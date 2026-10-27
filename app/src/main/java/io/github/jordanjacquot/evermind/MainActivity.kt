package io.github.jordanjacquot.evermind

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jordanjacquot.evermind.ui.theme.FondEvermind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jordanjacquot.evermind.rappels.Notifications
import io.github.jordanjacquot.evermind.ui.EvermindNavigation
import io.github.jordanjacquot.evermind.ui.FicheDemandee
import io.github.jordanjacquot.evermind.ui.ficheValide
import io.github.jordanjacquot.evermind.ui.foisValide
import io.github.jordanjacquot.evermind.ui.theme.EvermindTheme

class MainActivity : ComponentActivity() {
    /** La fiche à ouvrir (« Voir » dans un rappel). */
    private var ficheDemandee by mutableStateOf<FicheDemandee?>(null)

    /**
     * La fiche demandée par l'intention (« Voir » d'un rappel, ou une autre appli : cet écran est ouvert à toutes) :
     * seulement un identifiant de rendez-vous, sinon rien. La notification du rappel est retirée.
     */
    private fun lireFiche(i: Intent?): FicheDemandee? {
        val id = ficheValide(runCatching { i?.getStringExtra(Notifications.EXTRA_FICHE) }.getOrNull()) ?: return null
        val foisTexte = runCatching { i?.getStringExtra(Notifications.EXTRA_FOIS) }.getOrNull()
        if (foisTexte != null) runCatching { Notifications.retirer(this, id, foisTexte) }
        return FicheDemandee(id, foisValide(foisTexte))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Barres du téléphone transparentes, icônes claires : le fond de nuit va jusqu'en haut
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (savedInstanceState == null) ficheDemandee = lireFiche(intent)
        val app = application as EvermindApp
        // (seulement maintenant : un réveil par une alarme ne charge ni la voix ni l'IA)
        app.preparerEcran()
        setContent {
            val theme by app.theme.collectAsStateWithLifecycle()
            EvermindTheme(theme) {
                // Le fond de nuit d'Evermind, derrière tous les écrans
                FondEvermind {
                    EvermindNavigation(ficheDemandee = ficheDemandee, ficheOuverte = { ficheDemandee = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lireFiche(intent)?.let { ficheDemandee = it }
    }
}
