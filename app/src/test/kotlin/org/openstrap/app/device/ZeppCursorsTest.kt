package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.protocol.history.ActivitySample
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.HrvRmssd
import org.openstrap.protocol.history.TodayActivityExtended
import java.time.ZoneId
import java.time.ZonedDateTime

class ZeppCursorsTest {
    private val dubai = ZoneId.of("Asia/Dubai")
    private fun at(h: Int, m: Int, day: Int = 8) = ZonedDateTime.of(2026, 11, day, h, m, 17, 0, dubai).toEpochSecond()
    private val now = at(15, 30)

    @Test
    fun defaultIsTwoDaysBackAtTheHalfDay() {
        // Calendar.HOUR is the 12-hour field: an afternoon goes to noon, a morning to midnight.
        assertEquals(at(12, 0, day = 6) - 17, ZeppCursors.default(at(15, 30), dubai))
        assertEquals(at(0, 0, day = 6) - 17, ZeppCursors.default(at(9, 30), dubai))
    }

    @Test
    fun readCorrections() {
        assertEquals(ZeppCursors.FLOOR, ZeppCursors.onRead(100, now))
        assertEquals(now + 3600, ZeppCursors.onRead(now + 3600, now))
        assertEquals(now - 48 * 3600, ZeppCursors.onRead(now + 3601, now))
        // A bind time after now − 48 h wins, capped at now.
        assertEquals(now - 600, ZeppCursors.onRead(now + 7200, now, bindTime = now - 600))
        assertEquals(now, ZeppCursors.onRead(now + 7200, now, bindTime = now + 600))
    }

    @Test
    fun writeCorrections() {
        assertEquals(ZeppCursors.FLOOR, ZeppCursors.onWrite(0, now))
        assertEquals(now + 3600, ZeppCursors.onWrite(now + 3600, now))
        assertEquals(now, ZeppCursors.onWrite(now + 3601, now))
    }

    private fun start(type: HistoryDataType, stored: Map<HistoryDataType, Long>) =
        ZeppCursors.start(type, now, dubai) { stored.getValue(it) }

    @Test
    fun startsPerType() {
        val c = at(10, 5)
        val midnight = at(0, 0) - 17
        assertEquals(midnight, start(HistoryDataType.SLEEP_RESULT, mapOf(HistoryDataType.SLEEP_RESULT to c)))
        assertEquals(now - 45 * 86_400, start(HistoryDataType.SLEEP_RESULT, mapOf(HistoryDataType.SLEEP_RESULT to now - 50 * 86_400)))
        assertEquals(midnight, start(HistoryDataType.RESTING_HEART_RATE, mapOf(HistoryDataType.RESTING_HEART_RATE to c)))
        assertEquals(midnight, start(HistoryDataType.DAILY_MAX_EXERCISE_HR, mapOf(HistoryDataType.ACTIVITY to c)))
        assertEquals(c, start(HistoryDataType.SEC_HR_V2, mapOf(HistoryDataType.SEC_HR to c)))
        assertEquals(c - 1, start(HistoryDataType.OSA_RESULT, mapOf(HistoryDataType.OSA_RESULT to c)))
        assertEquals(c + 1, start(HistoryDataType.SPO2_OSA, mapOf(HistoryDataType.SPO2_OSA to c)))
        assertEquals(c, start(HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT, mapOf(HistoryDataType.SLEEP_BREATH_RATE to c)))
        assertEquals(c, start(HistoryDataType.SPORT_DETAIL, mapOf(HistoryDataType.SPORT_SUMMARY to c)))
        assertEquals(c, start(HistoryDataType.HRV_RMSSD, mapOf(HistoryDataType.HRV_RMSSD to c)))
        // Sport effect ahead of now: a month back at the half day.
        assertEquals(
            ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, dubai).toEpochSecond(),
            start(HistoryDataType.SPORT_EFFECT, mapOf(HistoryDataType.SPORT_EFFECT to now + 60)),
        )
    }

    private fun minute(ts: Long, blank: Boolean = false) =
        if (blank) ActivitySample(ts, 0xFF, 0xFF, 0xFF, 0xFF, null) else ActivitySample(ts, 1, 10, 20, 80, null)

    @Test
    fun nextPerType() {
        val h = at(10, 0) - 17
        val three = listOf(minute(h), minute(h + 60, blank = true), minute(h + 120))
        assertEquals(h + 180, ZeppCursors.next(HistoryDataType.ACTIVITY, h, three, null))
        val ext = List(2) { TodayActivityExtended(h + it * 60L, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) }
        assertEquals(h + 121, ZeppCursors.next(HistoryDataType.TODAY_ACTIVITY_EXTENDED, h, ext, null))
        val hrv = listOf(HrvRmssd(h, 0, 40), HrvRmssd(h + 300, 0, 41))
        assertEquals(h + 360, ZeppCursors.next(HistoryDataType.HRV_RMSSD, h, hrv, h + 301))
        assertEquals(h + 301, ZeppCursors.next(HistoryDataType.PAI, h, hrv, h + 301))
        assertNull(ZeppCursors.next(HistoryDataType.ACTIVITY, h, emptyList(), null))
    }

    @Test
    fun activityKeepsUpToTheFirstUnwrittenMinuteAfterNow() {
        val m = now / 60 * 60
        val records = listOf(minute(m - 120, blank = true), minute(m - 60), minute(m, blank = true), minute(m + 60), minute(m + 120, blank = true), minute(m + 180))
        // The past unwritten minute and the one in progress stay; a written minute ahead too, until an unwritten one.
        assertEquals(records.take(4), ZeppCursors.activityKept(records, now))
        assertEquals(m + 120, ZeppCursors.saved(HistoryDataType.ACTIVITY, ZeppCursors.activityKept(records, now), null))
    }

    @Test
    fun savedPerType() {
        val hrv = listOf(HrvRmssd(1000, 0, 40), HrvRmssd(1300, 0, 41))
        assertEquals(1301L, ZeppCursors.saved(HistoryDataType.HRV_RMSSD, hrv, 1360))
        assertEquals(1300L, ZeppCursors.saved(HistoryDataType.SLEEP_RESULT, hrv, 1360))
        assertEquals(1300L, ZeppCursors.saved(HistoryDataType.TODAY_ACTIVITY_EXTENDED, hrv, 1361))
        assertEquals(1360L, ZeppCursors.saved(HistoryDataType.STRESS_ALL_DAY, hrv, 1360))
        assertNull(ZeppCursors.saved(HistoryDataType.DAILY_MAX_EXERCISE_HR, hrv, 1301))
        assertNull(ZeppCursors.saved(HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT, hrv, 1301))
        assertNull(ZeppCursors.saved(HistoryDataType.HRV_RMSSD, emptyList(), null))
        assertEquals(HistoryDataType.SEC_HR, ZeppCursors.keyType(HistoryDataType.SEC_HR_V2))
    }
}
