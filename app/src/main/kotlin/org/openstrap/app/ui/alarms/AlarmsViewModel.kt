package org.openstrap.app.ui.alarms

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openstrap.app.data.catching
import org.openstrap.app.device.StrapCommandException
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.StrapManager
import org.openstrap.app.ui.device.ErrorCopy
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup

/** What the screen knows about the strap's alarms: not yet read, never read (the first read failed), or read. */
sealed interface AlarmsRead {
    data object Loading : AlarmsRead
    data class Failed(val message: String) : AlarmsRead
    data object Loaded : AlarmsRead
}

data class AlarmsUiState(
    /**
     * Loading until the first read ends. Failed means no read has ever worked, so [caps] and [alarms] are unknown and the
     * screen must not show them as "no alarms". Once Loaded it stays Loaded: a later failed read only sets [alarmsError].
     */
    val read: AlarmsRead = AlarmsRead.Loading,
    /** A write is in flight; controls are disabled meanwhile. */
    val busy: Boolean = false,
    /** A failed write's reason. */
    val error: String? = null,
    /** Why the alarms read failed, if it did; shown on the Alarms page only. */
    val alarmsError: String? = null,
    /** Why the health settings read failed, if it did; shown on the Health page only. */
    val healthError: String? = null,
    val caps: AlarmCapabilities? = null,
    val alarms: List<Alarm> = emptyList(),
    val health: SettingsGroup? = null,
) {
    val loading: Boolean get() = read is AlarmsRead.Loading

    val canAdd: Boolean get() = caps != null && alarms.size < caps.maxCount

    /** The strap only raises HR alerts while it measures often enough; Zepp allows continuous or every minute. */
    val alertsAvailable: Boolean get() = health?.get(SettingsCodec.KEY_HR_INTERVAL)?.number?.toInt().let { it == SettingsCodec.HR_CONTINUOUS || it == 1 }

    /**
     * The low blood oxygen alert works only with auto blood oxygen (HEALTH key 0x31) on, as Zepp gates its row
     * (`zas`). A strap that doesn't report the switch keeps the alert open.
     */
    val oxygenAlertAvailable: Boolean get() = health?.get(SettingsCodec.KEY_SMART_SPO2)?.bool != false
}

/** Reads alarms and health settings from the strap and writes changes back, re-reading after each write. */
// One function per strap setting the page changes, plus the read and the write queue.
@Suppress("TooManyFunctions")
class AlarmsViewModel(private val strap: StrapController) : ViewModel() {
    private val _state = MutableStateFlow(AlarmsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        // A one-time alarm switches itself off after ringing; show what the strap now holds.
        viewModelScope.launch { strap.alarmsChanged.collect { refresh() } }
        // A setting changed on the strap: apply it as a fresh read would (03 §3.6.1).
        viewModelScope.launch {
            strap.settingsChanged.collect { pushed ->
                _state.update { s -> s.health?.updatedWith(pushed)?.let { s.copy(health = it) } ?: s }
            }
        }
    }

    /** A refresh asked for while a write or read was running; it runs when that one ends, so it isn't lost. */
    private var refreshPending = false

    fun refresh() {
        // A retry after a failed first read goes back to "reading"; a refresh of a shown list keeps showing it.
        _state.update { if (it.read is AlarmsRead.Failed) it.copy(read = AlarmsRead.Loading) else it }
        run { }
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) = run { strap.saveAlarm(alarm.copy(enabled = enabled), isNew = false) }

    /** Completes with true when the strap took the change, false when it failed or was busy (the screen keeps the sheet then). */
    fun save(alarm: Alarm, isNew: Boolean): Deferred<Boolean> = run { strap.saveAlarm(alarm, isNew) }

    fun delete(alarm: Alarm): Deferred<Boolean> = run { strap.deleteAlarm(alarm.index) }

    fun deleteAll() = run { strap.clearAlarms() }

    /** The lowest slot the strap isn't using, as Zepp picks it. */
    fun freeIndex(): Int? {
        val s = _state.value
        val used = s.alarms.map { it.index }.toSet()
        return (0 until (s.caps?.maxCount ?: return null)).firstOrNull { it !in used }
    }

