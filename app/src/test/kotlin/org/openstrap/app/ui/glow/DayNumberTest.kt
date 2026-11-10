package org.openstrap.app.ui.glow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.ComposeRules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A detail's figure moves to another day's without jumping: a count runs one way from the figure shown to the new one
 * (2,500 to 4,500 never reads 4,500, then 2,500, then climbs back), and a change that isn't a day's lands at once, even
 * the first one on a day whose figure came in the same as the last day's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayNumberTest {
    @get:Rule val rules = ComposeRules()

    private var value by mutableStateOf("2,500")
    private var day by mutableStateOf(DAY)

    /** The figures on screen this frame, as numbers. */
    private fun shown(): List<Float> = rules.compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }
        .mapNotNull { it.replace(",", "").toFloatOrNull() }

    /** The largest figure on screen in each frame over [frames] frames from now. */
    private fun frames(frames: Int): List<Float> = List(frames) {
        rules.compose.mainClock.advanceTimeByFrame()
        shown().maxOrNull() ?: 0f
    }

    private fun start() {
        rules.compose.setContent { DayNumber(value, day) }
        rules.compose.waitForIdle()
        rules.compose.mainClock.autoAdvance = false
    }

    @Test fun `another day's figure counts one way to it, never showing it first`() {
        start()
        value = "4,500"
        day = DAY.plusDays(1)
        val seen = frames(FRAMES)
        assertTrue("the first frames read ${seen.take(3)}", seen.first() < END)
        seen.zipWithNext().forEach { (a, b) -> assertTrue("went back from $a to $b in $seen", b >= a) }
        assertEquals(END, seen.last(), 0f)
    }

    @Test fun `a change after the next day came in the same as the last lands at once`() {
        start()
        // The next day's figure is the same as the last day's, so nothing moves; then a live reading changes it.
        day = DAY.plusDays(1)
        frames(LATE_FRAMES)
        value = "4,500"
        val seen = frames(2)
        assertTrue("the live change read $seen", seen.all { it == START || it == END })
        assertEquals(END, seen.last(), 0f)
    }

    @Test fun `a change on the same day lands at once`() {
        start()
        value = "4,500"
        // The frame the change is applied in, then the new figure, with nothing between.
        val seen = frames(2)
        assertTrue("same day read $seen", seen.all { it == START || it == END })
        assertEquals(END, seen.last(), 0f)
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 11, 2)
        const val START = 2500f
        const val END = 4500f
        /** Enough frames for the count to finish: 600 ms at 16 ms a frame, and some. */
        const val FRAMES = 50
        const val LATE_FRAMES = 5
    }
}
