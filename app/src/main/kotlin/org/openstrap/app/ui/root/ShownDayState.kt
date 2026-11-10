// Keyed effects run once per key after the frame commits, as they did in AppRoot. A SideEffect would run on every
// recomposition.
@file:Suppress("UnnecessaryLaunchedEffect")

package org.openstrap.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.ui.dashboard.DataPreview
import org.openstrap.app.ui.dashboard.DayScreenState
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.dashboard.FirstScore
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.StrapPreview
import org.openstrap.app.ui.dashboard.earliestScore
import org.openstrap.app.ui.dashboard.firstScore
import org.openstrap.app.ui.dashboard.previewed
import org.openstrap.app.ui.dashboard.wear

private const val S_PER_MIN = 60

/** The day, its Recovery and a failed Recovery as the screens are shown them, the debug previews applied. */
class PreviewedDay(
    val screen: DayScreenState,
    val day: DayUiState,
    val recovery: RecoveryResult?,
    val recoveryFailure: RecoveryFailure?,
)

/**
 * The day shown with the debug previews applied: a strap [preview] of a failed Recovery shows the day as failed, with
 * yesterday's score from the real result, and a [data] preview shows first nights or no sleep in the day and its
 * Recovery. Nothing else changes.
 */
@Composable
internal fun rememberPreviewedDay(dayVm: DayViewModel, preview: StrapPreview?, data: DataPreview?): PreviewedDay {
    val screen by dayVm.screen.collectAsStateWithLifecycle()
    val realRecovery = screen.recovery
    val realRecoveryFailure = screen.recoveryFailure
    val realDay = screen.day
    val day = remember(realDay, data) { realDay.previewed(data) }
    val previewFailure = if (preview == StrapPreview.RecoveryFail) {
        realRecovery?.let { r -> RecoveryFailure(r.date, r.readinessTrend.firstOrNull { it.first == r.date.minusDays(1) }?.second) }
    } else {
        null
    }
    val recovery = if (previewFailure != null) null else realRecovery?.previewed(data)
    return PreviewedDay(screen, day, recovery, previewFailure ?: realRecoveryFailure)
}

/**
 * Everything a tab and detail is shown of the day: its wear for the Partial day tag (spec 4.3), the strap's score and
 * check-in, and the first real score (E4), marked once and never written while a preview shows.
 */
@Composable
internal fun rememberShownDay(
    shown: PreviewedDay,
    rootVm: AppRootViewModel,
    clockDate: LocalDate,
    now: Long,
    preview: StrapPreview?,
    data: DataPreview?,
): ShownDay {
    val day = shown.day
    val recovery = shown.recovery
    LaunchedEffect(day.date) { rootVm.dayShown(day.date) }
    val dayExtras by rootVm.dayExtras.collectAsStateWithLifecycle()
    // The check-in follows the day by its own flow, so it only counts once it's this day's.
    val checkIn = dayExtras.takeIf { it.date == day.date }?.checkIn
    val dayIsToday = day.date == clockDate
    val wear = day.wear(recovery, dayIsToday)
        .previewed(data, (((if (dayIsToday) now else day.dayEnd + 1) - day.dayStart) / S_PER_MIN).toInt())
    val firstScoreDay by rootVm.firstScoreDay.collectAsStateWithLifecycle()
    val dayRecovery = recovery
    val first = if (firstScoreDay == FirstScoreDay.Loading) {
        FirstScore(false)
    } else {
        firstScore(
            dayRecovery?.readiness?.score, day.date, dayIsToday, dayRecovery?.readiness?.phase == Recovery.Phase.Learning,
            dayRecovery?.let { earliestScore(it.readinessTrend, day.date) }, (firstScoreDay as? FirstScoreDay.Day)?.date,
        )
    }
    LaunchedEffect(first.remember, preview, data) {
        if (preview == null && data == null) first.remember?.let(rootVm::markFirstScore)
    }
    return ShownDay(
        day, recovery, shown.recoveryFailure, shown.screen.healthspan, checkIn, wear,
        firstScore = first.show || data == DataPreview.FirstScoreShown,
        loading = !shown.screen.loaded, usualSteps = shown.screen.usualSteps, activity = shown.screen.activity,
    )
}