    /**
     * Sets how often the strap measures HR. Like Zepp, continuous also turns on HR during activity, and a
     * rate too slow for alerts switches the HR alerts off.
     */
    fun setHrInterval(value: Int) = writeHealth { g ->
        buildList {
            add(SettingsCodec.enumSetting(SettingsCodec.KEY_HR_INTERVAL, value))
            if (value == SettingsCodec.HR_CONTINUOUS && g[SettingsCodec.KEY_ACTIVITY_HR] != null) add(SettingsCodec.boolSetting(SettingsCodec.KEY_ACTIVITY_HR, true))
            if (value != SettingsCodec.HR_CONTINUOUS && value != 1) {
                for (key in listOf(SettingsCodec.KEY_HIGH_HR_ALERT, SettingsCodec.KEY_LOW_HR_ALERT)) {
                    if ((g[key]?.number ?: 0L) != 0L) add(SettingsCodec.enumSetting(key, 0))
                }
            }
        }
    }

    fun setEnum(key: Int, value: Int) = writeHealth { listOf(SettingsCodec.enumSetting(key, value)) }

    fun setAutoOxygen(on: Boolean) = writeHealth { listOf(SettingsCodec.boolSetting(SettingsCodec.KEY_SMART_SPO2, on)) }

    private fun writeHealth(build: (SettingsGroup) -> List<Setting>) = run {
        val g = _state.value.health ?: throw StrapCommandException(Strings.app.get(R.string.you_alarms_not_loaded))
        strap.writeHealthSettings(g.version, build(g))
    }

    /**
     * Runs [action] against the strap, then re-reads everything so the screen shows what the strap holds. Completes with
     * whether [action] worked. While another run is going, a refresh (an [action] that does nothing) is queued to run
     * after it, and a write is refused (false) so the caller doesn't close its sheet as if it had been saved.
     */
    private fun run(action: suspend () -> Unit): Deferred<Boolean> {
        if (_state.value.busy) {
            // Only a plain refresh can wait: a write the user asked for is shown as not done, and the controls are disabled anyway.
            refreshPending = true
            return CompletableDeferred(false)
        }
        _state.update { it.copy(busy = true, error = null) }
        return viewModelScope.async {
            val error = try {
                action()
                null
            } catch (e: StrapCommandException) {
                e.message
            }
            readAll(error)
            val again = refreshPending
            refreshPending = false
            if (again) {
                _state.update { it.copy(busy = true) }
                readAll(null)
            }
            error == null
        }
    }

    /**
     * Re-reads alarms and health from the strap, each on its own so one failing doesn't hide the other. [error] is the
     * failed write's reason, if any; a read failure is kept apart from it ([AlarmsUiState.alarmsError], [AlarmsUiState.healthError]).
     */
    private suspend fun readAll(error: String?) {
        // The raw failure goes to Diagnostics, and the screen says it in plain words ([ErrorCopy.strap]).
        val alarmsRead = catching {
            val (caps, alarms) = strap.readAlarms()
            caps to alarms.sortedWith(compareBy({ it.hour }, { it.minute }))
        }.onFailure { (strap as? StrapManager)?.log("alarms: read from the strap failed: $it") }
        val healthRead = catching { strap.readHealthSettings() }
            .onFailure { (strap as? StrapManager)?.log("alarms: health settings read from the strap failed: $it") }
        _state.update { s ->
            val withAlarms = alarmsRead.fold(
                { (caps, alarms) -> s.copy(read = AlarmsRead.Loaded, caps = caps, alarms = alarms, alarmsError = null) },
                { e ->
                    val failed = ErrorCopy.strap(e, Strings.app.get(R.string.you_alarms_read_failed))
                    // Without an earlier good read there is nothing to show; with one, keep showing it beside the error.
                    s.copy(read = if (s.read is AlarmsRead.Loaded) s.read else AlarmsRead.Failed(failed), alarmsError = failed)
                },
            )
            // A failed re-read keeps the last good settings.
            healthRead.fold(
                { health -> withAlarms.copy(health = health, healthError = null) },
                { e -> withAlarms.copy(healthError = ErrorCopy.strap(e, Strings.app.get(R.string.you_alarms_read_failed))) },
            ).copy(busy = false, error = error)
        }
    }
}
