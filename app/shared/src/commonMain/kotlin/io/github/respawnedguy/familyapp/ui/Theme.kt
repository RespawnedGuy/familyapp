package io.github.respawnedguy.familyapp.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp

/**
 * Fixed colours from docs/gestaltung/optik-haptik-klickwege.md ("Visuelle Sprache"), contrast
 * checked against WCAG 2.2 AA there. No Material You (D6): Lisa's orange stays Lisa's orange.
 */
@Immutable
data class FamilyColors(
    val background: Color,
    val card: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val overdue: Color,
    val dueSoon: Color,
    val persons: List<Color>,
    val outline: Color,
    val dark: Boolean,
) {
    fun person(index: Int): Color = persons[((index % persons.size) + persons.size) % persons.size]
}

val LightFamilyColors = FamilyColors(
    background = Color(0xFFF6F5F2),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF1D1C1A),
    textSecondary = Color(0xFF5C5A55),
    textTertiary = Color(0xFF6E6B65),
    accent = Color(0xFF0E6B66),
    onAccent = Color(0xFFFFFFFF),
    overdue = Color(0xFFB3261E),
    dueSoon = Color(0xFF8A5300),
    persons = listOf(Color(0xFF2F5DA8), Color(0xFFA84B0C), Color(0xFF7B3FA0), Color(0xFF2C7A4B)),
    outline = Color(0xFFD9D6CF),
    dark = false,
)

val DarkFamilyColors = FamilyColors(
    background = Color(0xFF151513),
    card = Color(0xFF201F1C),
    text = Color(0xFFECEAE4),
    textSecondary = Color(0xFFB0ADA6),
    textTertiary = Color(0xFFB0ADA6),
    accent = Color(0xFF5FC4BC),
    onAccent = Color(0xFF0B2E2C),
    overdue = Color(0xFFFF8A80),
    dueSoon = Color(0xFFF2B85B),
    persons = listOf(Color(0xFF8FB0EC), Color(0xFFF0A06A), Color(0xFFC9A0E6), Color(0xFF7FCB9B)),
    outline = Color(0xFF3A3934),
    dark = true,
)

val LocalFamilyColors = staticCompositionLocalOf { LightFamilyColors }

/** Material 3 motion tokens used by the design doc ("Bewegung"). */
object Motion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    const val SHORT_MS = 200
    const val SHEET_MS = 300
    const val FEEDBACK_MS = 250
    const val UNDO_VISIBLE_MS = 6_000L
}

@Composable
fun FamilyTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkFamilyColors else LightFamilyColors
    val scheme = if (c.dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.card, onSurface = c.text, surfaceVariant = c.card, onSurfaceVariant = c.textSecondary,
            error = c.overdue, outline = c.outline, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerLow = c.background,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.card, onSurface = c.text, surfaceVariant = c.card, onSurfaceVariant = c.textSecondary,
            error = c.overdue, outline = c.outline, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerLow = c.background,
        )
    }
    val shapes = Shapes(
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(28.dp),
    )
    CompositionLocalProvider(LocalFamilyColors provides c) {
        MaterialTheme(colorScheme = scheme, shapes = shapes) {
            // Long German words ("Steuererklärung") in narrow columns at 200 % font size get a
            // hyphen instead of a bare break in the middle of the word (P1-4).
            val hyphenated = LocalTextStyle.current.copy(hyphens = Hyphens.Auto)
            CompositionLocalProvider(LocalTextStyle provides hyphenated, content = content)
        }
    }
}
