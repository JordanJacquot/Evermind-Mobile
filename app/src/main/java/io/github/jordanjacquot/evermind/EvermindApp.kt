package io.github.jordanjacquot.evermind

import android.app.Application
import io.github.jordanjacquot.evermind.donnees.BaseEvermind
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.rappels.Notifications
import io.github.jordanjacquot.evermind.rappels.Planificateur
import io.github.jordanjacquot.evermind.voix.Voix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** L'application : crée une seule fois ce que tous les écrans partagent. */
class EvermindApp : Application() {
    /** Ce qui doit continuer même quand on change d'écran (téléchargement des modèles, moteurs de la voix). */
    val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val depot: DepotRendezVous by lazy { DepotRendezVous(BaseEvermind.ouvrir(this).rendezVous()) { Planificateur.reprogrammer(this) } }
    val voix: Voix by lazy { Voix(this, portee) }

    /** Le thème choisi (Réglages) : neon, crepuscule, terminal, arcade ou rubis. */
    private val _theme by lazy { MutableStateFlow(getSharedPreferences("apparence", MODE_PRIVATE).getString("theme", "neon") ?: "neon") }
    val theme: StateFlow<String> get() = _theme
    fun choisirTheme(id: String) {
        _theme.value = id
        getSharedPreferences("apparence", MODE_PRIVATE).edit().putString("theme", id).apply()
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.creerCanal(this)
        Planificateur.reprogrammer(this)
        voix.prechauffer()
    }
}
