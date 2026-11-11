package org.openstrap.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * How long a switch to another day keeps the last day up, dimmed, before the new day's placeholder shows. Long enough
 * that a day read from the phone's own database lands without a flash, short enough that another day's numbers never
 * stand for the new one.
 */
const val DAY_HOLD_MS = 300L

/** How faint a held day is drawn, so it reads as on its way out. */
const val DAY_HELD_ALPHA = 0.5f

/** What a day's screen shows: [value], and whether it is another day's, kept for a moment ([rememberDayHold]). */
class DayHold<T : Any>(val value: T?, val pending: Boolean)

/**
 * [value] of [day] once there is one. While there isn't:
 * - the same day keeps the value it last had, as a refresh of the day shown;
 * - another day keeps the last day's for at most [DAY_HOLD_MS], marked [DayHold.pending] so it is drawn dimmed, then
 *   none, so the screens show their placeholders;
 * - a day that [failed] keeps nothing: its value is never coming.
 */
@Composable
fun <T : Any> rememberDayHold(value: T?, day: LocalDate, failed: Boolean = false): DayHold<T> {
    val last = remember { LastOfDay<T>() }
    var gaveUp by remember(day) { mutableStateOf(false) }
    LaunchedEffect(day, value == null) {
        gaveUp = false
        if (value == null) {
            delay(DAY_HOLD_MS)
            gaveUp = true
        }
    }
    SideEffect {
        if (value != null) {
            last.day = day
            last.value = value
        }
    }
    val kept = last.value
    return when {
        value != null -> DayHold(value, pending = false)
        failed || kept == null -> DayHold(null, pending = false)
        last.day == day -> DayHold(kept, pending = false)
        gaveUp -> DayHold(null, pending = false)
        else -> DayHold(kept, pending = true)
    }
}

/** Draws a held day ([DayHold.pending]) dimmed. */
fun Modifier.dayHeld(pending: Boolean): Modifier = if (pending) alpha(DAY_HELD_ALPHA) else this

private class LastOfDay<T : Any> {
    var day: LocalDate? = null
    var value: T? = null
}
