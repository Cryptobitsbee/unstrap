package org.openstrap.app.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.catching
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.LiveHeartRate
import org.openstrap.protocol.services.SportSession
import org.openstrap.protocol.toHex
import org.openstrap.protocol.u8

/**
 * Live heart rate from the strap, on the channel push, or the standard 2A37 characteristic without it. It streams
 * only while the app is on screen, a workout records it, or workout auto-start watches for effort (one reason each in [reasons]). There is
 * no switch for it, as in Zepp: the strap's own measuring is set by its monitor frequency.
 */
// One setter per reason to stream, as StrapController has them, plus start, stop and the reading.
@Suppress("TooManyFunctions")
internal class LiveHeartRateStream(
    private val scope: CoroutineScope,
    private val link: LiveLink,
    private val connection: StateFlow<ConnectionState>,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val log: (String) -> Unit,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    val liveHeartRate = MutableStateFlow<Int?>(null)
    val heartRateReadings = MutableSharedFlow<Int>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Which of the strap's heart rate sources is shown and recorded ([HeartRateSources]); guarded by itself. */
    private val sources = HeartRateSources()

    /** Why live HR is wanted. It streams while any holds: the app on screen, a workout recording, or the effort watch. */
    private enum class Reason { AppVisible, Workout, EffortWatch }

    private val reasons = MutableStateFlow<Set<Reason>>(emptySet())

    /** Bumped by each new connection, so a stream is opened again even when the link dropped and came back unseen. */
    private val connects = MutableStateFlow(0)

    /** True from the first command of a stream to the end of its stop, so a stop is sent only for a stream that opened. */
    private val wireOpen = MutableStateFlow(false)

    /** The session and client the open stream was sent on, for its stop. Touched by the one coroutine in [follow] only. */
    private var openSession: ChannelSession? = null
    private var openClient: GattClient? = null

    /** What [follow] acts on: stream or not, on which connection. */
    private data class Plan(val stream: Boolean, val connect: Int)

    init {
        // One ordered coroutine owns the wire: collectLatest ends a stream (cancelled, then joined) before the next
        // block starts, so a stop and a start can't cross, and a failed flash that ends firmwareUpdating restarts it.
        scope.launch {
            combine(reasons, connection, firmwareUpdating, connects) { r, c, fw, n ->
                Plan(r.isNotEmpty() && c is ConnectionState.Connected && !fw, n)
            }.distinctUntilChanged().collectLatest { follow(it) }
        }
    }

    fun setAppVisible(visible: Boolean) { set(Reason.AppVisible, visible) }

    fun setWorkoutActive(active: Boolean) {
        if (set(Reason.Workout, active)) log(if (active) "workout started: live hr stays on in the background" else "workout ended")
    }

    fun setEffortWatch(active: Boolean) { set(Reason.EffortWatch, active) }

    /** True when [reason] changed. */
    private fun set(reason: Reason, on: Boolean): Boolean {
        var changed = false
        reasons.update { current ->
            changed = (reason in current) != on
            if (on) current + reason else current - reason
        }
        return changed
    }

    /** A new connection: streams again if wanted. */
    fun onConnected() {
        connects.update { it + 1 }
    }

    /** The link ended: the stream with it. */
    fun onLinkEnded() {
        liveHeartRate.value = null
        synchronized(sources) { sources.reset() }
        sessionLogged = false
    }

    /**
     * Stops streaming and returns once the strap has been told (or a few seconds passed), for a firmware update, which
     * sets [firmwareUpdating] first: the stream follows it on its own, and starts again if the update fails.
     */
    suspend fun pause() {
        withTimeoutOrNull(PAUSE_WAIT_MS) { wireOpen.first { !it } }
    }

    private suspend fun follow(plan: Plan) {
        if (plan.stream) stream() else closeWire()
    }

    /** Tells the strap to stop, if a stream was opened and the link is still up. Not cancellable: both commands go out. */
    private suspend fun closeWire() {
        liveHeartRate.value = null
        val s = openSession
        val client = openClient
        openSession = null
        openClient = null
        if (!wireOpen.value) return
        withContext(NonCancellable) {
            if (connection.value is ConnectionState.Connected) stop(s, client)
            wireOpen.value = false
        }
    }

    // The catch-all rethrows a cancellation, and a failed stream only stops showing live HR.
    @Suppress("SuspendFunSwallowedCancellation", "TooGenericExceptionCaught")
    private suspend fun stream() {
        liveHeartRate.value = null
        val s = link.activeSession ?: return
        val client = link.gatt ?: return
        synchronized(sources) { sources.reset() }
        openSession = s
        openClient = client
        wireOpen.value = true
        coroutineScope {
            launch { clearWhenStale() }
            try {
                val channel = s.services.has(Endpoints.HEART_RATE)
                listen(s, client, channel)
                if (channel) {
                    val opened = s.request(Endpoints.OPEN_APP, LiveHeartRate.OPEN, OPEN_TIMEOUT_MS) { it.u8(0) == OPEN_REPLY }
                    log("live hr open: ${opened.toHex()}")
                    val subscribed = s.request(Endpoints.HEART_RATE, LiveHeartRate.SUBSCRIBE, OPEN_TIMEOUT_MS) {
                        it.u8(0) == SUBSCRIBE_REPLY
                    }
                    log("live hr subscribe: ${subscribed.toHex()}")
                } else {
                    client.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, LiveHeartRate.LEGACY_START, withResponse = true)
                    log("live hr started via 2A39")
                }
                while (isActive) {
                    delay(LiveHeartRate.KEEP_ALIVE_MS)
                    if (channel) {
                        s.send(Endpoints.OPEN_APP, LiveHeartRate.KEEP_ALIVE)
                    } else {
                        val keepAlive = LiveHeartRate.LEGACY_KEEP_ALIVE
                        client.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, keepAlive, withResponse = true)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("live hr failed: $e")
                liveHeartRate.value = null
            }
        }
    }

    /**
     * Collects the channel push on a strap that has 0x0048 ([channel]), else the standard 2A37 characteristic, in this
     * stream's job. Never both: Zepp builds one path per connection and subscribes to 2A37 only on a strap without
     * 0x0048 (`srg0.h`; its 2A37 class `gok` exists only inside the GATT path `dok`).
     */
    private suspend fun CoroutineScope.listen(s: ChannelSession, client: GattClient, channel: Boolean) {
        var pushes = 0
        launch {
            s.messagesFor(Endpoints.HEART_RATE).collect { p ->
                if (pushes++ == 0) log("live hr 0048 < ${p.toHex()}")
                LiveHeartRate.parsePush(p)?.let { offer(HeartRateSources.Source.CHANNEL, it, noReading = false) }
            }
        }
        // Each keep-alive is answered with `05 02`; only log the first reply and anything else.
        var opens = 0
        launch {
            s.messagesFor(Endpoints.OPEN_APP).collect { p ->
                if (opens++ == 0 || !p.contentEquals(KEEP_ALIVE_REPLY)) log("live hr 001d < ${p.toHex()}")
            }
        }
        if (channel) return
        var measurements = 0
        launch {
            client.notifications.filter { it.characteristic == StrapUuids.HR_MEASUREMENT }
                .collect { n ->
                    if (measurements++ == 0) log("live hr 2a37 < ${n.value.toHex()}")
                    val offBody = LiveHeartRate.measurementOffBody(n.value)
                    val bpm = LiveHeartRate.parseMeasurement(n.value)
                    if (offBody || bpm != null) offer(HeartRateSources.Source.MEASUREMENT, bpm, offBody)
                }
        }
        catching { client.enableNotifications(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_MEASUREMENT) }
            .onFailure { log("2A37 subscribe failed: $it") }
    }

    private suspend fun stop(s: ChannelSession?, client: GattClient?) {
        catching {
            if (s != null && s.services.has(Endpoints.HEART_RATE)) {
                s.send(Endpoints.OPEN_APP, LiveHeartRate.CLOSE)
                s.send(Endpoints.HEART_RATE, LiveHeartRate.UNSUBSCRIBE)
            } else {
                client?.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, LiveHeartRate.LEGACY_STOP, withResponse = true)
            }
        }.onFailure { log("live hr stop failed: $it") }
    }

    /**
     * A `14` frame of the strap's workout session (0x0019): the workout's heart rate while it comes ([HeartRateSources]). It
     * arrives through [StrapManager] whether or not this stream runs.
     */
    fun onSessionData(data: SportSession.Push.Data) {
        if (!sessionLogged) {
            sessionLogged = true
            log("live hr: the strap's session heart rate leads (${data.heartRate ?: "no reading"})")
        }
        offer(HeartRateSources.Source.SESSION, data.heartRate, data.noReading)
    }

    @Volatile private var sessionLogged = false

    private fun offer(source: HeartRateSources.Source, bpm: Int?, noReading: Boolean) {
        val decision = synchronized(sources) { sources.offer(source, bpm, noReading, clock()) }
        when (decision) {
            is HeartRateSources.Decision.Reading -> onReading(decision.bpm)
            HeartRateSources.Decision.NoReading -> {
                if (liveHeartRate.value != null) log("live hr: no reading from the strap ($source)")
                liveHeartRate.value = null
            }
            HeartRateSources.Decision.Ignore -> Unit
        }
    }

    /** A number no source has refreshed for [HeartRateSources.STALE_MS] is cleared rather than left on screen. */
    private suspend fun clearWhenStale() {
        while (true) {
            delay(STALE_CHECK_MS)
            val stale = synchronized(sources) { sources.isStale(clock()).also { if (it) sources.cleared() } }
            if (stale && liveHeartRate.value != null) {
                log("live hr: no reading for ${HeartRateSources.STALE_MS} ms, cleared")
                liveHeartRate.value = null
            }
        }
    }

    /**
     * Shown live, and recorded during a workout; the strap also records per-second HR itself and history sync stores it.
     * Called directly only by the emulator's simulated strap.
     */
    fun onReading(bpm: Int) {
        liveHeartRate.value = bpm
        heartRateReadings.tryEmit(bpm)
    }

    private companion object {
        val KEEP_ALIVE_REPLY = byteArrayOf(0x05, 0x02)
        const val OPEN_TIMEOUT_MS = 5_000L
        const val OPEN_REPLY = 0x05
        const val SUBSCRIBE_REPLY = 0x04
        const val STALE_CHECK_MS = 2_000L
        const val PAUSE_WAIT_MS = 5_000L
    }
}
