package io.github.jordanjacquot.evermind.voix

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.remplace
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.quandEnTexte
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La voix d'Evermind, partagée par toute l'app : les réglages, l'installation des modèles et les moteurs
 * (chargés une seule fois, à l'avance dès que c'est installé, pour répondre vite).
 */
class Voix(private val contexte: Context, private val portee: CoroutineScope) {
    val installation = Installation(contexte, portee)
    private val reglages = contexte.getSharedPreferences("voix", Context.MODE_PRIVATE)

    private val _voixChoisie = MutableStateFlow(VoixKokoro.entries.firstOrNull { it.name == reglages.getString("voix", null) } ?: VoixKokoro.K30)
    val voixChoisie: StateFlow<VoixKokoro> = _voixChoisie
    private val _eveilActif = MutableStateFlow(reglages.getBoolean("eveil", false))
    /** « OK Evermind » voulu (dans les réglages) ; il n'écoute que si c'est installé et qu'Evermind est à l'écran. */
    val eveilActif: StateFlow<Boolean> = _eveilActif
    /** Evermind répond à voix haute (si la voix est installée). */
    private val _repondre = MutableStateFlow(reglages.getBoolean("repondre", true))
    val repondre: StateFlow<Boolean> = _repondre

    fun choisirVoix(v: VoixKokoro) { _voixChoisie.value = v; reglages.edit().putString("voix", v.name).apply() }
    fun activerEveil(oui: Boolean) { _eveilActif.value = oui; reglages.edit().putBoolean("eveil", oui).apply() }
    fun activerReponse(oui: Boolean) { _repondre.value = oui; reglages.edit().putBoolean("repondre", oui).apply() }

    val voixInstallee get() = Modeles.installe(contexte, Pack.VOIX)
    val eveilInstalle get() = Modeles.installe(contexte, Pack.EVEIL)

    private val verrou = Mutex()
    private var whisper: Whisper? = null
    private var kokoro: Kokoro? = null
    private var eveil: Eveil? = null
    private val parole = Mutex()

    private suspend fun <T> moteur(existant: () -> T?, garder: (T) -> Unit, creer: () -> T): T = withContext(Dispatchers.Default) {
        verrou.withLock { existant() ?: creer().also(garder) }
    }

    /** Charge les moteurs installés pendant qu'on ne fait rien (Whisper met quelques secondes à se charger). */
    fun prechauffer() {
        if (!voixInstallee) return
        portee.launch {
            runCatching {
                moteur({ whisper }, { whisper = it }) { Whisper(contexte) }
                moteur({ kokoro }, { kokoro = it }) { Kokoro(contexte) }
            }
        }
    }

    /** Les mots de l'agenda (titres, lieux, personnes) : la dictée les écrit comme dans l'agenda. */
    var motsAgenda: suspend () -> List<String> = { emptyList() }

    /** Écoute une phrase et l'écrit (Whisper), avec les noms corrigés (villes, métiers, mots de l'agenda). null si rien n'a été dit. */
    suspend fun dicter(debut: () -> Unit = {}): String? = withContext(Dispatchers.Default) {
        val w = moteur({ whisper }, { whisper = it }) { Whisper(contexte) }
        val son = w.ecouterPhrase(debut) ?: return@withContext null
        val texte = nettoyer(w.transcrire(son)).takeIf { it.contains(Regex("\\w")) } ?: return@withContext null
        runCatching { Correcteur.du(contexte).corriger(texte, motsAgenda()) }.getOrDefault(texte)
    }

    /** Dit la phrase avec la voix choisie (rien si la voix n'est pas installée ou si Evermind ne doit pas répondre). */
    suspend fun dire(texte: String) {
        if (!voixInstallee || !_repondre.value) return
        withContext(Dispatchers.Default) {
            val k = moteur({ kokoro }, { kokoro = it }) { Kokoro(contexte) }
            parole.withLock { k.dire(pourLaVoix(texte), _voixChoisie.value) }
        }
    }

    /** Fait écouter une voix (réglages). */
    suspend fun essayer(v: VoixKokoro) = withContext(Dispatchers.Default) {
        val k = moteur({ kokoro }, { kokoro = it }) { Kokoro(contexte) }
        parole.withLock { k.dire("Salut ! C'est noté : dentiste, mardi 14 octobre à 15 heures 30.", v) }
    }

    /** Attend « OK Evermind » (true), ou d'être arrêté (false). */
    suspend fun attendreReveil(): Boolean = withContext(Dispatchers.Default) {
        moteur({ eveil }, { eveil = it }) { Eveil(contexte) }.attendre()
    }

    /** Le petit bip qui dit « je t'écoute » (ou, plus grave, « je n'ai rien entendu »). */
    fun bip(ok: Boolean = true) {
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            t.startTone(if (ok) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 150)
            portee.launch { kotlinx.coroutines.delay(400); t.release() }
        }
    }

    companion object {
        /** « OK Evermind » tel que Whisper l'écrit (« OK, Evermind », « OK Ever Mind », « OK Évermind »...), comme sur PC. */
        private const val RxReveil = "(?:(?:ok|okay|o\\.\\s?k\\.?|hey|hé|au fait|oh|eh|euh|et)\\W*)?(?:[eé]ver\\s*-?\\s*m\\w*|[eé]vermaïnd|[eé]?\\s*v[eé]r?\\s*m[aeiï]n\\w*)"

        /** Ce que Whisper écrit en plus : bruits entre crochets ou parenthèses, et « OK Evermind » au début (Invoke-Dictee). */
        fun nettoyer(texte: String): String =
            texte.remplace("\\[[^\\]]*\\]|\\([^)]*\\)", " ").remplace("\\s+", " ").trim()
                .remplace("^\\W*$RxReveil\\b\\W*", "").trim()

        /** Les heures dites en entier : « 15 h 30 » devient « 15 heures 30 ». */
        fun pourLaVoix(texte: String) = texte.remplace("(\\d+) h (\\d+)", "$1 heures $2").remplace("(\\d+) h\\b", "$1 heures")

        /** Ce qu'Evermind répond quand le rendez-vous est noté (comme sur PC). */
        fun noteAjoute(rdvs: List<RendezVous>): String {
            val r = rdvs.first()
            val rep = r.laRepetition?.enTexte()
            var dit = when {
                r.journee -> "${r.titre}, " + (rep ?: r.quandEnTexte())
                rep != null -> "${r.titre}, $rep à ${r.quand.toLocalTime().enTexte()}"
                else -> "${r.titre}, ${r.quandEnTexte()}"
            }
            if (rdvs.size > 1) dit += ", et ${rdvs.size - 1} autres fois"
            if (r.lieu.isNotBlank()) dit += ", ${r.lieu}"
            if (r.avec.isNotEmpty()) dit += ", avec ${r.avec.joinToString(" et ")}"
            return "C'est noté : $dit."
        }

        /** Ce qu'Evermind répond quand il manque quelque chose (comme sur PC). */
        fun manque(m: Manque): String = when (m) {
            Manque.HEURE -> "Je n'ai pas compris l'heure. Tu peux la corriger dans Evermind."
            Manque.JOUR -> "Je n'ai pas compris le jour. Tu peux le corriger dans Evermind."
            Manque.QUAND -> "Je n'ai pas compris le jour ni l'heure. Tu peux corriger dans Evermind."
            Manque.PASSE -> "Ce moment est déjà passé."
            Manque.INVALIDE -> "Cette date n'existe pas."
            Manque.MATIN -> "Je ne sais pas si c'est le matin ou le soir. Tu peux le corriger dans Evermind."
            Manque.RIEN -> ""
        }
    }
}
