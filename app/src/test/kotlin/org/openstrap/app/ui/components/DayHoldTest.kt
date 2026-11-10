package org.openstrap.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.ComposeRules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** A day switch keeps the last day up, dimmed, for a moment only, then the new day's placeholder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayHoldTest {
    @get:Rule val rules = ComposeRules()

    private val monday = LocalDate.of(2026, 11, 9)
    private val tuesday = monday.plusDays(1)

    private var value by mutableStateOf<String?>("monday")
    private var day by mutableStateOf(monday)
    private var failed by mutableStateOf(false)
    private lateinit var hold: DayHold<String>

    private fun show() {
        rules.compose.mainClock.autoAdvance = false
        rules.compose.setContent { hold = rememberDayHold(value, day, failed) }
        rules.compose.mainClock.advanceTimeByFrame()
    }

    private fun switchTo(newDay: LocalDate, newValue: String?) {
        day = newDay
        value = newValue
        frame()
    }

    /** The writes above reach the composition, and one frame runs. */
    private fun frame() {
        Snapshot.sendApplyNotifications()
        rules.compose.mainClock.advanceTimeByFrame()
    }

    @Test fun `another day keeps the last one dimmed for a moment, then shows the placeholder`() {
        show()
        switchTo(tuesday, null)
        assertEquals("monday", hold.value)
        assertTrue(hold.pending)
        rules.compose.mainClock.advanceTimeBy(DAY_HOLD_MS + 100)
        assertNull(hold.value)
        assertFalse(hold.pending)
    }

    @Test fun `the new day replaces the held one as soon as it comes`() {
        show()
        switchTo(tuesday, null)
        value = "tuesday"
        frame()
        assertEquals("tuesday", hold.value)
        assertFalse(hold.pending)
    }

    @Test fun `a refresh of the same day keeps its value, undimmed, however long it takes`() {
        show()
        value = null
        frame()
        rules.compose.mainClock.advanceTimeBy(DAY_HOLD_MS * 10)
        assertEquals("monday", hold.value)
        assertFalse(hold.pending)
    }

    @Test fun `a day that failed holds nothing`() {
        show()
        failed = true
        switchTo(tuesday, null)
        assertNull(hold.value)
        assertFalse(hold.pending)
    }
}
