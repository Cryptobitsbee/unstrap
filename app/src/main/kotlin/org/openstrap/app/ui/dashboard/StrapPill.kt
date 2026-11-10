package org.openstrap.app.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openstrap.app.R
import org.openstrap.app.ui.components.Motion
import org.openstrap.app.ui.components.rememberAnimationsOn
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.StrapDrawing
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.ControlSurface
import org.openstrap.app.ui.theme.MetricColors

/*
 * The strap pill in the header (spec 3.1). The pill is the strap's state, not a dot with words:
 *  - connected: green as far as the battery, fading out into the pill, the percent in the middle (amber when low)
 *  - charging: the same green, flowing, with a bolt before the number
 *  - not synced: orange all over, with the (i)
 *  - not connected, connecting or not paired: the strap as the Strap page draws it, dimmed, with its words ("2 h ago",
 *    "Not connected"), which tell the three apart. Not connected is mostly out of range or Bluetooth off and passes, so
 *    it isn't drawn as damage.
 * Green and amber are for the strap and the sync only, never a health value (spec 2.2).
 */

/** A fill's two colours: rich where it starts, softer where it runs out. */
private class Tint(val rich: Color, val soft: Color)

/** The orange of a sync that failed: the sync amber, deepened. */
private val OrangeDeep = Color(0xFFE5672A)

private fun tintOf(dot: StrapChip.Dot): Tint? = when (dot) {
    StrapChip.Dot.Fine, StrapChip.Dot.Charging -> Tint(MetricColors.good.copy(alpha = 0.62f), MetricColors.good.copy(alpha = 0.3f))
    StrapChip.Dot.Low -> Tint(MetricColors.warn.copy(alpha = 0.62f), MetricColors.warn.copy(alpha = 0.3f))
    StrapChip.Dot.Unsynced -> Tint(MetricColors.warn.copy(alpha = 0.55f), OrangeDeep.copy(alpha = 0.85f))
    StrapChip.Dot.Warn, StrapChip.Dot.Quiet -> null
}

/** How much of the pill is filled: the battery, or all of it when there's no reading or the colour is the point. */
private fun fillOf(chip: StrapChip): Float = when (chip.dot) {
    StrapChip.Dot.Fine, StrapChip.Dot.Low, StrapChip.Dot.Charging -> (chip.battery?.toFloat() ?: FULL) / FULL
    StrapChip.Dot.Unsynced -> 1f
    StrapChip.Dot.Warn, StrapChip.Dot.Quiet -> 0f
}

private const val FULL = 100f

@Composable
internal fun StrapPill(chip: StrapChip, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = tintOf(chip.dot)
    // A change of level moves the fill rather than cutting.
    val fill by animateFloatAsState(fillOf(chip), tween(FILL_MS, easing = Motion.easing), label = "strap fill")
    val flow = rememberChargeFlow(chip.dot == StrapChip.Dot.Charging)
    Row(
        // 48 dp tall to tap, the pill drawn 32 dp tall in the middle of it; the header row is 60 dp, so nothing moves.
        // [modifier] comes after, so the Strap page grows out of the pill as drawn, not the room round it.
        Modifier.minimumInteractiveComponentSize().then(modifier)
            .clip(CircleShape).background(ControlSurface)
            // Read where it is drawn, so the fill and its flow redraw without composing the pill again.
            .drawBehind { if (tint != null) drawFill(tint, fill, flow.value) }
            .border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape)
            .clickable(onClickLabel = stringResource(R.string.day_header_open_strap), onClick = onClick)
            .semantics { contentDescription = chip.description }
            .defaultMinSize(minWidth = MIN_WIDTH, minHeight = HEIGHT)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) { PillContent(chip) }
}

/**
 * The colour from the left edge to [fill] of the width, then fading out into the pill's own surface over [FADE], so it
 * ends in a soft edge like the lit cards, never a line. While charging ([flow] 0 to 1, -1 for still) the colour flows
 * along the pill, swelling and thinning, and the fade holds it in.
 */
private fun DrawScope.drawFill(tint: Tint, fill: Float, flow: Float) {
    val level = size.width * fill.coerceIn(0f, 1f)
    if (level <= 0f) return
    val full = fill >= 1f
    val end = if (full) size.width else minOf(size.width, level + FADE.toPx())
    val body = if (flow < 0f) {
        Brush.horizontalGradient(listOf(tint.rich, tint.soft), startX = 0f, endX = end)
    } else {
        // One swell is a period long and repeats, so sliding it along never shows a seam.
        val period = size.width * FLOW_PERIOD
        Brush.horizontalGradient(
            listOf(tint.soft, tint.rich, tint.soft), startX = period * (flow - 1f), endX = period * flow, tileMode = TileMode.Repeated,
        )
    }
    clipRect(0f, 0f, end, size.height) { drawRect(body) }
    if (!full) {
        val from = maxOf(0f, level - FADE.toPx() * FADE_LEAD)
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, ControlSurface), startX = from, endX = end),
            topLeft = Offset(from, 0f), size = Size(end - from, size.height),
        )
    }
}

/** How far along the pill the flow has come, 0 to 1, round and round while charging; -1 when it isn't flowing. */
@Composable
private fun rememberChargeFlow(charging: Boolean): State<Float> {
    val animate = rememberAnimationsOn()
    if (!charging || !animate) return NoFlow
    return rememberInfiniteTransition(label = "strap charge").animateFloat(
        0f, 1f, infiniteRepeatable(tween(FLOW_MS, easing = LinearEasing)), label = "strap charge flow",
    )
}

private val NoFlow: State<Float> = mutableFloatStateOf(-1f)

@Composable
private fun PillContent(chip: StrapChip) {
    when (chip.dot) {
        StrapChip.Dot.Fine, StrapChip.Dot.Low ->
            if (chip.text.isNotEmpty()) PillText(chip.text) else Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        StrapChip.Dot.Charging -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Bolt, null, Modifier.size(13.dp), tint = Color.White)
            Spacer(Modifier.width(2.dp))
            PillText(chip.battery?.toString() ?: chip.text)
        }
        StrapChip.Dot.Unsynced -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Info, null, Modifier.size(15.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
            PillText(chip.text)
        }
        StrapChip.Dot.Warn, StrapChip.Dot.Quiet -> StrapAndWords(chip.text)
    }
}

/** The Strap page's strap drawing, small (its light out and the drawing dimmed, as it is when the strap isn't there). */
@Composable
private fun StrapAndWords(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StrapDrawing(
            led = Color.White, lit = false, syncing = false, modifier = Modifier.size(width = MARK_WIDTH, height = MARK_HEIGHT),
            lineScale = MARK_LINES,
        )
        if (text.isNotEmpty()) {
            Spacer(Modifier.width(7.dp))
            PillText(text)
        }
    }
}

@Composable
private fun PillText(text: String) {
    Text(
        text, maxLines = 1, softWrap = false, color = Color.White,
        style = GlowType.secondary.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    )
}

private const val FILL_MS = 600
private const val FLOW_MS = 2600
private const val FLOW_PERIOD = 0.9f
private const val FADE_LEAD = 0.5f
private const val MARK_LINES = 4f
private val MIN_WIDTH = 56.dp
private val HEIGHT = 32.dp
private val FADE = 22.dp
private val MARK_WIDTH = 19.dp
private val MARK_HEIGHT = 24.dp
