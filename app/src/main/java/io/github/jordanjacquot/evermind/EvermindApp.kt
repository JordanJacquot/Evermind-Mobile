package io.github.jordanjacquot.evermind

import android.app.Application
import io.github.jordanjacquot.evermind.donnees.BaseEvermind
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous

/** L'application : crée une seule fois ce que tous les écrans partagent. */
class EvermindApp : Application() {
    val depot: DepotRendezVous by lazy { DepotRendezVous(BaseEvermind.ouvrir(this).rendezVous()) }
}
