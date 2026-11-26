package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

// Large system font sizes (quality plan P1-4), checked on the emulator by LargeFontScreensTest.

private const val LARGE_FONT_SCALE = 1.3f

/** True when the system font size is clearly enlarged; layouts then stack instead of sitting side by side. */
@Composable
fun largeFont(): Boolean = LocalDensity.current.fontScale > LARGE_FONT_SCALE

/**
 * One-line label in a slot of fixed width (bottom bars, top bar). It grows with the system font
 * size like any text, but shrinks to fit the slot instead of splitting the word in the middle
 * (quality plan P1-4). [minPhysical] is the smallest size, independent of the font size setting.
 */
@Composable
fun FitLabel(text: String, style: TextStyle, modifier: Modifier = Modifier, minPhysical: Float = 9f) {
    val fontScale = LocalDensity.current.fontScale
    val merged = LocalTextStyle.current.merge(TextStyle(textAlign = TextAlign.Center)).merge(style)
    BasicText(
        text,
        modifier,
        style = merged,
        softWrap = false,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = (minPhysical / fontScale).sp, maxFontSize = merged.fontSize),
    )
}
