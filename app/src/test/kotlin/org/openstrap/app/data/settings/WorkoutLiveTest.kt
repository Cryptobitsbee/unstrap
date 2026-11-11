package org.openstrap.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutLiveTest {
    @Test fun `kept figures read back as written`() {
        val live = WorkoutLive(1_700_000_000, 30, 42.5f, 110f)
        assertEquals(live, WorkoutLive.decode(live.encode()))
    }

    @Test fun `no kcal yet reads back as none`() {
        val live = WorkoutLive(1_700_000_000, 10, null, null)
        assertEquals(live, WorkoutLive.decode(live.encode()))
    }

    @Test fun `nothing or something broken reads as nothing`() {
        assertNull(WorkoutLive.decode(null))
        assertNull(WorkoutLive.decode(""))
        assertNull(WorkoutLive.decode("x,1,2,3"))
        assertNull(WorkoutLive.decode("1,2,3"))
    }
}
