package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.openstrap.app.R

/**
 * Over the route map, which line it draws: Roads, along the streets the route took
 * ([org.openstrap.app.maps.RouteSnapper]), or GPS, as it was recorded. Roads is dimmed while the route is matched to them
 * ([working]).
 */
@Composable
internal fun RouteLineChoice(roads: Boolean, working: Boolean, onChoose: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val matching = stringResource(R.string.glow_summary_route_matching)
    Row(
        modifier.height(36.dp).clip(CircleShape).background(OverRouteMap).padding(3.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteLineOption(stringResource(R.string.glow_summary_route_roads), roads, dim = working, note = matching.takeIf { working }) {
            onChoose(true)
        }
        RouteLineOption(stringResource(R.string.glow_summary_route_gps), !roads) { onChoose(false) }
    }
}

/** One of [RouteLineChoice]'s two: white when [selected]. [note] tells a screen reader what it's doing. */
@Composable
private fun RouteLineOption(label: String, selected: Boolean, dim: Boolean = false, note: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().clip(CircleShape).background(if (selected) Color.White else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .then(if (note != null) Modifier.semantics { stateDescription = note } else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (selected) Color.Black else Color.White
        Text(label, color = ink.copy(alpha = if (dim) DIM_ALPHA else 1f), style = GlowType.secondary, maxLines = 1)
    }
}

/** Drawn over the map: near black, a little see-through, as the live map's buttons are. */
private val OverRouteMap = Color(0xD9141416)

/** Roads while the route is matched to them. */
private const val DIM_ALPHA = 0.45f
