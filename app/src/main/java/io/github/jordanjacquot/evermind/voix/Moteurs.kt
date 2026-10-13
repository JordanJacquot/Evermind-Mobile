package io.github.jordanjacquot.evermind.voix

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.min

// Les moteurs de la voix, chacun chargé une fois puis gardé : Whisper (dictée), Silero (fin de phrase),
// Kokoro (la voix d'Evermind) et Vosk (« OK Evermind »). Tout tourne sur le téléphone. À appeler hors du fil principal.

const val TAUX = 16_000

private fun nbFils() = min(4, max(1, Runtime.getRuntime().availableProcessors() - 2))

/** Le micro, en 16 kHz mono : la forme qu'attendent Whisper, Silero et Vosk. */
@SuppressLint("MissingPermission")   // (la permission est demandée avant d'écouter)
class Micro : AutoCloseable {
    private val taille = max(AudioRecord.getMinBufferSize(TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), TAUX)
    private val enregistreur = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, taille)

    init {
        check(enregistreur.state == AudioRecord.STATE_INITIALIZED) { "micro indisponible" }
        enregistreur.startRecording()
    }

    /** Lit le prochain morceau de son (bloquant) ; renvoie le nombre d'échantillons lus. */
    fun lire(tampon: ShortArray): Int = enregistreur.read(tampon, 0, tampon.size)

    override fun close() {
        runCatching { enregistreur.stop() }
        enregistreur.release()
    }
}

private fun versFloat(s: ShortArray, n: Int) = FloatArray(n) { s[it] / 32768f }

/** Whisper small, comme sur PC, avec Silero pour savoir quand la phrase est finie. */
class Whisper(contexte: Context) {
    private val dossier = Modeles.dossier(contexte)
    private val reconnaissance = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = TAUX, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$dossier/whisper/encoder.int8.onnx",
                    decoder = "$dossier/whisper/decoder.int8.onnx",
                    language = "fr",
                    task = "transcribe",
                ),
                tokens = "$dossier/whisper/tokens.txt",
                numThreads = nbFils(),
            ),
        ),
    )
    private val vadConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = "$dossier/silero_vad_v5.onnx",
            threshold = 0.5f,
            minSilenceDuration = 0.9f,   // une petite pause au milieu de la phrase ne la coupe pas
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = 25f,
        ),
        sampleRate = TAUX,
        numThreads = 1,
    )

    /**
     * Écoute une phrase au micro : commence quand on parle, s'arrête après un silence. « debut » est appelé quand la
     * parole commence. null si rien n'a été dit (au bout de quelques secondes) ou si l'écoute a été arrêtée.
     */
    suspend fun ecouterPhrase(debut: () -> Unit = {}, attenteMax: Int = 7): FloatArray? {
        val vad = Vad(config = vadConfig)
        try {
            Micro().use { micro ->
                val tampon = ShortArray(512)
                val phrase = ArrayList<FloatArray>()
                var parle = false
                var lus = 0L
                while (currentCoroutineContext().isActive) {
                    val n = micro.lire(tampon)
                    if (n <= 0) continue
                    lus += n
                    vad.acceptWaveform(versFloat(tampon, n))
                    if (!parle && vad.isSpeechDetected()) { parle = true; debut() }
                    if (!vad.empty()) {
                        while (!vad.empty()) { phrase += vad.front().samples; vad.pop() }
                        return phrase.reduce { a, b -> a + b }
                    }
                    if (!parle && lus > attenteMax.toLong() * TAUX) return null
                    if (lus > 30L * TAUX) break
                }
                vad.flush()
                while (!vad.empty()) { phrase += vad.front().samples; vad.pop() }
                return phrase.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b }
            }
        } finally {
            vad.release()
        }
    }

    fun transcrire(son: FloatArray): String {
        val flux = reconnaissance.createStream()
        try {
            flux.acceptWaveform(son, TAUX)
            reconnaissance.decode(flux)
            return reconnaissance.getResult(flux).text.trim()
        } finally {
            flux.release()
        }
    }
}

