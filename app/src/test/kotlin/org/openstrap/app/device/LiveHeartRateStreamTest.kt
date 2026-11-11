package org.openstrap.app.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.GattStrapLink
import org.openstrap.protocol.ChannelSession

/** Live HR's reasons: the workout and the screen are separate, and a pause with nothing open doesn't wait. */
class LiveHeartRateStreamTest {
    private val noLink = object : LiveLink {
        override val session: ChannelSession? = null
        override val gatt: GattClient? = null
        override val link: GattStrapLink? = null
        override val activeSession: ChannelSession? = null
    }

    @Test
    fun theWorkoutAndTheScreenAreSeparateReasons() = runTest {
        val log = mutableListOf<String>()
        val live = LiveHeartRateStream(
            backgroundScope, noLink, MutableStateFlow(ConnectionState.Disconnected), MutableStateFlow(false), { log += it }, clock = { 0L },
        )
        live.setAppVisible(true)
        live.setWorkoutActive(true)
        live.setWorkoutActive(true)
        live.setAppVisible(false)
        live.setWorkoutActive(false)
        // Each change of the workout is logged once, whatever else is on.
        assertEquals(listOf("workout started: live hr stays on in the background", "workout ended"), log)
    }

    @Test
    fun pauseReturnsAtOnceWhenNothingIsOpen() = runTest {
        val live = LiveHeartRateStream(
            backgroundScope, noLink, MutableStateFlow(ConnectionState.Disconnected), MutableStateFlow(false), {}, clock = { 0L },
        )
        live.pause()
    }
}
