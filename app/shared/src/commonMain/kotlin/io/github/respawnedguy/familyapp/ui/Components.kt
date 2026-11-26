package io.github.respawnedguy.familyapp.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.Source
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val DOT_INITIAL_SCALE = 0.45f
private const val DOT_QUESTION_SCALE = 0.5f

/** Person dot: fixed colour plus initial, so colour is never the only signal (design D6). */
@Composable
fun PersonDot(member: Member?, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val c = LocalFamilyColors.current
    if (member == null) {
        // Open responsibility: dashed circle with a question mark (design "Demnächst").
        Box(
            modifier.size(size).dashedBorder(c.textSecondary, size / 2),
            contentAlignment = Alignment.Center,
        ) {
            val questionSize = with(LocalDensity.current) { (size * DOT_QUESTION_SCALE).toSp() }
            Text(
                "?",
                color = c.textSecondary,
                fontSize = questionSize,
                lineHeight = questionSize,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    Box(
        modifier.size(size).clip(CircleShape).background(c.person(member.colorIndex))
            .clearAndSetSemantics { contentDescription = member.name },
        contentAlignment = Alignment.Center,
    ) {
        val initialSize = with(LocalDensity.current) { (size * DOT_INITIAL_SCALE).toSp() }
        Text(
            member.initial,
            color = if (c.dark) Color(0xFF151513) else Color.White,
            // The dot has a fixed size, so its initial must not grow with the system font size
            // (it would be cut off at 200 %, P1-4). The name is read out via contentDescription.
            fontSize = initialSize,
            lineHeight = initialSize,
            fontWeight = FontWeight.Bold,
        )
    }
}

fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val stroke = Stroke(width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    drawRoundRect(color = color, style = stroke, cornerRadius = CornerRadius(radius.toPx()))
}

@Composable
fun SectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    val c = LocalFamilyColors.current
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, Modifier.semantics { heading() },
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        if (count != null && count > 0) {
            Text(
                count.toString(),
                modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(c.accent).padding(horizontal = 8.dp, vertical = 1.dp),
                color = c.onAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    val c = LocalFamilyColors.current
    Text(
        text,
        modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
        color = c.textSecondary,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
    )
}

/**
 * Chip on a card. Missing values are dashed questions ("Wer kümmert sich?"), rule suggestions are
 * dashed with the value, decisions by a person are solid.
 */
@Composable
fun DecisionChip(
    label: String,
    missing: Boolean,
    suggested: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalFamilyColors.current
    val shape = RoundedCornerShape(16.dp)
    // 48 dp: minimum touch target (Material, WCAG 2.5.8 with margin; quality plan P1-6).
    val base = Modifier.heightIn(min = 48.dp).clip(shape)
    val bordered = if (missing || suggested) base.dashedBorder(if (missing) c.textSecondary else c.accent, 16.dp)
    else base.border(1.dp, c.outline, shape).background(c.background)
    Row(
        bordered.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(label, color = if (missing) c.textSecondary else c.text, fontSize = 14.sp)
    }
}

fun sourceLabel(source: Source): String = when (source) {
    Source.VOICE -> "Gesprochen"
    Source.PHOTO -> "Foto"
    Source.TEXT -> "Getippt"
    Source.SHARE -> "Geteilt"
}

/** "gerade eben", "vor 5 Min.", "vor 3 Std.", "vor 2 Tagen". */
fun ago(thenMs: Long, nowMs: Long): String {
    val min = max(0L, nowMs - thenMs) / 60_000
    return when {
        min < 1 -> "gerade eben"
        min < 60 -> "vor $min Min."
        min < 24 * 60 -> "vor ${min / 60} Std."
        min < 48 * 60 -> "gestern"
        else -> "vor ${min / (24 * 60)} Tagen"
    }
}

/**
 * Horizontal swipe with visible feedback only (no vibration, decided 20.11.2026): the background
 * turns into the action colour once the threshold is passed. Every swipe also has a button (WCAG 2.5.1).
 */
@Composable
fun SwipeToDecide(
    rightLabel: String,
    onRight: () -> Unit,
    leftLabel: String?,
    onLeft: (() -> Unit)?,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = LocalFamilyColors.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val threshold = width * 0.35f
    val spec = tween<Float>(Motion.SHORT_MS, easing = Motion.Standard)

    Box(modifier.fillMaxWidth().onSizeChanged { width = max(1, it.width) }) {
        val x = offset.value
        if (x != 0f) {
            val past = abs(x) > threshold
            val bg = when {
                past && x > 0 -> c.accent
                past -> c.textSecondary
                else -> c.outline
            }
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (x > 0) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(
                    if (x > 0) rightLabel else leftLabel.orEmpty(),
                    color = if (past) (if (x > 0) c.onAccent else c.background) else c.text,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val next = offset.value + delta
                            offset.snapTo(if (onLeft == null) max(0f, next) else next)
                        }
                    },
                    onDragStopped = {
                        val now = offset.value
                        when {
                            now > threshold -> {
                                if (!reducedMotion) offset.animateTo(width.toFloat(), spec)
                                onRight()
                                offset.snapTo(0f)
                            }
                            now < -threshold && onLeft != null -> {
                                if (!reducedMotion) offset.animateTo(-width.toFloat(), spec)
                                onLeft()
                                offset.snapTo(0f)
                            }
                            reducedMotion -> offset.snapTo(0f)
                            else -> offset.animateTo(0f, spec)
                        }
                    },
                ),
        ) { content() }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title, Modifier.semantics { heading() },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        content()
    }
}
