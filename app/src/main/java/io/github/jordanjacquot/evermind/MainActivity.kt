package io.github.jordanjacquot.evermind

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        if (savedInstanceState == null) ficheDemandee = intent.getStringExtra(Notifications.EXTRA_FICHE)
        setContent {
            EvermindTheme {
                EvermindNavigation(ficheDemandee = ficheDemandee, ficheOuverte = { ficheDemandee = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_FICHE)?.let { ficheDemandee = it }
    }
}
