package org.openstrap.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zepp's `yt00` on a step push: notify goes off only behind the app, with no phone workout, once per 1.5 s. */
class StepsNotifyTest {
    private val push = byteArrayOf(0x07, 0x01, 0x10, 0x27, 0x00, 0x00)
    private val longAgo = 10_000L

    @Test fun `a push behind the app with no workout turns notify off`() {
        assertTrue(StepsNotify.offOnPush(push, appVisible = false, workoutActive = false, sinceLastOffMs = longAgo))
    }

    @Test fun `a push with the app in front or a workout recording leaves it on`() {
        assertFalse(StepsNotify.offOnPush(push, appVisible = true, workoutActive = false, sinceLastOffMs = longAgo))
        assertFalse(StepsNotify.offOnPush(push, appVisible = false, workoutActive = true, sinceLastOffMs = longAgo))
    }

    @Test fun `a query reply or an ack is not a push`() {
        val reply = byteArrayOf(0x04, 0x01, 0x01, 0x10, 0x27, 0x00, 0x00)
        assertFalse(StepsNotify.offOnPush(reply, appVisible = false, workoutActive = false, sinceLastOffMs = longAgo))
        assertFalse(StepsNotify.offOnPush(byteArrayOf(0x06, 0x01), appVisible = false, workoutActive = false, sinceLastOffMs = longAgo))
        assertFalse(StepsNotify.offOnPush(ByteArray(0), appVisible = false, workoutActive = false, sinceLastOffMs = longAgo))
    }

    @Test fun `a second push within 1_5 s sends no second off`() {
        assertFalse(StepsNotify.offOnPush(push, appVisible = false, workoutActive = false, sinceLastOffMs = 1_000))
        assertTrue(StepsNotify.offOnPush(push, appVisible = false, workoutActive = false, sinceLastOffMs = 1_500))
    }
}
