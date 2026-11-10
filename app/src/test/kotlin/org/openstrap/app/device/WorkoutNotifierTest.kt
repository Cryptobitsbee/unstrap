package org.openstrap.app.device

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.openstrap.app.data.db.AppWorkout

/** Saved and the Keep recording notice follow the session's state, whatever the service is doing (audit S10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val start = 1_000_000L
    private val state = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    private val stored = AppWorkout(
        start = start, end = start + 1_200, sportType = 1, endReason = AppWorkout.END_AUTO, recordedEnd = start + 1_800,
    )

    private fun notifier(scope: TestScope) = WorkoutNotifier(context, state, { stored }, scope)

    private fun recording() = WorkoutState.Recording(stored.copy(end = null, recordedEnd = null), maxHeartRate = 190)

    private fun saved() = manager.getNotification(WorkoutNotifier.SUMMARY_ID)

    @Test
    fun savedPostsWhenARecordingEndsAndGoesWhenTheSummaryCloses() = runTest {
        val n = notifier(this)
        n.sync(recording())
        assertNull(saved())
        state.value = WorkoutState.Ended(start)
        n.sync(state.value)
        assertNotNull(saved())
        state.value = WorkoutState.Idle
        n.sync(WorkoutState.Idle)
        assertNull(saved())
    }

    @Test
    fun aFreshNotifierDoesNotPostSavedForAnEndedStateItNeverSawRecording() = runTest {
        state.value = WorkoutState.Ended(start)
        notifier(this).sync(state.value)
        assertNull(saved())
    }

    @Test
    fun theKeepRecordingNoticeGoesWhenTheSessionStopsRecording() = runTest {
        KeepRecordingNotice.serviceRefused(context, sportType = 1)
        val n = notifier(this)
        n.sync(recording())
        assertNotNull(manager.getNotification(KeepRecordingNotice.NOTIFICATION_ID))
        n.sync(WorkoutState.Idle)
        assertNull(manager.getNotification(KeepRecordingNotice.NOTIFICATION_ID))
    }

    @Test
    fun undoOnlyAppliesWhileTheWorkoutIsAsTheNotificationLeftIt() {
        assertTrue(WorkoutNotifier.undoApplies(stored, stored.end!!, stored.recordedEnd!!))
        // A trim by hand: a user end at another time.
        val trimmed = stored.copy(end = start + 900, endReason = AppWorkout.END_USER)
        assertFalse(WorkoutNotifier.undoApplies(trimmed, stored.end!!, stored.recordedEnd!!))
        // Trimmed by hand to the same end, still a user end.
        assertFalse(WorkoutNotifier.undoApplies(stored.copy(endReason = AppWorkout.END_USER), stored.end!!, stored.recordedEnd!!))
        assertEquals(600L, WorkoutNotifier.cutSeconds(stored))
    }
}
