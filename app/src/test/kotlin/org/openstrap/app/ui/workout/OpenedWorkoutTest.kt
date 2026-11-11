package org.openstrap.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.WorkoutAnalysis

/** A workout's detail tells "no such workout" from "still loading", and each answer names its workout. */
class OpenedWorkoutTest {
    private fun analysis(start: Long) = WorkoutAnalysis(
        Workout(start, start + 600, 1, 600, 50, 140, 160, 90, null, null, null, null, null),
        start + 600, emptyList(), null, null, null, null, List(5) { 0 }, 190,
    )

    @Test fun `no workout at the key is missing, not loading`() {
        assertEquals(OpenedWorkout.Missing(100), OpenedWorkout.of(100, null))
        assertNotEquals(OpenedWorkout.Loading, OpenedWorkout.of(100, null))
    }

    @Test fun `a workout at the key is found`() {
        val a = analysis(100)
        assertEquals(OpenedWorkout.Found(a), OpenedWorkout.of(100, a))
    }

    @Test fun `a missing answer for one key is not the answer for another`() {
        assertNotEquals(OpenedWorkout.Missing(100), OpenedWorkout.Missing(200))
    }
}
