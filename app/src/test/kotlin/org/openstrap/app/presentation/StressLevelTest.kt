package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Zepp's stress bands: relaxed 0 to 39, normal 40 to 59, medium 60 to 79, high 80 to 100. */
class StressLevelTest {
    @Test fun `a stress value is judged by Zepp's bands`() {
        assertEquals(StressLevel.Relaxed, StressLevel.of(0f))
        assertEquals(StressLevel.Relaxed, StressLevel.of(39f))
        assertEquals(StressLevel.Normal, StressLevel.of(40f))
        assertEquals(StressLevel.Normal, StressLevel.of(59f))
        assertEquals(StressLevel.Medium, StressLevel.of(60f))
        assertEquals(StressLevel.Medium, StressLevel.of(79f))
        assertEquals(StressLevel.High, StressLevel.of(80f))
        assertEquals(StressLevel.High, StressLevel.of(100f))
    }

    @Test fun `the level agrees with the whole number shown`() {
        assertEquals(StressLevel.Normal, StressLevel.of(39.6f))
        assertEquals(StressLevel.Relaxed, StressLevel.of(39.4f))
        assertEquals(StressLevel.High, StressLevel.of(79.5f))
    }

    @Test fun `each level has its word`() {
        assertEquals(listOf("Relaxed", "Normal", "Medium", "High"), StressLevel.entries.map { it.word() })
    }
}
