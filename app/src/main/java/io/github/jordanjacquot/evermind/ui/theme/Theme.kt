package io.github.jordanjacquot.evermind.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import io.github.jordanjacquot.evermind.donnees.Sorte
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Les thèmes d'Evermind PC (themes.ps1), les mêmes que Nevermind. Toute l'app est dessinée avec la palette Néon ;
// un thème « traduit » chaque couleur : les couleurs de la marque (cyan, violet, magenta) prennent celles du thème,
// les gris et les fonds prennent sa teinte. Les couleurs d'état (vert, orange, rouge, bleu) ne changent jamais.

data class ThemeEvermind(
    val id: String,
    val nom: String,
    val description: String,
    val p: Long,
    val s: Long,
    val t: Long,
    /** Teinte des gris et des fonds (null : ils restent tels quels). */
    val nh: Double?,
    val ns: Double,
    val correspondances: Map<Int, Int> = emptyMap(),
    val bonjour: String = "%s ! Je me souviens de tout pour toi.",
    val police: FontFamily? = null,
)

val Themes = listOf(
    ThemeEvermind("neon", "Néon", "Le thème d'Evermind : cyan, violet et magenta sur fond de nuit.",
        0xFF00E5FF, 0xFFB04BFF, 0xFFFF2EB5, null, 1.0),
    ThemeEvermind("crepuscule", "Crépuscule", "Le style Néon recoloré : orange, rose et or, comme un coucher de soleil.",
        0xFFFF8A3D, 0xFFFF3D7F, 0xFFFFC83D, 345.0, 0.75,
        mapOf(0x0C0920 to 0x1A0B12, 0x06050F to 0x0B0508, 0x0B0820 to 0x170910, 0x08060F to 0x160806)),
    ThemeEvermind("terminal", "Terminal", "Pour les geeks : vert phosphore sur fond noir, tout en police de code.",
        0xFF33FF77, 0xFFB6FF3B, 0xFF00E0A0, 140.0, 0.55,
        mapOf(0x0C0920 to 0x030B06, 0x06050F to 0x000302, 0x0B0820 to 0x020A05, 0x08060F to 0x001A08),
        bonjour = "> %s. je me souviens de tout._", police = FontFamily.Monospace),
    ThemeEvermind("arcade", "Arcade", "Jaune, rouge et bleu vifs sur fond bleu nuit, comme une vieille borne.",
        0xFFFBD000, 0xFFE52521, 0xFF1E88E5, 228.0, 0.9,
        mapOf(0x0C0920 to 0x0B1640, 0x06050F to 0x050A20, 0x0B0820 to 0x0A1338, 0x08060F to 0x1A1000)),
    ThemeEvermind("rubis", "Rubis", "Rouge vif, jaune et bleu sur un gris anthracite sobre.",
        0xFFFF3B3B, 0xFFFFDE00, 0xFF3B6FFF, 0.0, 0.15,
        mapOf(0x0C0920 to 0x17171D, 0x06050F to 0x0A0A0D, 0x0B0820 to 0x141419, 0x08060F to 0x1A0606)),
)

fun themeParId(id: String?) = Themes.firstOrNull { it.id == id } ?: Themes.first()

private fun versHsl(rgb: Int): DoubleArray {
    val r = (rgb shr 16 and 0xFF) / 255.0
    val g = (rgb shr 8 and 0xFF) / 255.0
    val b = (rgb and 0xFF) / 255.0
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
    val d = mx - mn
    val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> (g - b) / d + (if (g < b) 6 else 0)
        g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    }
    return doubleArrayOf(h * 60.0, s, l)
}

private fun depuisHsl(h0: Double, s: Double, l: Double): Int {
    val h = ((h0 % 360) + 360) % 360
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs(((h / 60.0) % 2) - 1))
    val m = l - c / 2
    val (r, g, b) = when (floor(h / 60.0).toInt()) {
        0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
        3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
    }
    fun o(v: Double) = (255 * (v + m)).roundToInt().coerceIn(0, 255)
    return (o(r) shl 16) or (o(g) shl 8) or o(b)
}

