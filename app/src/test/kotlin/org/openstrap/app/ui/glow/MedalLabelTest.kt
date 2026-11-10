package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.Rewards
import org.openstrap.app.testing.ComposeRules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A medal's number or distance ("1000", "14/14", "HM") keeps clear of its inner ring at every size it's drawn, the
 * small cells and the sheet's large medal alike, rather than running into the edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MedalLabelTest {
    @get:Rule val rules = ComposeRules()

    /** The medals with the longest labels in each shape, each label once. */
    private val looks: List<Pair<String, MedalLook>> = listOf(
        "1000" to badgeLook(badge("km_1000"), "1000"),
        "500" to badgeLook(badge("workouts_500"), "500"),
        "1M" to badgeLook(badge("steps_1m"), "1M"),
        "10K" to badgeLook(badge("first_10k"), "10K"),
        "52" to badgeLook(badge("goal_52"), "52"),
        "14/14" to badgeLook(badge("goal_first"), "14/14"),
        "HM" to bestLook(Rewards.Kind.fastest(HALF_MARATHON)),
    )

    private fun badge(id: String) = Rewards.BADGES.first { it.id == id }

    @Test fun `labels keep inside the ring on a cell's medal`() = assertInside(64.dp)

    @Test fun `labels keep inside the ring on a sheet's medal`() = assertInside(156.dp)

    private fun assertInside(diameter: Dp) {
        rules.compose.setContent {
            Column {
                looks.forEachIndexed { i, (_, look) -> Box(Modifier.testTag("medal$i")) { Medal(look, diameter) } }
            }
        }
        val out = looks.mapIndexedNotNull { i, (label, look) ->
            val medal = unclipped(rules.compose.onNodeWithTag("medal$i").fetchSemanticsNode())
            val text = unclipped(rules.compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode())
            val cx = medal.center.x
            val cy = medal.center.y
            // The ring's radius on this medal, in px: the frame is 100 units across.
            val ring = innerRing(look.shape) * medal.width / FRAME_UNITS
            val corners = listOf(text.topLeft, text.topRight, text.bottomLeft, text.bottomRight)
            val far = corners.maxOf { hypot(it.x - cx, it.y - cy) }
            "$label on ${look.shape} at $diameter: reaches $far px, the ring is at $ring".takeIf { far > ring }
        }
        assertTrue(out.joinToString("\n"), out.isEmpty())
    }

    /** A node's bounds where it's laid out, even off the screen's edge, as its size scaled by any layer. */
    private fun unclipped(node: SemanticsNode): Rect {
        val box = node.layoutInfo.coordinates
        return Rect(box.localToRoot(Offset.Zero), box.localToRoot(Offset(box.size.width.toFloat(), box.size.height.toFloat())))
    }

    /**
     * How far the inner ring is from the middle, in frame units, read safely: the ring at 0.87 of the face, and for the
     * hexagon (its flats 40.7 from the middle) and the soft square the circle that fits inside them, so a pass means
     * clear of every edge.
     */
    private fun innerRing(shape: MedalShape): Float = INNER * when (shape) {
        MedalShape.Round -> 46f
        MedalShape.Hex -> 40.7f
        MedalShape.Laurel -> 34f
        MedalShape.Soft -> 45f
        MedalShape.Rosette -> 36f
    }

    private companion object {
        const val HALF_MARATHON = 21097
        const val FRAME_UNITS = 100f
        const val INNER = 0.87f
    }
}
