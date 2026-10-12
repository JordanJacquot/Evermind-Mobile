package io.github.jordanjacquot.evermind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.jordanjacquot.evermind.ui.EvermindNavigation
import io.github.jordanjacquot.evermind.ui.theme.EvermindTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EvermindTheme {
                EvermindNavigation()
            }
        }
    }
}
