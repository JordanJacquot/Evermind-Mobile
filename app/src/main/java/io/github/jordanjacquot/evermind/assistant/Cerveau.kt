package io.github.jordanjacquot.evermind.assistant

import android.app.ActivityManager
import android.content.Context
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.voix.Modeles
import io.github.jordanjacquot.evermind.voix.Pack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Les assistants possibles, du plus grand au plus léger. Chaque téléphone a celui que sa mémoire permet sans gêner le
 * reste (Evermind ne doit jamais prendre toute la mémoire) ; les scores viennent du catalogue de demandes du PC.
 */
enum class ModeleAssistant(
    val pack: Pack,
    val nom: String,
    val description: String,
    /** Mémoire du téléphone conseillée (en Go, telle qu'Android la compte : un « 6 Go » en montre environ 5,6). */
    val ramConseillee: Double,
    val penalitePresence: Float,
    val avertissement: String? = null,
) {
    GRAND(Pack.IA_GRAND, "Qwen3 4B", "Le même que sur PC : il comprend même les phrases les plus tordues.", 10.5, 0f),
    MOYEN(Pack.IA_MOYEN, "Qwen3.5 2B", "Comprend presque toutes les phrases tordues.", 5.2, 1.5f),
    LEGER(Pack.IA_LEGER, "Qwen3.5 0.8B", "Léger, pour les petits téléphones.", 3.3, 1.5f,
        avertissement = "Il se trompe parfois sur les phrases qu'Evermind ne comprend pas seul : écoute bien sa réponse, et dis « annule » si ce n'est pas ce que tu voulais."),
    ;

    val fichier get() = Modeles.fichiers.first { it.pack == pack }

    companion object {
        /** Le meilleur assistant que ce téléphone peut faire tourner, ou null (moins de 4 Go : les règles d'Evermind suffisent). */
        fun conseille(ramGo: Double): ModeleAssistant? = entries.firstOrNull { ramGo >= it.ramConseillee }

        fun ramGo(contexte: Context): Double {
            val info = ActivityManager.MemoryInfo()
            contexte.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return info.totalMem / 1_073_741_824.0
        }
    }
}

/**
 * L'assistant du téléphone : les règles d'Evermind (toujours là, immédiates), et en plus, pour les phrases qu'elles ne
 * reconnaissent pas, le modèle choisi, chargé seulement quand il sert et libéré après 10 minutes sans question (comme
 * sur PC), ou tout de suite si Android manque de mémoire.
 */
class Cerveau(private val contexte: Context, private val portee: CoroutineScope, rappelsParDefaut: (RendezVous) -> List<String>) {
    private val reglages = contexte.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    val ramGo = ModeleAssistant.ramGo(contexte)
    val conseille = ModeleAssistant.conseille(ramGo)

    private val _choisi = MutableStateFlow(reglages.getString("modele", null)?.let { n -> ModeleAssistant.entries.firstOrNull { it.name == n } })
    /** L'assistant installé et utilisé (null : pas d'assistant). */
    val choisi: StateFlow<ModeleAssistant?> = _choisi

    private val _actif = MutableStateFlow(reglages.getBoolean("actif", true))
    val actif: StateFlow<Boolean> = _actif

    fun choisir(m: ModeleAssistant?) { _choisi.value = m; reglages.edit().putString("modele", m?.name).apply(); liberer(); preparer() }
    fun activer(oui: Boolean) { _actif.value = oui; reglages.edit().putBoolean("actif", oui).apply(); if (!oui) liberer() else preparer() }

    /** Prêt à répondre : installé, activé, et le téléphone sait le faire tourner. */
    val pret: Boolean
        get() {
            val m = _choisi.value ?: return false
            return _actif.value && Modeles.installe(contexte, m.pack) && MoteurLlama.disponible(contexte)
        }

    private val verrou = Mutex()
    private var charge: ModeleAssistant? = null
    private var veille: Job? = null

    /** Les consignes déjà lues par ce modèle, gardées d'un lancement à l'autre. */
    private fun etat(m: ModeleAssistant) = java.io.File(contexte.filesDir, "assistant/${m.name.lowercase()}.consignes")

    private val moteur = MoteurIA { messages, schema ->
        withContext(Dispatchers.Default) {
            verrou.withLock {
                if (!pret) return@withLock null
                val m = _choisi.value ?: return@withLock null
                if (charge != m) {
                    MoteurLlama.liberer()
                    val fils = min(4, max(2, Runtime.getRuntime().availableProcessors() - 2))
                    etat(m).parentFile?.mkdirs()
                    if (!MoteurLlama.charger(Modeles.fichier(contexte, m.fichier.vers).path, 6144, fils, etat(m).path)) { charge = null; return@withLock null }
                    charge = m
                }
                val r = MoteurLlama.repondre(messagesEnJson(messages), schema, 400, m.penalitePresence)
                android.util.Log.i("EvermindAssistant", "réponse : ${r?.take(400)}")
                endormirPlusTard()
                r
            }
        }
    }

    val assistant = Assistant(moteur, rappelsParDefaut)

    /** L'IA va-t-elle servir ? (installée et activée) */
    val avecIA: Boolean get() = pret

    /**
     * La première lecture des consignes est longue (plusieurs minutes sur un petit téléphone) : elle est faite une fois
     * pour toutes en arrière-plan, juste après l'installation ou au lancement, et gardée sur le disque.
     */
    fun preparer() {
        val m = _choisi.value ?: return
        if (!pret || etat(m).exists()) return
        portee.launch(Dispatchers.Default) {
            val agenda = AgendaIA(emptyList(), java.time.LocalDateTime.now())
            val ctx = Consignes.contexte(agenda)
            moteur.repondre(listOf(MessageIA("system", ctx.systeme), MessageIA("user", "Bonjour")), SchemaIA)
            android.util.Log.i("EvermindAssistant", "consignes préparées : ${etat(m).exists()}")
        }
    }

    /** Après 10 minutes sans question, la mémoire est rendue au téléphone. */
    private fun endormirPlusTard() {
        veille?.cancel()
        veille = portee.launch { delay(10 * 60_000L); liberer() }
    }

    fun liberer() {
        portee.launch(Dispatchers.Default) {
            verrou.withLock { if (charge != null) { MoteurLlama.liberer(); charge = null } }
        }
    }
}
