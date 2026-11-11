package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.Metric
import org.openstrap.app.presentation.StressLevel

/** Blood oxygen's latest reading of the day, which Today, All metrics and the details opened from them lead with. */
class LatestReadingTest {
    @Test fun `the latest reading is the newest of the day, whatever the order they arrive in`() {
        val day = DayUiState(spo2 = listOf(ChartPoint(300, 95f), ChartPoint(900, 97f), ChartPoint(600, 94f)))
        assertEquals(ChartPoint(900, 97f), LatestReading.spo2(day))
    }

    @Test fun `a day without a reading has no latest one`() {
        assertNull(LatestReading.spo2(DayUiState()))
    }

    @Test fun `stress's latest reading is the newest of the day's, whatever the order`() {
        val day = DayUiState(stress = listOf(ChartPoint(300, 40f), ChartPoint(900, 62f), ChartPoint(600, 55f)))
        assertEquals(ChartPoint(900, 62f), LatestReading.stress(day))
        assertNull(LatestReading.stress(DayUiState()))
        assertEquals(ChartPoint(900, 62f), LatestReading.of(Metric.Stress, day))
        assertNull(LatestReading.of(Metric.HeartRate, day))
    }

    @Test fun `a night average is not the latest reading`() {
        // The night's readings stay in their own series: only the day's series, day and night, counts.
        val day = DayUiState(sleepSpo2 = listOf(ChartPoint(100, 90f)))
        assertNull(LatestReading.spo2(day))
    }

    private val hour = 3600L
    private fun clock(t: Long) = "t$t"

    @Test fun `a reading is stale once it is more than an hour old`() {
        assertFalse(LatestReading.isStale(1000, 1000))
        assertFalse(LatestReading.isStale(1000, 1000 + hour))
        assertTrue(LatestReading.isStale(1000, 1000 + hour + 1))
        // A reading taken after now (clock skew) is fresh.
        assertFalse(LatestReading.isStale(2000, 1000))
    }

    @Test fun `a reading is asleep inside the sleep window, ends included, and daytime without one`() {
        val window = 1000L..5000L
        assertTrue(LatestReading.isAsleep(1000, window))
        assertTrue(LatestReading.isAsleep(5000, window))
        assertFalse(LatestReading.isAsleep(999, window))
        assertFalse(LatestReading.isAsleep(5001, window))
        assertFalse(LatestReading.isAsleep(3000, null))
    }

    @Test fun `the sleep window is the main sleep's start to its end`() {
        assertNull(LatestReading.sleepWindow(DayUiState()))
        assertEquals(100L..900L, LatestReading.sleepWindow(DayUiState(sleep = SleepSession(start = 100, end = 900))))
    }

    @Test fun `blood oxygen's line says daytime or asleep, with the time only once the reading is stale`() {
        val window = 1000L..5000L
        assertEquals("Daytime", LatestReading.spo2Line(8000, window, 8000 + 60, time = ::clock))
        assertEquals("Daytime, t8000", LatestReading.spo2Line(8000, window, 8000 + 2 * hour, time = ::clock))
        assertEquals("While asleep", LatestReading.spo2Line(3000, window, 3000 + 60, time = ::clock))
        assertEquals("While asleep, t3000", LatestReading.spo2Line(3000, window, 3000 + 2 * hour, time = ::clock))
        // No sleep window known: daytime.
        assertEquals("Daytime, t3000", LatestReading.spo2Line(3000, null, 3000 + 2 * hour, time = ::clock))
    }

    @Test fun `stress's line is the level of the reading, with the time only once it is stale`() {
        assertEquals("Normal", LatestReading.stressLine(500, 45f, 600, tagged = true, time = ::clock))
        assertEquals("Normal, t500", LatestReading.stressLine(500, 45f, 500 + 2 * hour, tagged = true, time = ::clock))
        assertEquals("Relaxed", LatestReading.stressLine(500, 12f, 600, tagged = false, time = ::clock))
        assertEquals("Medium, t500", LatestReading.stressLine(500, 70f, 500 + 2 * hour, tagged = false, time = ::clock))
    }

    @Test fun `where the High tag shows the line doesn't repeat it`() {
        assertNull(LatestReading.stressLine(500, 85f, 600, tagged = true, time = ::clock))
        assertEquals("Latest at t500", LatestReading.stressLine(500, 85f, 500 + 2 * hour, tagged = true, time = ::clock))
        // A row with no tag to show says the level itself.
        assertEquals("High", LatestReading.stressLine(500, 85f, 600, tagged = false, time = ::clock))
        assertEquals("High, t500", LatestReading.stressLine(500, 85f, 500 + 2 * hour, tagged = false, time = ::clock))
    }

    @Test fun `only a High stress reading is tagged`() {
        assertNull(LatestReading.stressTag(StressLevel.Relaxed))
        assertNull(LatestReading.stressTag(StressLevel.Normal))
        assertNull(LatestReading.stressTag(StressLevel.Medium))
        assertEquals(NewsTag("High", goal = false), LatestReading.stressTag(StressLevel.High))
    }

    @Test fun `the latest blood oxygen reading is asleep by the day's sleep window`() {
        val sleep = SleepSession(start = 1000, end = 5000)
        assertTrue(LatestReading.spo2Asleep(DayUiState(sleep = sleep, spo2 = listOf(ChartPoint(900, 95f), ChartPoint(3000, 94f)))))
        assertFalse(LatestReading.spo2Asleep(DayUiState(sleep = sleep, spo2 = listOf(ChartPoint(3000, 94f), ChartPoint(9000, 96f)))))
        assertFalse(LatestReading.spo2Asleep(DayUiState(sleep = sleep)))
    }

    @Test fun `the shaded hours are the readings inside the sleep window, clipped to the day`() {
        val day = 0L..(24 * hour)
        val slot = 15 * 60L
        val count = (24 * hour / slot).toInt() + 1
        // A night from 23:00 the evening before to 07:00 shades from the start of the day to 07:00.
        assertEquals(0..28, LatestReading.shadeIndices(-hour..7 * hour, day, slot, count))
        assertEquals(4..8, LatestReading.shadeIndices(hour..2 * hour, day, slot, count))
        assertNull(LatestReading.shadeIndices(null, day, slot, count))
        assertNull(LatestReading.shadeIndices(-10 * hour..-9 * hour, day, slot, count))
    }
}
