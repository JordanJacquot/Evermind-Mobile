package io.github.jordanjacquot.evermind.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.jordanjacquot.evermind.donnees.Sorte

// Les couleurs du thème sombre d'Evermind PC ; les autres thèmes viendront avec l'étape 5
val Fond = Color(0xFF0E1020)
val Surface = Color(0xFF171A2E)
val SurfaceHaute = Color(0xFF22263F)
val Cyan = Color(0xFF00E5FF)
val Texte = Color(0xFFE8EAF6)
val TexteDiscret = Color(0xFF9AA0C3)
val Rouge = Color(0xFFFF4D6D)

private val Couleurs = darkColorScheme(
    primary = Cyan,
    onPrimary = Fond,
    secondary = Cyan,
    onSecondary = Fond,
    background = Fond,
    onBackground = Texte,
    surface = Surface,
    onSurface = Texte,
    surfaceVariant = SurfaceHaute,
    onSurfaceVariant = TexteDiscret,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHaute,
    error = Rouge,
)

val Sorte.teinte: Color get() = Color(couleur)

@Composable
fun EvermindTheme(contenu: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Couleurs, content = contenu)
}
