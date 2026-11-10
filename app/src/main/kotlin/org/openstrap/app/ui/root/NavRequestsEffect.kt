package org.openstrap.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import org.openstrap.app.NavRequest
import org.openstrap.app.NavigationRequests
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.dashboard.Detail
import kotlinx.coroutines.flow.StateFlow

/**
 * What a notification, a widget, the tile or a shortcut asked for, each once and in the order asked. A request sent
 * before the first frame waits for it.
 */
@Composable
internal fun NavRequestsEffect(
    requests: NavigationRequests, nav: AppNavState, hooks: NavRequestHooks, session: StateFlow<WorkoutState>,
) {
    val current by rememberUpdatedState(hooks)
    // Whatever started the workout, Ready and Choose go with it; re-run if one is opened while it is in progress.
    val state by session.collectAsState()
    val inProgress = state is WorkoutState.Recording || state is WorkoutState.Ended
    val overlay = nav.workout
    LaunchedEffect(inProgress, overlay) { if (inProgress) nav.sessionInProgress() }
    LaunchedEffect(requests, nav) {
        requests.requests.collect { r ->
            val h = current
            val firmware = Page.Firmware in nav.pages
            val before = nav.pages
            nav.apply(r, h.recording(), h.firmwareInstalling())
            if (firmware && Page.Firmware !in nav.pages) h.firmwareClosed()
            when (r) {
                // A health alert or body-signal notification: its metric's screen, for the alert's day.
                is NavRequest.OpenDetail -> if (Detail.fromRoute(r.route) != null) r.day?.let(h.selectDay)
                NavRequest.OpenToday -> h.today()
                NavRequest.OpenStrap -> if (nav.pages != before && nav.pages == listOf(Page.Strap)) h.strapOpened()
                NavRequest.ShowWorkout -> h.showWorkout()
                else -> Unit
            }
        }
    }
}
