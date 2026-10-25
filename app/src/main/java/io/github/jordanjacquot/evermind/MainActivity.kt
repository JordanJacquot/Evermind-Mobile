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
import io.github.jordanjacquot.evermind.ui.theme.EvermindTheme

class MainActivity : ComponentActivity() {
    /** La fiche à ouvrir (« Voir » dans un rappel). */
    private var ficheDemandee by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Barres du téléphone transparentes, icônes claires : le fond de nuit va jusqu'en haut
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (savedInstanceState == null) ficheDemandee = intent.getStringExtra(Notifications.EXTRA_FICHE)
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
        intent.getStringExtra(Notifications.EXTRA_FICHE)?.let { ficheDemandee = it }
    }
}
