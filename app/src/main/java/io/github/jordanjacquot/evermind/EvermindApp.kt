package io.github.jordanjacquot.evermind

import android.app.Application
import io.github.jordanjacquot.evermind.assistant.Cerveau
import io.github.jordanjacquot.evermind.donnees.BaseEvermind
import io.github.jordanjacquot.evermind.rappels.ReglagesRappels
import io.github.jordanjacquot.evermind.rappels.rappelsVoulus
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.rappels.Notifications
import io.github.jordanjacquot.evermind.rappels.Planificateur
import io.github.jordanjacquot.evermind.voix.Voix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** L'application : crée une seule fois ce que tous les écrans partagent. */
class EvermindApp : Application() {
    /** Ce qui doit continuer même quand on change d'écran (téléchargement des modèles, moteurs de la voix). */
    val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val depot: DepotRendezVous by lazy { DepotRendezVous(BaseEvermind.ouvrir(this).rendezVous()) { Planificateur.reprogrammer(this) } }
    val voix: Voix by lazy {
        Voix(this, portee).also { v -> v.motsAgenda = { depot.liste().flatMap { r -> listOf(r.titre, r.lieu) + r.avec } } }
    }
    /** Ce que l'assistant change dans l'agenda, et « annule » (JournalAssistant). */
    val journal: io.github.jordanjacquot.evermind.ui.agenda.JournalAssistant by lazy { io.github.jordanjacquot.evermind.ui.agenda.JournalAssistant(depot) }
    /** Les mises à jour d'Evermind (le bandeau de l'écran principal). */
    val maj: io.github.jordanjacquot.evermind.maj.MiseAJour by lazy { io.github.jordanjacquot.evermind.maj.MiseAJour(this, portee) }
    /** L'assistant (le modèle choisi selon la mémoire du téléphone), chargé seulement quand on lui parle. */
    private val cerveauPret = lazy { Cerveau(this, portee) { r -> r.rappelsVoulus(ReglagesRappels(this)) } }
    val cerveau: Cerveau by cerveauPret

    /** Le thème choisi (Réglages) : neon, crepuscule, terminal, arcade ou rubis. */
    private val _theme by lazy { MutableStateFlow(getSharedPreferences("apparence", MODE_PRIVATE).getString("theme", "neon") ?: "neon") }
    val theme: StateFlow<String> get() = _theme
    fun choisirTheme(id: String) {
        _theme.value = id
        getSharedPreferences("apparence", MODE_PRIVATE).edit().putString("theme", id).apply()
    }

    /** Android manque de mémoire, ou Evermind passe en arrière-plan : l'assistant rend la sienne. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // (un réveil par une alarme n'a pas chargé l'assistant : rien à rendre)
        if (level >= TRIM_MEMORY_RUNNING_LOW && cerveauPret.isInitialized()) cerveau.liberer()
    }

    /**
     * Tout démarrage de l'appli passe ici, y compris le réveil par une alarme de rappel ou par le redémarrage du
     * téléphone : seulement les notifications et les rappels. La voix, l'IA et la mise à jour attendent l'écran.
     */
    override fun onCreate() {
        super.onCreate()
        Notifications.creerCanal(this)
        Planificateur.reprogrammer(this)
    }

    private var ecranPrepare = false

    /** L'écran d'Evermind s'ouvre (MainActivity) : la voix, l'IA et la mise à jour se préparent, une fois. */
    fun preparerEcran() {
        if (ecranPrepare) return
        ecranPrepare = true
        // Les consignes de l'IA lues une fois pour toutes (si elle est installée et pas encore prête), sans gêner le lancement
        // (et une nouvelle version du modèle téléchargée toute seule, en Wi-Fi)
        portee.launch { kotlinx.coroutines.delay(15_000); cerveau.remplacerSiBesoin(voix.installation); cerveau.preparer() }
        // Le lexique des noms (villes, métiers) prêt avant la première dictée
        portee.launch(kotlinx.coroutines.Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            runCatching { io.github.jordanjacquot.evermind.voix.Correcteur.du(this@EvermindApp) }
            android.util.Log.i("Evermind", "lexique des noms prêt en ${System.currentTimeMillis() - t0} ms")
        }
        voix.prechauffer()
        // Une nouvelle version ? (au plus une fois par jour, sans gêner le lancement)
        portee.launch { kotlinx.coroutines.delay(5_000); maj.verifierSiBesoin() }
    }
}