/** Les voix au choix, les mêmes que sur PC (la 1 est une vraie voix française ; les autres ont un petit accent). */
enum class VoixKokoro(val locuteur: Int, val nom: String, val description: String) {
    K30(30, "Voix 1", "Femme, française (conseillée)"),
    K31(31, "Voix 2", "Femme, petit accent"),
    K29(29, "Voix 3", "Homme, petit accent"),
    K36(36, "Voix 4", "Homme, petit accent"),
    K33(33, "Voix 5", "Homme grave, petit accent"),
}

/** La voix d'Evermind : Kokoro, la prononciation française venant d'espeak-ng (comme sur PC). */
class Kokoro(contexte: Context) {
    private val dossier = "${Modeles.dossier(contexte)}/kokoro"
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dossier/model.int8.onnx",
                    voices = "$dossier/voices.bin",
                    tokens = "$dossier/tokens.txt",
                    dataDir = "$dossier/espeak-ng-data",
                    lang = "fr",
                    lengthScale = 1.0f,
                ),
                numThreads = nbFils(),
            ),
        ),
    )

    /** Dit la phrase (bloquant jusqu'à la fin). */
    suspend fun dire(texte: String, voix: VoixKokoro) {
        val son = tts.generate(texte, voix.locuteur, 1.0f)
        if (!currentCoroutineContext().isActive || son.samples.isEmpty()) return
        val piste = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(son.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(son.samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            piste.write(son.samples, 0, son.samples.size, AudioTrack.WRITE_BLOCKING)
            piste.play()
            val duree = son.samples.size * 1000L / son.sampleRate
            val debut = System.currentTimeMillis()
            while (System.currentTimeMillis() - debut < duree + 150 && currentCoroutineContext().isActive) kotlinx.coroutines.delay(50)
        } finally {
            runCatching { piste.stop() }
            piste.release()
        }
    }
}

/**
 * L'écoute de « OK Evermind » (comme sur PC) : Vosk ne connaît que « ok ever mind », « ever mind » et « ok ève »
 * (le mot d'Eve, l'autre assistant, connu pour être refusé) ; tout le reste est oublié aussitôt, rien n'est enregistré.
 */
class Eveil(contexte: Context) {
    private val modele = Model("${Modeles.dossier(contexte)}/vosk")
    private val grammaire = "[\"ok ever mind\", \"ever mind\", \"ok ève\", \"[unk]\"]"
    private val rxMot = Regex("\"conf\"\\s*:\\s*([0-9.]+)[^}]*?\"word\"\\s*:\\s*\"([^\"]*)\"")

    /** Écoute jusqu'à entendre « OK Evermind » (renvoie true) ou être arrêtée (false). */
    suspend fun attendre(seuil: Double = 0.3): Boolean {
        Recognizer(modele, TAUX.toFloat(), grammaire).use { reco ->
            reco.setWords(true)
            Micro().use { micro ->
                val tampon = ShortArray(TAUX / 10)
                while (currentCoroutineContext().isActive) {
                    val n = micro.lire(tampon)
                    if (n <= 0) continue
                    if (reco.acceptWaveForm(tampon, n) && reveil(reco.result, seuil)) return true
                }
            }
        }
        return false
    }

    /** Fin d'une phrase : « ever mind » au début (après au plus deux « ok » ou bruits), assez sûr ? (Analyser sur PC) */
    private fun reveil(json: String, seuil: Double): Boolean {
        val mots = rxMot.findAll(json).map { it.groupValues[2] to it.groupValues[1].toDouble() }.toList()
        var d = 0
        while (d < 2 && d < mots.size && (mots[d].first == "ok" || mots[d].first == "[unk]") && !(d + 1 < mots.size && mots[d].first == "ever")) d++
        if (mots.size < d + 2 || (mots[d].first != "ever" && mots[d].first != "ève") || mots[d + 1].first != "mind") return false
        val conf = mots.take(d + 2).filter { it.first != "[unk]" }.minOfOrNull { it.second } ?: 1.0
        return conf >= seuil
    }
}
