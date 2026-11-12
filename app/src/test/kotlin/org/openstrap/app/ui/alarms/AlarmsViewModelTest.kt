package org.openstrap.app.ui.alarms

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.device.StrapCommandException
import org.openstrap.app.device.StrapController
import org.openstrap.app.testing.endMain
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.SettingsGroup
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** A failed read from the strap says plain words on the Alarms page, never an exception's own text (audit E6). */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmsViewModelTest {
    private val models = mutableListOf<AlarmsViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        endMain(models.map { it.viewModelScope.coroutineContext[Job] })
    }

    @Test fun `an unexpected failure says it couldn't read, not its own text`() {
        val error = errorAfterRead(IllegalStateException("Room cannot verify the data integrity"))
        assertEquals("Couldn't read from the strap", error)
    }

    @Test fun `the strap's own reason and a timeout keep their words`() {
        assertEquals("The strap isn't connected", errorAfterRead(StrapCommandException("The strap isn't connected")))
        assertEquals("The strap didn't answer. Try again.", errorAfterRead(GattTimeoutException("op 0x12 timed out")))
    }

    private fun errorAfterRead(failure: Throwable): String? {
        val vm = AlarmsViewModel(failingStrap(failure)).also { models += it }
        val state = vm.state.value
        assertFalse(state.loading)
        // A failed read is the Alarms page's own error, apart from a failed write's.
        return state.alarmsError
    }

    /** A strap whose alarm read fails with [failure]. Everything else answers empty. */
    private fun failingStrap(failure: Throwable): StrapController {
        val flows = mutableMapOf<String, Any>()
        return Proxy.newProxyInstance(StrapController::class.java.classLoader, arrayOf(StrapController::class.java)) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "failing strap"
                // A suspend call fails through its continuation, so a checked exception isn't wrapped by the proxy.
                method.name == "readAlarms" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args.last() as Continuation<Any?>).resumeWith(Result.failure(failure))
                    COROUTINE_SUSPENDED
                }
                StateFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableStateFlow<Any?>(null) }
                SharedFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableSharedFlow<Any?>() }
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> Unit
            }
        } as StrapController
    }

    @Test fun `a failed first read is Failed, not an empty list, and there is nothing to add to`() {
        val vm = AlarmsViewModel(failingStrap(StrapCommandException("The strap isn't connected"))).also { models += it }
        val state = vm.state.value
        assertEquals(AlarmsRead.Failed("The strap isn't connected"), state.read)
        assertNull(state.caps)
        assertFalse(state.canAdd)
    }

    @Test fun `a save during a refresh is refused and the refresh that was asked for still runs after`() {
        val strap = FakeStrap()
        val vm = AlarmsViewModel(strap.controller).also { models += it }
        assertEquals(AlarmsRead.Loaded, vm.state.value.read)
        // A write is in flight and waiting on the strap.
        strap.holdSave = true
        val writing = vm.save(Alarm(0, 7, 0, Alarm.WEEKDAYS, enabled = true), isNew = true)
        assertTrue(vm.state.value.busy)
        // A push asks for a refresh meanwhile, and a second save comes in.
        vm.refresh()
        val second = vm.save(Alarm(1, 8, 0, 0, enabled = true), isNew = true)
        assertTrue(second.isCompleted)
        assertFalse(second.getCompleted())
        val readsBefore = strap.reads
        strap.release()
        assertTrue(writing.getCompleted())
        // One read for the write, one more for the refresh that was waiting.
        assertEquals(readsBefore + 2, strap.reads)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `a failed save completes false so the sheet stays open`() {
        val strap = FakeStrap()
        val vm = AlarmsViewModel(strap.controller).also { models += it }
        strap.saveFailure = StrapCommandException("Slot taken")
        val done = vm.save(Alarm(0, 7, 0, 0, enabled = true), isNew = true)
        assertFalse(done.getCompleted())
        assertEquals("Slot taken", vm.state.value.error)
    }

    @Test fun `a failed alarms read leaves the health settings read and its own error on the Alarms page`() {
        val strap = FakeStrap().apply { alarmsFailure = StrapCommandException("Alarms unreadable") }
        val vm = AlarmsViewModel(strap.controller).also { models += it }
        val state = vm.state.value
        assertEquals("Alarms unreadable", state.alarmsError)
        assertNull(state.healthError)
        assertTrue(state.health != null)
    }

    @Test fun `a failed health read leaves the alarms read and keeps the last good settings`() {
        val strap = FakeStrap()
        val vm = AlarmsViewModel(strap.controller).also { models += it }
        assertTrue(vm.state.value.health != null)
        strap.healthFailure = StrapCommandException("Settings unreadable")
        vm.refresh()
        val state = vm.state.value
        assertEquals("Settings unreadable", state.healthError)
        assertNull(state.alarmsError)
        assertEquals(AlarmsRead.Loaded, state.read)
        assertEquals(1, state.alarms.size)
        assertTrue(state.health != null)
    }

    @Test fun `a failed save's reason survives the refresh that was queued meanwhile`() {
        val strap = FakeStrap()
        val vm = AlarmsViewModel(strap.controller).also { models += it }
        strap.holdSave = true
        val writing = vm.save(Alarm(0, 7, 0, 0, enabled = true), isNew = true)
        vm.refresh()
        strap.release(StrapCommandException("Slot taken"))
        assertFalse(writing.getCompleted())
        assertEquals("Slot taken", vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    /** A strap that holds one alarm, can hold a save until [release], and counts its reads. */
    private class FakeStrap {
        var reads = 0
        var holdSave = false
        var saveFailure: Throwable? = null
        var alarmsFailure: Throwable? = null
        var healthFailure: Throwable? = null
        private var held: Continuation<Any?>? = null
        private val caps = AlarmCapabilities(version = 1, maxCount = 10, titles = false, smartSupported = false)
        private val flows = mutableMapOf<String, Any>()

        fun release(failure: Throwable? = null) {
            held?.resumeWith(failure?.let { Result.failure(it) } ?: Result.success(Unit))
            held = null
            holdSave = false
        }

        @Suppress("UNCHECKED_CAST")
        val controller: StrapController = Proxy.newProxyInstance(StrapController::class.java.classLoader, arrayOf(StrapController::class.java)) { proxy, method, args ->
            fun done(r: Result<Any?>): Any? {
                (args.last() as Continuation<Any?>).resumeWith(r)
                return COROUTINE_SUSPENDED
            }
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "fake strap"
                method.name == "readAlarms" -> { reads++; done(alarmsFailure?.let { Result.failure(it) } ?: Result.success(caps to listOf(Alarm(0, 6, 0, Alarm.WEEKDAYS, enabled = true)))) }
                method.name == "readHealthSettings" -> done(healthFailure?.let { Result.failure(it) } ?: Result.success(SettingsGroup(0, 1, emptyMap())))
                method.name == "saveAlarm" -> when {
                    saveFailure != null -> done(Result.failure(saveFailure!!))
                    holdSave -> { held = args.last() as Continuation<Any?>; COROUTINE_SUSPENDED }
                    else -> done(Result.success(Unit))
                }
                StateFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableStateFlow<Any?>(null) }
                SharedFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableSharedFlow<Any?>() }
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> Unit
            }
        } as StrapController
    }
}
