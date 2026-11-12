package org.openstrap.app.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore

/**
 * Changes to phone workouts already saved: discard, trim and its undo, and a stretch saved from history. Each runs
 * under [WorkoutSession]'s [lock], so it can't interleave with the end of a recording. Their days' calories rows follow
 * every change ([refreshDays]), and [onSaved] tells auto sync.
 */
// Built once, by WorkoutSession, from what that was built with.
@Suppress("LongParameterList")
internal class SavedWorkoutEditor(
    private val dao: HealthDao,
    private val settings: SettingsStore,
    private val lock: Mutex,
    private val onSaved: (start: Long) -> Unit,
    /** The workout [start] was deleted: the session closes its summary if it is the one showing. Runs under [lock]. */
    private val onDiscarded: (start: Long) -> Unit,
    private val log: (String) -> Unit,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    /** The route and step totals still in memory for the workout [start] (the last one recorded), else null. */
    private val track: (start: Long) -> WorkoutDistance.Track? = { null },
    /** The workout [start] was deleted, with all it was recorded over, [from] to [to]: auto sync takes it out of Health Connect. */
    private val onRemoved: (start: Long, from: Long, to: Long) -> Unit = { _, _, _ -> },
) {
    /**
     * Deletes a phone workout, and closes its summary if it is the one showing. The strap's own records of it
     * ([org.openstrap.app.data.Workouts.strapTwins]) go too, as they show as this one: left, they would come back as
     * a workout of their own.
     */
    fun discard(start: Long) {
        scope.launch {
            lock.withLock {
                // One transaction: not deleted, nothing is, its summary stays, and Discard can be tried again.
                val w = catching { dao.discardAppWorkout(start, clock()) }
                    .onFailure { log("workout not discarded: ${it.message}") }.getOrElse { return@withLock }
                if (w?.end != null) {
                    refreshDays(w.start, w.recordedEnd ?: w.end)
                    onRemoved(w.start, w.start, w.recordedEnd ?: w.end)
                }
                onDiscarded(start)
            }
        }
    }

    /**
     * Saves a stretch nobody started as a workout ("Save as workout", or added after the fact), built from the
     * strap's history. A stretch over a workout already saved is refused.
     */
    fun saveFromHistory(from: Long, to: Long, sportType: Int) {
        scope.launch {
            lock.withLock {
                if (to - from < MIN_LENGTH_S || dao.appWorkoutsOnce(from, to).isNotEmpty()) return@withLock
                val w = AppWorkout(
                    start = from, end = to, sportType = sportType, source = AppWorkout.SOURCE_SUGGESTED, endReason = AppWorkout.END_USER,
                )
                dao.upsertAppWorkout(w)
                WorkoutGapFill(dao) { settings.profile.first() }.fill(w)
                refreshDays(from, to)
                onSaved(w.start)
            }
        }
    }

    /** "Not a workout": remembered for that time range, so the same stretch isn't offered again. */
    fun dismissSuggestion(from: Long, to: Long) {
        scope.launch { dao.insertDismissedWorkouts(listOf(org.openstrap.app.data.db.DismissedWorkout(from, to))) }
    }

    /** Puts back what an early end or a trim left out: the workout as recorded. */
    fun undoTrim(start: Long) = editSaved(start) { it.copy(end = it.recordedEnd ?: it.end, recordedEnd = null, shownStart = null) }

    /** Shows a saved workout from [from] to [to], both inside what was recorded; nothing is deleted. */
    fun trim(start: Long, from: Long, to: Long) = editSaved(start) { w ->
        val recorded = w.recordedEnd ?: w.end ?: return@editSaved w
        val end = to.coerceIn(w.start + MIN_LENGTH_S, recorded)
        // A trim by hand replaces whatever the app proposed, so the summary says "Trimmed", not "when you'd stopped".
        w.copy(
            shownStart = from.coerceIn(w.start, end - MIN_LENGTH_S).takeIf { it > w.start }, end = end,
            recordedEnd = recorded.takeIf { it > end },
            endReason = AppWorkout.END_USER,
        )
    }

    private fun editSaved(start: Long, change: (AppWorkout) -> AppWorkout) {
        scope.launch {
            lock.withLock {
                val w = catching { dao.appWorkoutOnce(start) }.getOrNull()?.takeIf { it.end != null } ?: return@withLock
                val changed = change(w).let { c ->
                    if (c.from == w.from && c.end == w.end) c else trackOf(w).let { withStretches(withDistance(w, c, it), it) }
                }
                if (changed == w) return@withLock
                catching { dao.upsertAppWorkout(withEffect(changed, dao.appWorkoutHeartRateOnce(start))) }
                    .onFailure { log("workout edit not saved: ${it.message}") }
                    .getOrElse { return@withLock }
                // Its kcal moved, so its days' calories rows follow: over all it was recorded, which holds both ranges.
                refreshDays(w.start, w.recordedEnd ?: w.end ?: start)
                onSaved(start)
            }
        }
    }

    /** What [old]'s distance and stretches are worked out from again: the route and step totals in memory, or a kept route. */
    private suspend fun trackOf(old: AppWorkout): WorkoutDistance.Track? = track(old.start) ?: run {
        if (!old.keepRoute) return@run null
        catching { dao.routeOnce(old.start).map { it.point } }.getOrNull()
            ?.let { WorkoutDistance.Track(it, emptyList(), null, routeSince = old.start, stepsSince = old.start) }
    }

    /**
     * [new], a new range of [old], with the distance over it ([WorkoutDistance]): from [track], and without it
     * [old]'s by the share of moving time kept.
     */
    private fun withDistance(old: AppWorkout, new: AppWorkout, track: WorkoutDistance.Track?): AppWorkout {
        val shown = track?.let { WorkoutDistance.shown(new, clock(), it) }
        return if (shown != null) new.copy(distanceMeters = shown.first, distanceFromSteps = shown.second)
        else new.copy(distanceMeters = WorkoutDistance.scaledTo(old, new))
    }

    /**
     * [w] with its fastest stretches worked out again over the range it shows, as when it was saved: a trim can leave a
     * stretch that started outside it, and an undo needs ones never worked out. Left as they are without the whole route.
     */
    private fun withStretches(w: AppWorkout, track: WorkoutDistance.Track?): AppWorkout {
        val end = w.end ?: return w
        if (track == null || track.routeSince > w.start || track.route.isEmpty()) return w
        val stretches = org.openstrap.app.data.Rewards.stretches(track.route.filter { it.t in w.from..end }, w.pauseRanges)
            .let(org.openstrap.app.data.Rewards.Stretch::format)
        return w.copy(stretches = stretches)
    }

    /** Writes the daily calories rows from [from] to [to] again ([org.openstrap.app.data.DailyCaloriesHistory.updateDays]). */
    suspend fun refreshDays(from: Long, to: Long) {
        catching { org.openstrap.app.data.DailyCaloriesHistory(dao).updateDays(from, to, settings.profile.first()) }
            .onFailure { log("daily energy not refreshed: ${it.message}") }
    }

    /**
     * [w] with its average and peak, then its effect ([org.openstrap.app.data.WorkoutEffectEngine]); [route] is the one in
     * memory at the end. A failure there never costs the workout: it is saved with its stats alone.
     */
    suspend fun withEffect(w: AppWorkout, hr: List<AppWorkoutHeartRate>, route: List<Route.Point>? = null): AppWorkout {
        val stats = WorkoutRecorder.withStats(w, hr)
        return catching { org.openstrap.app.data.WorkoutEffectEngine(dao).withEffect(stats, hr, settings.profile.first(), route) }
            .getOrDefault(stats)
    }

    companion object {
        /** A saved workout, or what a trim leaves of one, is at least a minute long. */
        const val MIN_LENGTH_S = 60L
    }
}
