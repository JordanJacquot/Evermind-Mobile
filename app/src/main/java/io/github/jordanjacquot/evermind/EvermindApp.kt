package io.github.jordanjacquot.evermind

import android.app.Application
import io.github.jordanjacquot.evermind.donnees.BaseEvermind
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.voix.Voix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** L'application : crée une seule fois ce que tous les écrans partagent. */
class EvermindApp : Application() {
    /** Ce qui doit continuer même quand on change d'écran (téléchargement des modèles, moteurs de la voix). */
    val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val depot: DepotRendezVous by lazy { DepotRendezVous(BaseEvermind.ouvrir(this).rendezVous()) }
    val voix: Voix by lazy { Voix(this, portee) }

    override fun onCreate() {
        super.onCreate()
        voix.prechauffer()
    }
}