/** Une couleur Néon (0xAARRGGBB) traduite dans le thème (ConvertTo-ThemeHex sur PC). */
fun ThemeEvermind.traduire(argb: Long): Color {
    if (id == "neon") return Color(argb)
    val alpha = (argb ushr 24).toInt() and 0xFF
    val rgb = (argb and 0xFFFFFF).toInt()
    val sortie = correspondances[rgb] ?: when (rgb) {
        0x00E5FF -> (p and 0xFFFFFF).toInt()
        0xB04BFF -> (s and 0xFFFFFF).toInt()
        0xFF2EB5 -> (t and 0xFFFFFF).toInt()
        else -> {
            val (h, sa, l) = versHsl(rgb).let { Triple(it[0], it[1], it[2]) }
            val marque = if (sa > 0.5 && l > 0.2 && l < 0.92) when {
                h >= 170 && h < 200 -> p
                h >= 228 && h < 292 -> s
                h >= 292 && h < 336 -> t
                else -> null
            } else null
            when {
                marque != null -> {
                    // Même clarté que la couleur d'origine, teinte et saturation du thème
                    val bh = versHsl((marque and 0xFFFFFF).toInt())
                    val dl = l - 0.5
                    if (abs(dl) < 0.06) (marque and 0xFFFFFF).toInt()
                    else depuisHsl(bh[0], bh[1], min(0.95, max(0.05, bh[2] + dl)))
                }
                (sa < 0.35 || l < 0.16) && l > 0.015 && l < 0.97 && nh != null -> depuisHsl(nh, min(1.0, sa * ns), l)
                else -> rgb
            }
        }
    }
    return Color((alpha.toLong() shl 24) or sortie.toLong())
}

/** Les couleurs d'Evermind dans le thème choisi (la palette Néon de interface.xaml). */
class CouleursEvermind(val theme: ThemeEvermind) {
    private fun c(argb: Long) = theme.traduire(argb)
    val fondHaut = c(0xFF0C0920)
    val fondBas = c(0xFF06050F)
    val panneau = c(0xF2141026)
    val p = c(0xFF00E5FF)
    val s = c(0xFFB04BFF)
    val t = c(0xFFFF2EB5)
    val violet = c(0xFF7A3CFF)
    val texte = c(0xFFEEEBF7)
    val discret = c(0xFFA6A1BC)
    val tresDiscret = c(0xFF77718F)
    val bien = c(0xFF7FF4FF)
    val mal = Color(0xFFFF7A9C)
    fun sorte(s: Sorte) = c(s.couleur)
}

val LocalCouleurs = staticCompositionLocalOf { CouleursEvermind(Themes.first()) }

/** Raccourci : les couleurs d'Evermind du thème en cours. */
val ev: CouleursEvermind @Composable get() = LocalCouleurs.current

val Sorte.teinte: Color @Composable get() = LocalCouleurs.current.sorte(this)

@Composable
fun EvermindTheme(themeId: String = "neon", contenu: @Composable () -> Unit) {
    val ce = remember(themeId) { CouleursEvermind(themeParId(themeId)) }
    val schema = darkColorScheme(
        primary = ce.p,
        onPrimary = ce.fondBas,
        primaryContainer = ce.p,
        onPrimaryContainer = ce.fondBas,
        secondary = ce.s,
        onSecondary = ce.fondBas,
        secondaryContainer = ce.p.copy(alpha = 0.22f),
        onSecondaryContainer = ce.texte,
        tertiary = ce.t,
        background = ce.fondHaut,
        onBackground = ce.texte,
        surface = ce.panneau,
        onSurface = ce.texte,
        surfaceVariant = Color.White.copy(alpha = 0.06f),
        onSurfaceVariant = ce.discret,
        surfaceContainer = ce.panneau,
        surfaceContainerHigh = ce.panneau,
        surfaceContainerHighest = ce.panneau,
        surfaceContainerLow = ce.panneau,
        outline = Color.White.copy(alpha = 0.18f),
        outlineVariant = Color.White.copy(alpha = 0.10f),
        error = ce.mal,
        // (les bandeaux en bas de l'écran, « Ajouté : ... Annuler »)
        inverseSurface = ce.panneau,
        inverseOnSurface = ce.texte,
        inversePrimary = ce.p,
    )
    val typo = ce.theme.police?.let { f ->
        val base = Typography()
        fun TextStyle.p() = copy(fontFamily = f)
        Typography(
            displayLarge = base.displayLarge.p(), displayMedium = base.displayMedium.p(), displaySmall = base.displaySmall.p(),
            headlineLarge = base.headlineLarge.p(), headlineMedium = base.headlineMedium.p(), headlineSmall = base.headlineSmall.p(),
            titleLarge = base.titleLarge.p(), titleMedium = base.titleMedium.p(), titleSmall = base.titleSmall.p(),
            bodyLarge = base.bodyLarge.p(), bodyMedium = base.bodyMedium.p(), bodySmall = base.bodySmall.p(),
            labelLarge = base.labelLarge.p(), labelMedium = base.labelMedium.p(), labelSmall = base.labelSmall.p(),
        )
    } ?: Typography()
    CompositionLocalProvider(LocalCouleurs provides ce) {
        MaterialTheme(colorScheme = schema, typography = typo, content = contenu)
    }
}
