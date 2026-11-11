package org.openstrap.app.ui.dashboard

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.StressLevel

/**
 * Blood oxygen and stress on Today, All metrics, the Recovery tab and the details opened from them: the day's latest
 * reading and when it was taken, as heart rate leads with its latest. The strap reads blood oxygen day and night, so a
 * night's average is only on the Sleep and Recovery tabs ([SleepCopy.nightValue]). Stress is judged by the level of the
 * reading shown ([StressLevel]): its line says the level, and only a High reading gets a tag and a rim ([stressTag]).
 * The sentence under its chart still reads the day's average against your usual.
 */
internal object LatestReading {
    /** The newest of the day's blood oxygen readings, day or night; null before the day has one. */
    fun spo2(state: DayUiState): ChartPoint? = state.spo2.maxByOrNull { it.x }

    /** The newest of the day's stress readings, those during and after a workout already left out; null before the day has one. */
    fun stress(state: DayUiState): ChartPoint? = state.stress.maxByOrNull { it.x }

    /** The metrics whose day leads with their latest reading. */
    val METRICS: Set<org.openstrap.app.presentation.Metric> = setOf(
        org.openstrap.app.presentation.Metric.Spo2, org.openstrap.app.presentation.Metric.Stress,
    )

    /** [metric]'s latest reading of the day, null for a metric that doesn't lead with one or before it has one. */
    fun of(metric: org.openstrap.app.presentation.Metric, state: DayUiState): ChartPoint? = when (metric) {
        org.openstrap.app.presentation.Metric.Spo2 -> spo2(state)
        org.openstrap.app.presentation.Metric.Stress -> stress(state)
        else -> null
    }

    /** How old a reading may be, in seconds, before cards and rows say when it was taken. */
    const val STALE_AFTER_S = 60 * 60L

    /** Whether a reading taken at [at] is more than an hour before [now] (epoch seconds); every reading of a past day is. */
    fun isStale(at: Long, now: Long): Boolean = now - at > STALE_AFTER_S

    /** The day's sleep window, the main sleep's start to its end, null on a day without one. */
    fun sleepWindow(state: DayUiState): LongRange? = state.sleep?.let { it.start..it.end }

    /** Whether a reading at [at] falls inside the sleep [window]; without a window every reading counts as daytime. */
    fun isAsleep(at: Long, window: LongRange?): Boolean = window != null && at in window

    /**
     * The indices of a chart's [count] readings, one every [slot] seconds from the start of [day], that fall inside the
     * sleep [window]; null without a window, or when it doesn't touch the day.
     */
    fun shadeIndices(window: LongRange?, day: LongRange, slot: Long, count: Int): IntRange? {
        val touches = window != null && window.first <= day.last && window.last >= day.first
        if (window == null || count < 2 || !touches) return null
        val from = ((window.first - day.first).coerceAtLeast(0) / slot).toInt()
        val to = ((window.last - day.first) / slot).toInt().coerceAtMost(count - 1)
        return (from..to).takeIf { from < to }
    }

    /** Whether the day's latest blood oxygen reading was taken inside the sleep window; false without a reading. */
    fun spo2Asleep(state: DayUiState): Boolean = spo2(state)?.let { isAsleep(it.x, sleepWindow(state)) } == true

    /**
     * The line under a blood oxygen reading taken at [at]: "Daytime" or "While asleep", with the time ("Daytime, 12:50")
     * once the reading is stale at [now]. [time] writes an epoch second as a clock time.
     */
    fun spo2Line(at: Long, window: LongRange?, now: Long, strings: Strings = Strings.app, time: (Long) -> String): String {
        val asleep = isAsleep(at, window)
        return if (isStale(at, now)) {
            strings.format(if (asleep) R.string.glow_today_spo2_asleep_at else R.string.glow_today_spo2_daytime_at, time(at))
        } else {
            strings.get(if (asleep) R.string.glow_today_spo2_asleep else R.string.glow_today_spo2_daytime)
        }
    }

    /** The stress tag: "High" when the reading's [level] is High, null for the other levels, which have no tag and no rim. */
    fun stressTag(level: StressLevel, strings: Strings = Strings.app): NewsTag? =
        if (level == StressLevel.High) NewsTag(level.word(strings), goal = false) else null

    /**
     * The line under a stress reading of [value] taken at [at]: its level word ("Normal"), with the time ("Normal,
     * 12:50") once the reading is stale at [now]. Where the High tag shows ([tagged]) the tag says the level already, so
     * the line is null when fresh and "Latest at 12:50" when stale.
     */
    fun stressLine(
        at: Long, value: Float, now: Long, tagged: Boolean, strings: Strings = Strings.app, time: (Long) -> String,
    ): String? {
        val level = StressLevel.of(value)
        val stale = isStale(at, now)
        return when {
            tagged && level == StressLevel.High -> if (stale) strings.format(R.string.glow_today_latest_at, time(at)) else null
            stale -> strings.format(R.string.glow_today_stress_level_at, level.word(strings), time(at))
            else -> level.word(strings)
        }
    }
}
