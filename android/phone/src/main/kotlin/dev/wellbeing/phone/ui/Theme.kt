package dev.wellbeing.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.ThemeChoice
import dev.wellbeing.running.Tokens

/** One pastel family: a fill and the ink used on it. */
data class Pastel(val fill: Color, val ink: Color)

/** The approved palette, read from the shared tokens. */
class AppColors(val isDark: Boolean) {
    private fun c(name: String) = Color(0xFF000000 or Tokens.color(name, isDark).toLong())

    val bg = c("bg")
    val surface = c("surface")
    val surface2 = c("surface-2")
    val surface3 = c("surface-3")
    val ink = c("ink")
    val ink2 = c("ink-2")
    val ink3 = c("ink-3")
    val accent = c("accent")
    val accentSoft = c("accent-soft")
    val onAccent = c("on-accent")
    val lav = Pastel(c("p-lav"), c("i-lav"))
    val mint = Pastel(c("p-mint"), c("i-mint"))
    val peach = Pastel(c("p-peach"), c("i-peach"))
    val sky = Pastel(c("p-sky"), c("i-sky"))
    val butter = Pastel(c("p-butter"), c("i-butter"))
    val rose = Pastel(c("p-rose"), c("i-rose"))
    val zoneInk = Color(0xFF000000 or Tokens.ZONE_INK.toLong())

    fun zone(z: Int) = Color(0xFF000000 or Tokens.zone(z).toLong())
}

val LocalColors = staticCompositionLocalOf { AppColors(false) }

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Bricolage Grotesque: titles and figures. */
val Display = FontFamily(variable(R.font.bricolage_grotesque, 500), variable(R.font.bricolage_grotesque, 600), variable(R.font.bricolage_grotesque, 700))

/** Manrope: body text and labels. */
val Body = FontFamily(variable(R.font.manrope, 400), variable(R.font.manrope, 500), variable(R.font.manrope, 600), variable(R.font.manrope, 700))

/** Figures keep the same width so they do not jitter while they change. */
private const val TABULAR = "tnum"

object Type {
    val h1 = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 31.sp)
    val h2 = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
    val h3 = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)
    val strong = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val eyebrow = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.84.sp)
    val label = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.52.sp)

    fun figure(size: Int) = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.02f).sp,
        fontFeatureSettings = TABULAR,
    )
}

object Radius {
    val card = RoundedCornerShape(28.dp)
    val tile = RoundedCornerShape(20.dp)
    val row = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(50)
}

@Composable
fun WellbeingTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }
    val c = AppColors(dark)
    // Material components take the approved palette; the phone's dynamic colours are not used.
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink, surfaceVariant = c.surface2, onSurfaceVariant = c.ink2, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink, surfaceVariant = c.surface2, onSurfaceVariant = c.ink2, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent)
    }
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = Type.body, bodyMedium = Type.body, labelLarge = Type.strong, titleLarge = Type.h2),
            shapes = Shapes(small = Radius.row, medium = Radius.tile, large = Radius.card),
            content = content,
        )
    }
}
