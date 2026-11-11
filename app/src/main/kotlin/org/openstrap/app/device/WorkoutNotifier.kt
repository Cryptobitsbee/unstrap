package org.openstrap.app.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openstrap.app.MainActivity
import org.openstrap.app.R
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.Units

/**
 * Owns the workout notifications that outlive [StrapService]: Saved, and the Keep recording notice. Both follow the
 * session's state and live on the app scope, so a service that stops itself, or a fresh one, can't orphan, drop or
 * cancel one (audit S10). The ongoing workout notification stays the service's: it is the foreground notification, and
 * Android removes it with the service.
 */
class WorkoutNotifier(
    context: Context,
    private val state: StateFlow<WorkoutState>,
    /** The stored workout by its start. */
    private val workoutOnce: suspend (Long) -> AppWorkout?,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private var previous: WorkoutState? = null

    /** Follows the session from now on. */
    fun start() {
        scope.launch { state.collect { sync(it) } }
    }

    /**
     * Posts and cancels from [st]: Saved once a workout is saved, and gone as soon as the summary is closed or another
     * workout starts; the Keep recording notice gone as soon as nothing records.
     */
    suspend fun sync(st: WorkoutState) {
        val was = previous
        previous = st
        if (st !is WorkoutState.Recording) KeepRecordingNotice.end(context)
        when {
            st is WorkoutState.Ended && was is WorkoutState.Recording -> {
                val w = catching { workoutOnce(st.start) }.getOrNull() ?: return
                // The summary may already be closed by the time the read returns.
                if (state.value == st) notifySaved(w)
            }
            st !is WorkoutState.Ended -> cancelSaved(context)
        }
    }

    private fun notifySaved(w: AppWorkout) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SAVED_CHANNEL, context.getString(R.string.system_channel_saved), NotificationManager.IMPORTANCE_LOW),
        )
        nm.notify(SUMMARY_ID, savedNotification(w))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, StrapService.REQUEST_OPEN,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_WORKOUT, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * A quiet summary with View, and Undo when it ended on its own (the stop or car question) and the time after was
     * kept. Undo carries the end it was posted for, so it can tell a later trim by hand ([undoApplies]).
     */
    private fun savedNotification(w: AppWorkout): Notification {
        val end = w.end ?: System.currentTimeMillis() / 1000
        val duration = WorkoutRecorder.activeSeconds(w.copy(end = null, pausedAt = null), end)
        val facts = listOfNotNull(
            Units.workoutClock(duration),
            w.distanceMeters?.let { Units.distance(it, Units.Precision.Workout, w.distanceFromSteps) },
            w.averageHeartRate?.let { context.getString(R.string.system_saved_average_bpm, it) },
        ).joinToString(", ")
        val cut = cutSeconds(w)
        val time = TimeOfDay.format(end, java.time.ZoneId.systemDefault())
        val text = if (cut != null) context.getString(R.string.system_saved_cut, facts, time, (cut / 60).toInt()) else facts
        val b = NotificationCompat.Builder(context, SAVED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_unstrap)
            .setContentTitle(context.getString(R.string.system_saved_title, Workouts.sportName(w.sportType)))
            .setContentText(facts)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(NotificationCompat.Action(0, context.getString(R.string.system_saved_action_view), openApp()))
        if (cut != null) b.addAction(
            NotificationCompat.Action(
                0, context.getString(R.string.system_saved_action_undo),
                PendingIntent.getService(
                    context, REQUEST_UNDO,
                    Intent(context, StrapService::class.java).setAction(StrapService.ACTION_UNDO)
                        .putExtra(StrapService.EXTRA_START, w.start)
                        .putExtra(StrapService.EXTRA_END, end)
                        .putExtra(StrapService.EXTRA_RECORDED_END, w.recordedEnd ?: 0L),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ),
        )
        return b.build()
    }

    companion object {
        internal const val SUMMARY_ID = 4
        internal const val SAVED_CHANNEL = "workout_saved"
        private const val REQUEST_UNDO = 6
        private const val MIN_CUT_S = 60

        /** The time an automatic end left out, in seconds, when it is long enough to offer Undo for. */
        internal fun cutSeconds(w: AppWorkout): Long? {
            val end = w.end ?: return null
            val auto = w.endReason == AppWorkout.END_AUTO || w.endReason == AppWorkout.END_CAR
            return w.recordedEnd?.let { it - end }?.takeIf { auto && it >= MIN_CUT_S }
        }

        /**
         * Whether Undo, posted for a workout that ended at [end] with [recordedEnd] recorded, may still be applied: only
         * while it is as it was left, an automatic end. A trim by hand since is a user end at another time.
         */
        internal fun undoApplies(w: AppWorkout, end: Long, recordedEnd: Long): Boolean =
            w.end == end && w.recordedEnd == recordedEnd &&
                (w.endReason == AppWorkout.END_AUTO || w.endReason == AppWorkout.END_CAR)

        fun cancelSaved(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(SUMMARY_ID)
        }
    }
}
