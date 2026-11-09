package org.openstrap.app.device

import org.openstrap.app.data.Days
import org.openstrap.protocol.history.ActivitySample
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.HistoryRecord
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Zepp 10.10.5's history cursors, type by type (codemap sync-zepp-10.10.5 §5.2, §5.3). Epoch seconds: Zepp keeps
 * milliseconds, but a START carries whole seconds, so its "+ 1 ms" asks for the same second again.
 */
internal object ZeppCursors {
    /** Zepp's floor for any cursor, 2014-01-01 00:00 at UTC+8 (`TimestampCorrectUseCase`, `c5a0`). */
    const val FLOOR = 1_388_505_600L
    private const val HOUR = 3_600L
    private const val DAY = 24 * HOUR
    private const val MINUTE = 60L

    /** The newest sleep result START goes back to (`ave.d`). */
    private const val SLEEP_RESULT_DAYS = 45L

    /**
     * Where a type with no cursor starts (`ty80.f`, no cloud list): now − 2 days, with `Calendar.HOUR` (the 12-hour
     * field), minute and second set to 0. So 15:30 gives 12:00 that day and 09:30 gives 00:00.
     */
    fun default(now: Long, zone: ZoneId): Long =
        twelveHourFloor(ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(now), zone).minusDays(DEFAULT_DAYS))

    private const val DEFAULT_DAYS = 2L
    private const val NOON = 12

    /** `Calendar.set(HOUR, 0)` then minute and second 0: the start of the half day. */
    private fun twelveHourFloor(t: ZonedDateTime): Long =
        t.withHour(if (t.hour >= NOON) NOON else 0).withMinute(0).withSecond(0).withNano(0).toEpochSecond()

    /** A stored cursor as read (`c5a0`): below [FLOOR] is raised, over now + 1 h becomes max(now − 48 h, bind) capped at now. */
    fun onRead(value: Long, now: Long, bindTime: Long? = null): Long = when {
        value < FLOOR -> FLOOR
        value > now + HOUR -> maxOf(now - 2 * DAY, bindTime ?: Long.MIN_VALUE).coerceAtMost(now)
        else -> value
    }

    /** The cursor stored under [key] as Zepp reads it: corrected, or the default when there is none. */
    suspend fun read(cursors: SyncCursors, key: String, now: Long, zone: ZoneId): Long =
        cursors.get(key)?.let { onRead(it, now) } ?: default(now, zone)

    /** A cursor as written (`c5a0`): below [FLOOR] is raised, over now + 1 h becomes now. */
    fun onWrite(value: Long, now: Long): Long = when {
        value < FLOOR -> FLOOR
        value > now + HOUR -> now
        else -> value
    }

    /**
     * The first START's time for [type], from the corrected cursors ([read] gives a type's stored cursor or the
     * default). Null for a type Zepp skips.
     */
    fun start(type: HistoryDataType, now: Long, zone: ZoneId, read: (HistoryDataType) -> Long): Long = when (type) {
        // Zepp's sleep result job: the stored day from its local midnight, never before now − 45 days.
        HistoryDataType.SLEEP_RESULT -> maxOf(Days.start(read(type), zone), now - SLEEP_RESULT_DAYS * DAY)
        HistoryDataType.RESTING_HEART_RATE -> Days.start(read(type), zone)
        // Max HR follows the activity cursor, from that day's midnight (`c1t`).
        HistoryDataType.DAILY_MAX_EXERCISE_HR -> Days.start(read(HistoryDataType.ACTIVITY), zone)
        // Both second-HR types start from what 76 holds (Zepp: the newest stored second-HR record + 1 ms).
        HistoryDataType.SEC_HR, HistoryDataType.SEC_HR_V2 -> read(HistoryDataType.SEC_HR)
        // 74 and the sleep plan (66, stored plan time − 1000 ms) start a second back.
        HistoryDataType.OSA_RESULT, HistoryDataType.SLEEP_PLAN -> read(type) - 1
        HistoryDataType.SPO2_SINGLE, HistoryDataType.SPO2_OSA, HistoryDataType.SPO2_OSA_EVENT -> read(type) + 1
        // Breath events go with breath rate, from its cursor (`stk`).
        HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT -> read(HistoryDataType.SLEEP_BREATH_RATE)
        HistoryDataType.SPORT_SUMMARY, HistoryDataType.SPORT_DETAIL -> read(HistoryDataType.SPORT_SUMMARY)
        HistoryDataType.SPORT_EFFECT -> read(type).let { if (it > now) monthBack(now, zone) else it }
        else -> read(type)
    }

    /** Sport effect's cursor in the future: now − 1 month, with the 12-hour HOUR field, minute and second at 0 (`vf70`). */
    private fun monthBack(now: Long, zone: ZoneId): Long =
        twelveHourFloor(ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(now), zone).minusMonths(1))

    /**
     * The next START's time after a chunk from [headerTime] that gave [records], or null to stop. [parsed] is the
     * parser's own next cursor (last record + 1 s for most types).
     */
    fun next(type: HistoryDataType, headerTime: Long, records: List<HistoryRecord>, parsed: Long?): Long? {
        // Zepp's loop stops on a chunk that parses no records.
        if (records.isEmpty()) return null
        val last = records.maxOf { it.timestamp }
        return when (type) {
            // Every record counts, unwritten ones (all 0xFF) and the minute in progress too.
            HistoryDataType.ACTIVITY -> headerTime + records.size * MINUTE
            HistoryDataType.TODAY_ACTIVITY_EXTENDED, HistoryDataType.STRESS_ALL_DAY -> headerTime + records.size * MINUTE + 1
            HistoryDataType.SLEEP_RESULT, HistoryDataType.HRV_RMSSD, HistoryDataType.HRV_SDNN,
            HistoryDataType.OSA_RESULT,
            -> last + MINUTE
            HistoryDataType.SPO2_SINGLE, HistoryDataType.SPO2_OSA, HistoryDataType.SPO2_OSA_EVENT -> last + SPO2_STEP
            else -> parsed
        }
    }

    private const val SPO2_STEP = 30L

    /**
     * The activity minutes Zepp's data-out keeps (`fnk`): everything up to the first minute that starts after now
     * and is unwritten (all 0xFF). Past unwritten minutes and the minute in progress are kept.
     */
    fun <R : HistoryRecord> activityKept(records: List<R>, now: Long): List<R> {
        val stop = records.indexOfFirst { it.timestamp > now && it is ActivitySample && it.isBlank }
        return if (stop < 0) records else records.subList(0, stop)
    }

    /**
     * What is written back for [type] once its records are stored, or null to leave it. [records] are all the
     * sync's records for the type (after [activityKept] for activity), [next] the last next cursor of the loop.
     */
    fun saved(type: HistoryDataType, records: List<HistoryRecord>, next: Long?): Long? {
        if (records.isEmpty()) return null
        val last = records.maxOf { it.timestamp }
        return when (type) {
            // The minute after the last one kept (`yfk.N`).
            HistoryDataType.ACTIVITY -> last + MINUTE
            HistoryDataType.HRV_RMSSD, HistoryDataType.HRV_SDNN, HistoryDataType.RECOVERY_HEART_RATE -> last + 1
            // The start of the last minute, so the minute in progress is asked again (`w7a0`).
            HistoryDataType.TODAY_ACTIVITY_EXTENDED,
            HistoryDataType.SLEEP_RESULT, HistoryDataType.RESTING_HEART_RATE, HistoryDataType.OSA_RESULT,
            HistoryDataType.SEC_HR, HistoryDataType.SEC_HR_V2,
            HistoryDataType.SPO2_SINGLE, HistoryDataType.SPO2_OSA, HistoryDataType.SPO2_OSA_EVENT,
            HistoryDataType.SPORT_EFFECT, HistoryDataType.SLEEP_PLAN,
            -> last
            // Max HR reads activity's cursor, and breath events results are ignored: neither keeps one.
            // Statistics are marked by the day they ran instead.
            HistoryDataType.DAILY_MAX_EXERCISE_HR, HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT,
            HistoryDataType.DEVICE_STATISTIC,
            -> null
            else -> next ?: (last + 1)
        }
    }

    /** The key a type's cursor is written under: second HR v2 writes 76's (`r2v`). */
    fun keyType(type: HistoryDataType): HistoryDataType = when (type) {
        HistoryDataType.SEC_HR_V2 -> HistoryDataType.SEC_HR
        else -> type
    }
}
