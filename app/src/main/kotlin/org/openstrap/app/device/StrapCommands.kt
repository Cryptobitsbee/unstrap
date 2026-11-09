package org.openstrap.app.device

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.R
import org.openstrap.app.data.catching
import org.openstrap.app.i18n.Strings
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.AlarmCodec
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup
import org.openstrap.protocol.services.SettingsProtocol
import org.openstrap.protocol.services.SportSession
import org.openstrap.protocol.toHex
import org.openstrap.protocol.u8
import java.util.Locale

/**
 * Request and reply commands on the connected session: alarms, settings, restart and the sport module. Each throws
 * [StrapCommandException] when not connected, during a firmware update, or when the strap refuses.
 */
// One function per strap command StrapController offers.
@Suppress("TooManyFunctions")
internal class StrapCommands(
    private val link: LiveLink,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val log: (String) -> Unit,
) {
    /** Sends a settings-style command and returns the reply whose first byte is the command + 1 (or that [matches]). */
    // Each throw is a reason the user is shown.
    @Suppress("ThrowsCount")
    suspend fun command(
        endpoint: Int,
        request: ByteArray,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        matches: (ByteArray) -> Boolean = { it.isNotEmpty() && it.u8(0) == request.u8(0) + 1 },
    ): ByteArray {
        val s = link.activeSession ?: throw StrapCommandException(R.string.system_command_not_connected)
        if (firmwareUpdating.value) throw StrapCommandException(R.string.system_command_firmware_updating)
        val reply = catching {
            s.request(endpoint, request, timeoutMs, matches = matches)
        }.getOrElse { throw StrapCommandException(R.string.system_command_no_answer) }
        log("cmd %04x > %s < %s".format(Locale.ROOT, endpoint, request.toHex(), reply.toHex()))
        return reply
    }

    /** The alarm module's version for the connection it was read on: Zepp's `sq0.l`, asked again after a reconnect. */
    private var alarmCaps: Pair<ChannelSession, AlarmCapabilities>? = null

    private suspend fun alarmCaps(): AlarmCapabilities {
        val s = link.activeSession
        alarmCaps?.takeIf { it.first === s }?.let { return it.second }
        val caps = AlarmCodec.parseVersion(command(Endpoints.ALARMS, AlarmCodec.versionRequest))
            ?: throw StrapCommandException(R.string.system_command_alarm_reply)
        if (s != null) alarmCaps = s to caps
        return caps
    }

    suspend fun readAlarms(): Pair<AlarmCapabilities, List<Alarm>> {
        val caps = alarmCaps()
        val list = AlarmCodec.parseList(command(Endpoints.ALARMS, AlarmCodec.listRequest), caps.titles)
            ?: throw StrapCommandException(R.string.system_command_alarm_list)
        return caps to list
    }

    suspend fun saveAlarm(alarm: Alarm, isNew: Boolean) {
        val caps = alarmCaps()
        val request = if (isNew) {
            AlarmCodec.add(alarm, caps.titles, caps.workdaysSupported)
        } else {
            AlarmCodec.modify(alarm, caps.titles, caps.workdaysSupported)
        }
        alarmCommand(request, R.string.system_command_alarm_not_saved)
    }

    suspend fun deleteAlarm(index: Int) = alarmCommand(AlarmCodec.delete(index), R.string.system_command_alarm_not_deleted)

    suspend fun clearAlarms() = alarmCommand(AlarmCodec.clearAllRequest, R.string.system_command_alarms_not_deleted)

    private suspend fun alarmCommand(request: ByteArray, @StringRes refused: Int) {
        if (!AlarmCodec.isSuccess(request, command(Endpoints.ALARMS, request))) throw StrapCommandException(refused)
    }

    /** Learned on connect. Null until then or when the strap didn't answer (then replies are read as v1 to v3). */
    @Volatile var settingsProtocol: SettingsProtocol? = null

    suspend fun settingsProtocol(): SettingsProtocol? = settingsProtocol
        ?: catching { SettingsCodec.parseVersion(command(Endpoints.SETTINGS, SettingsCodec.versionRequest)) }.getOrNull()
            ?.also { settingsProtocol = it }

    /** A settings query; a `04` push for another group arriving meanwhile isn't taken as its reply (03 §3.6.1). */
    suspend fun settingsQuery(group: Int, keys: List<Int> = emptyList(), withOptions: Boolean = true): ByteArray =
        command(Endpoints.SETTINGS, SettingsCodec.query(group, keys, withOptions)) { SettingsCodec.isReplyTo(group, it) }

    suspend fun readHealthSettings(): SettingsGroup {
        val protocol = settingsProtocol()
        val reply = settingsQuery(SettingsCodec.GROUP_HEALTH)
        return SettingsCodec.parse(reply, protocol?.version)
            ?: throw StrapCommandException(
                Strings.app.format(R.string.system_command_settings_unread_code, "${SettingsCodec.replyCode(reply)}"),
            )
    }

    suspend fun writeHealthSettings(version: Int, settings: List<Setting>) {
        val reply = command(Endpoints.SETTINGS, SettingsCodec.write(SettingsCodec.GROUP_HEALTH, version, settings))
        if (!SettingsCodec.isWriteSuccess(reply)) {
            throw StrapCommandException(Strings.app.format(R.string.system_command_setting_refused, "${reply.getOrNull(1)}"))
        }
    }

    /** Writes GENERAL key 45 = true with the group version just read (07 §0). The strap may drop the link before its ack. */
    // Each throw is a reason the user is shown.
    @Suppress("ThrowsCount")
    suspend fun restartStrap() {
        val query = settingsQuery(SettingsCodec.GROUP_GENERAL, listOf(SettingsCodec.KEY_RESTART), withOptions = false)
        val group = SettingsCodec.parse(query, settingsProtocol()?.version)
            ?: throw StrapCommandException(R.string.system_command_settings_unread)
        val request = SettingsCodec.restartFor(group) ?: throw StrapCommandException(R.string.system_command_no_restart)
        log("restart: asking the strap to restart")
        val reply = catching { command(Endpoints.SETTINGS, request) }
            .getOrElse { e -> if (link.activeSession == null) { log("restart: link dropped before the ack"); return } else throw e }
        if (!SettingsCodec.isWriteSuccess(reply)) {
            throw StrapCommandException(Strings.app.format(R.string.system_command_restart_refused, "${reply.getOrNull(1)}"))
        }
    }

    /**
     * GENERAL key 47, null when the strap doesn't report it. Zepp refuses OTA while it is on (07 §1.6). Asked straight
     * on the session, as the firmware install has set firmwareUpdating by then and [command] refuses while it is set.
     */
    suspend fun fastRechargeOn(s: ChannelSession): Boolean? = catching {
        val group = SettingsCodec.GROUP_GENERAL
        val request = SettingsCodec.query(group, listOf(SettingsCodec.KEY_FAST_RECHARGE), withOptions = false)
        val reply = s.request(Endpoints.SETTINGS, request, COMMAND_TIMEOUT_MS) { SettingsCodec.isReplyTo(group, it) }
        SettingsCodec.parse(reply, settingsProtocol?.version)?.get(SettingsCodec.KEY_FAST_RECHARGE)?.bool
    }.onFailure { log("fast-charge query failed: $it") }.getOrNull()

    /**
     * The sport module's version for the session it was read on. Asked once per connection, under [sportVersionLock],
     * so a workout command that comes first waits for the connect's `01` and the version is never asked twice.
     */
    private var sportCaps: Pair<ChannelSession, SportSession.Capabilities>? = null
    private val sportVersionLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun sportCaps(s: ChannelSession): SportSession.Capabilities? = sportVersionLock.withLock {
        sportCaps?.takeIf { it.first === s }?.second ?: catching {
            val r = s.request(SportSession.ENDPOINT, SportSession.versionRequest, COMMAND_TIMEOUT_MS) {
                SportSession.isReplyTo(SportSession.versionRequest, it)
            }
            log("sport version ${r.toHex()}")
            SportSession.parseVersion(r)
        }.onFailure { log("sport version not read: $it") }.getOrNull()?.also { sportCaps = s to it }
    }

    /** Zepp's `wok.s0` on every connect: 0x0019 `01`, kept for the workout commands. A failure is only logged. */
    suspend fun querySportVersion(s: ChannelSession) {
        if (s.services.has(SportSession.ENDPOINT)) sportCaps(s)
    }

    /** 0x0019 `06 00`, the strap's workout status; null when it doesn't answer or refuses. */
    suspend fun sportStatus(): SportSession.DeviceStatus? {
        val s = link.activeSession?.takeIf { it.services.has(SportSession.ENDPOINT) } ?: return null
        return catching {
            val r = s.request(SportSession.ENDPOINT, SportSession.statusQuery, COMMAND_TIMEOUT_MS) {
                SportSession.isReplyTo(SportSession.statusQuery, it)
            }
            log("sport status ${r.toHex()}")
            SportSession.parseStatus(r)
        }.onFailure { log("sport status not read: $it") }.getOrNull()
    }

    // Each throw is a reason the user is shown.
    @Suppress("ThrowsCount")
    suspend fun sportSession(state: SportSession.State, sportType: Int, startTime: Long, elapsedSeconds: Long, reconnect: Boolean) {
        val s = link.activeSession ?: throw StrapCommandException(R.string.system_command_not_connected)
        if (!s.services.has(SportSession.ENDPOINT)) throw StrapCommandException(R.string.system_command_no_sport_module)
        suspend fun send(request: ByteArray): ByteArray {
            val reply = catching { s.request(SportSession.ENDPOINT, request, COMMAND_TIMEOUT_MS) { SportSession.isReplyTo(request, it) } }
                .getOrElse { throw StrapCommandException(R.string.system_command_no_answer) }
            log("sport > ${request.toHex()} < ${reply.toHex()}")
            if (!SportSession.isOk(reply)) throw StrapCommandException(R.string.system_command_workout_refused)
            return reply
        }
        val caps = sportCaps(s) ?: throw StrapCommandException(R.string.system_command_sport_version)
        // Zepp's `bo60.j`: the config before START, and before whatever state goes out again on a reconnect.
        if (state == SportSession.State.START || reconnect) send(SportSession.config(sportType, caps))
        send(SportSession.state(state, startTime, elapsedSeconds, caps))
    }

    suspend fun sportSessionKeepAlive() {
        val s = link.activeSession ?: return
        catching { s.send(SportSession.ENDPOINT, SportSession.pauseKeepAlive) }
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 8_000L
    }
}
