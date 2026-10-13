package io.github.jordanjacquot.evermind.voix

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * La voix de bout en bout, sans micro : Kokoro dit une phrase, Whisper l'écrit, Evermind la comprend.
 * Seulement si la voix est installée sur le téléphone (Réglages, « Installer la voix »).
 */
@RunWith(AndroidJUnit4::class)
class VoixBoucleTest {
    @Test fun kokoroPuisWhisper() {
        val contexte = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("voix pas installée", Modeles.installe(contexte, Pack.VOIX))
        val dossier = "${Modeles.dossier(contexte)}/kokoro"
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
            model = "$dossier/model.int8.onnx", voices = "$dossier/voices.bin", tokens = "$dossier/tokens.txt",
            dataDir = "$dossier/espeak-ng-data", lang = "fr"), numThreads = 2)))
        val son = tts.generate("Mardi prochain à quinze heures, dentiste.", VoixKokoro.K30.locuteur, 1.0f)
        tts.release()

        // Kokoro parle à 24 kHz, Whisper écoute à 16 kHz
        val pas = son.sampleRate.toDouble() / TAUX
        val en16k = FloatArray((son.samples.size / pas).toInt()) { i ->
            val x = i * pas
            val a = x.toInt()
            val b = minOf(a + 1, son.samples.size - 1)
            (son.samples[a] * (1 - (x - a)) + son.samples[b] * (x - a)).toFloat()
        }
        val debut = System.currentTimeMillis()
        val texte = Voix.nettoyer(Whisper(contexte).transcrire(en16k))
        Log.i("Evermind", "Whisper (${System.currentTimeMillis() - debut} ms) : « $texte »")

        val p = lirePhrase(texte, LocalDateTime.of(2026, 10, 10, 18, 30))
        assertEquals("« $texte »", Manque.RIEN, p.manque)
        // (le titre peut être un peu déformé par une voix de synthèse : seuls le jour et l'heure sont vérifiés)
        assertEquals("« $texte »", LocalDateTime.of(2026, 10, 13, 15, 0), p.quand)
    }
}
