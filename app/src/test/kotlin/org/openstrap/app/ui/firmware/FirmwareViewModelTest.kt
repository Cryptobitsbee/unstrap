package org.openstrap.app.ui.firmware

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapController
import org.openstrap.app.testing.endMain
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import org.openstrap.protocol.firmware.DfuProgress
import org.openstrap.protocol.firmware.FirmwareType

/** The firmware view model stands on the strap, the settings, a cache folder and a file reader, not the whole app (audit A3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirmwareViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings by lazy {
        SettingsStore.of(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } })
    }
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val vms = mutableListOf<FirmwareViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        endMain(vms.map { it.viewModelScope.coroutineContext[Job] } + scope.coroutineContext[Job])
    }

    private fun vm(openFile: (Uri) -> InputStream?) =
        FirmwareViewModel(strap(), settings, folder.newFolder(), openFile).also { vms += it }

    private val picked = Uri.parse("content://test/firmware.bin")

    @Test fun `a picked file is read through the reader it was given`() = runBlocking {
        val vm = vm { ByteArrayInputStream(ByteArray(10)) }
        vm.loadFile(picked).join()
        assertEquals(FirmwareStep.Failed("That file is 10 bytes, which isn't a plausible firmware image."), vm.step.value)
    }

    @Test fun `a file that can't be opened asks for it again`() = runBlocking {
        val vm = vm { null }
        vm.loadFile(picked).join()
        assertEquals(FirmwareStep.Failed("Couldn't read the file. Choose it again."), vm.step.value)
    }

    @Test fun `install waits for a connected strap with enough battery`() {
        val vm = vm { null }
        assertEquals(listOf("Connect the strap."), vm.blockers())
        connection.value = ConnectionState.Connected(20, "3.25")
        assertEquals(listOf("Charge the strap above 30% (now 20%)."), vm.blockers())
        assertEquals(connection.value, vm.connection.value)
    }

    @Test fun `events that don't fit the step are ignored, and late progress can't revive an install`() = runBlocking {
        connection.value = ConnectionState.Connected(50, "3.25")
        val vm = vm { ByteArrayInputStream(ByteArray(10)) }
        val image = ReadyImage(ByteArray(2048), FirmwareType.FIRMWARE, "Firmware", null, fromCloud = false, trailer = null, warnings = emptyList())
        vm.install(image)
        val installing = vm.step.value
        assertTrue(installing is FirmwareStep.Installing)

        // A file picked, a check, or a second install during the install change nothing.
        vm.loadFile(picked).join()
        vm.check()
        vm.install(image)
        vm.reset()
        assertEquals(installing, vm.step.value)

        // Progress is applied while installing, then the install ends.
        progress!!(DfuProgress.Applying)
        assertTrue(vm.step.value is FirmwareStep.Installing)
        installDone!!.resume(Unit)
        assertEquals(FirmwareStep.Installed, vm.step.value)

        // A callback that arrives after the end doesn't start it again.
        progress!!(DfuProgress.Validating)
        assertEquals(FirmwareStep.Installed, vm.step.value)
        vm.reset()
        progress!!(DfuProgress.Validating)
        assertEquals(FirmwareStep.Idle, vm.step.value)
    }

    private var progress: ((DfuProgress) -> Unit)? = null
    private var installDone: Continuation<Unit>? = null

    /** A strap whose link is [connection] and that does nothing else. */
    private fun strap(): StrapController {
        val flows = mutableMapOf<String, Any>()
        return Proxy.newProxyInstance(StrapController::class.java.classLoader, arrayOf(StrapController::class.java)) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "test strap"
                method.name == "getConnection" -> connection
                // An install that stays open until the test ends it.
                method.name == "installFirmware" -> {
                    @Suppress("UNCHECKED_CAST")
                    progress = args!![2] as (DfuProgress) -> Unit
                    @Suppress("UNCHECKED_CAST")
                    installDone = args[3] as Continuation<Unit>
                    COROUTINE_SUSPENDED
                }
                StateFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableStateFlow<Any?>(null) }
                SharedFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableSharedFlow<Any?>() }
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> Unit
            }
        } as StrapController
    }
}
