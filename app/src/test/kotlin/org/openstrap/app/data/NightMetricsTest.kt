package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.db.HrvSample

/** The night's HRV: one gate and one rounding for Recovery and for every screen. */
class NightMetricsTest {
    private val start = 10_000L
    private val end = 40_000L

    private fun at(t: Long, ms: Int) = HrvSample(t, ms)

    @Test fun `only readings inside the sleep and from 5 to 200 ms count`() {
        val readings = listOf(
            at(start - 1, 60), at(start, 40), at(start + 600, 4), at(start + 1200, 5), at(start + 1800, 200),
            at(start + 2400, 201), at(end, 60), at(end + 1, 90),
        )
        // 40, 5, 200 and 60 are in; 4 and 201 are out of range, and the two outside the sleep are out of it.
        assertEquals(listOf(40, 5, 200, 60), NightMetrics.sleepHrvReadings(readings, start, end))
        assertEquals(76.25f, NightMetrics.hrvMean(NightMetrics.sleepHrvReadings(readings, start, end)))
    }

    @Test fun `the figure shown is the mean to the nearest whole ms`() {
        // 51.5 goes up and 51.33 down; a cut to a whole number would give 51 for both.
        assertEquals(52f, NightMetrics.sleepHrv(listOf(at(start, 51), at(start + 60, 52)), start, end))
        assertEquals(51f, NightMetrics.sleepHrv(listOf(at(start, 51), at(start + 60, 51), at(start + 120, 52)), start, end))
    }

    @Test fun `a night with no clean reading has no figure`() {
        assertNull(NightMetrics.sleepHrv(emptyList(), start, end))
        assertNull(NightMetrics.sleepHrv(listOf(at(start + 10, 3), at(start + 20, 400)), start, end))
        assertNull(NightMetrics.hrvMean(emptyList()))
    }

    @Test fun `the screens and Recovery take the same mean`() {
        val readings = (0 until 20).map { at(start + it * 600L, 30 + it * 3) }
        val mean = NightMetrics.hrvMean(NightMetrics.sleepHrvReadings(readings, start, end))!!
        assertEquals(Math.round(mean).toFloat(), NightMetrics.sleepHrv(readings, start, end))
    }
}
