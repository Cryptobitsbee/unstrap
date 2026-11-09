package org.openstrap.app.device

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.R
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.DayKey
import org.openstrap.app.i18n.Strings
import org.openstrap.app.data.Days
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.ActivityExtendedMinute
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.DeletedWorkout
import org.openstrap.app.data.db.DismissedWorkout
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.RawRecord
import org.openstrap.app.data.db.RecoveryHeartRateRow
import org.openstrap.app.data.db.SleepSpo2Sample
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.history.ActivityLayout
import org.openstrap.protocol.history.ActivitySample
import org.openstrap.protocol.history.DailyMaxHeartRate
import org.openstrap.protocol.history.OsaResult
import org.openstrap.protocol.history.RecoveryHeartRate
import org.openstrap.protocol.history.SleepRespiratoryEvent
import org.openstrap.protocol.history.SleepSpO2Sample
import org.openstrap.protocol.history.SpO2OsaEvent
import org.openstrap.protocol.history.TodayActivityExtended
import org.openstrap.protocol.history.FetchCodec
import org.openstrap.protocol.history.GdspClient
import org.openstrap.protocol.history.GdspFetch
import org.openstrap.protocol.history.PreviousChunk
import org.openstrap.protocol.history.GdspMessage
import org.openstrap.protocol.history.GdspStatus
import org.openstrap.protocol.history.WorkoutFetch
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.HistoryRecord
import org.openstrap.protocol.history.HistoryTypeSpec
import org.openstrap.protocol.history.HistoryTypeSpecs
import org.openstrap.protocol.history.HrvRmssd
import org.openstrap.protocol.history.HrvSdnn
import org.openstrap.protocol.history.SportEffect
import org.openstrap.protocol.history.WorkoutDetail
import org.openstrap.protocol.history.WorkoutSummary
import org.openstrap.protocol.history.PaiRecord
import org.openstrap.protocol.history.ParseContext
import org.openstrap.protocol.history.ParseResult
import org.openstrap.protocol.history.RestHrRealtime
import org.openstrap.protocol.history.SecondHeartRate
import org.openstrap.protocol.history.SkinTemperatureSample
import org.openstrap.protocol.history.SleepBreathRate
import org.openstrap.protocol.history.SleepResult
import org.openstrap.protocol.history.SleepStageCodes
import org.openstrap.protocol.history.SleepStageType
import org.openstrap.protocol.history.HeartRateSentinel
import org.openstrap.protocol.history.SpO2Reading
import org.openstrap.protocol.history.SpotHeartRate
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.ReadinessCodec
import org.openstrap.protocol.services.PaiCodec
import org.openstrap.protocol.u8
import org.openstrap.protocol.toHex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.openstrap.app.data.db.StressSample as DbStress
import org.openstrap.protocol.history.RestingHeartRate as StrapRestingHr
import org.openstrap.protocol.history.StressSample as StrapStress

/**
 * Pulls history from the strap into Room using the GDSP fetch protocol (docs/protocol/02).
 * Control runs over channel endpoint 0x004B when listed, otherwise HUAMI(0004); data always
 * arrives on HUAMI(0005). Each type keeps its own cursor so an interrupted sync resumes.
 */
class HistorySync internal constructor(
    private val session: ChannelSession,
    private val gatt: HistoryGatt,
    private val dao: HealthDao,
    private val cursors: SyncCursors,
    private val state: MutableStateFlow<SyncState>,
    private val log: (String) -> Unit,
    /** Called for a newly synced workout the strap auto-detected, to notify about it (codemap 05 §8). */
    private val onAutoDetected: (Workout) -> Unit = {},
) {
    constructor(
        session: ChannelSession,
        gatt: GattClient,
        dao: HealthDao,
        settings: SettingsStore,
        state: MutableStateFlow<SyncState>,
        log: (String) -> Unit,
        onAutoDetected: (Workout) -> Unit = {},
    ) : this(session, gatt.asHistoryGatt(), dao, settings.asSyncCursors(), state, log, onAutoDetected)

    private val zone = ZoneId.systemDefault()
    private val useChannel = session.services.has(Endpoints.HISTORY)
    private val control = Channel<GdspMessage>(Channel.UNLIMITED)
    private val data = Channel<ByteArray>(Channel.UNLIMITED)
    private val client = GdspClient(::send, control, data, log)
    private val channelJobs = ZeppChannelJobs(session, dao, cursors, zone, log) { dao.inTransaction { store(it) } }

    /** Syncs every type, or only the codes in [only] (an on-demand sync after a data-available push). */
    suspend fun run(only: Set<Int>? = null) = coroutineScope {
        state.value = SyncState.Running(Strings.app.get(R.string.system_sync_preparing))
        val listeners = launch {
            launch { gatt.notifications.filter { it.characteristic == HISTORY_DATA }.collect { data.send(it.value) } }
            if (useChannel) {
                launch { session.messagesFor(Endpoints.HISTORY).collect { log("gdsp < ${it.toHex()}"); control.send(FetchCodec.parse(it)) } }
            } else {
                launch { gatt.notifications.filter { it.characteristic == HISTORY_CONTROL }.collect { control.send(FetchCodec.parse(it.value)) } }
            }
        }
        // catching gives back a CancellationException only once the sync is cancelled: a timeout is one too, and fails it.
        try {
            catching { sync(only) }.onFailure { e ->
                run {
                    log("history failed: $e")
                    // A wrapped system error's message is just its class name (android.os.DeadObjectException), not for
                    // the card. Whatever a call on a dropped link threw (a missing characteristic, a closed op), the cause
                    // is the link.
                    val reason = if (!gatt.connected.value) Strings.app.get(R.string.system_sync_link_dropped)
                    else e.message?.takeUnless { e.cause != null && it == e.cause.toString() }
                    state.value = SyncState.Failed(reason ?: Strings.app.get(R.string.system_sync_failed))
                }
            }
        } finally {
            listeners.cancel()
            // A cancelled sync, or anything else that got past the catches, must not stay Running: auto sync skips
            // while it is, and the notification would show progress for good.
            if (state.value is SyncState.Running) state.value = SyncState.Failed(Strings.app.get(R.string.system_sync_stopped))
        }
    }

    /** The sync itself, for [run], which turns what it throws into a Failed state. */
    private suspend fun sync(only: Set<Int>?) {
        // Inside [run]'s catch: a database error here ends the sync as Failed instead of escaping the app's scope.
        repairEarlierSyncs()
        migrateCursorsOnce()
        gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_DATA)
        if (!useChannel) gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_CONTROL)
        log("history: control via ${if (useChannel) "channel 0x004B" else "HUAMI(0004)"}")

        // Zepp's loader and job gates, before the first job: the sleep module once per strap, then the PAI module's,
        // the GDSP and the readiness module's versions once per connection.
        val sleepPlan = channelJobs.sleepPlanSupported()
        val paiVersion = channelJobs.paiVersion()
        val version = versionFor(session)
        val advertised = version?.supportedTypes()
        val readinessVersion = channelJobs.readinessVersion()
        log("history version ${version?.version}, strap offers types ${advertised?.sorted()}" + only?.let { ", syncing $it" }.orEmpty())

        val gates = ZeppJobs.Gates(
            advertised, session.features, session.services::has, readinessVersion, paiVersion, sleepPlan, statisticsDue(),
        )
        val all = ZeppJobs.list(gates)
        val jobs = if (only == null) all else ZeppJobs.only(all, only)
        log("history jobs: ${jobs.joinToString { jobLabel(it) }}")
        // Zepp runs every job and counts the ones that failed; the sync then ends with an error.
        var failedTypes = 0
        jobs.forEachIndexed { i, job ->
            val step = Strings.app.format(R.string.system_sync_step, jobName(job), i + 1, jobs.size)
            state.value = SyncState.Running(step, i.toFloat() / jobs.size)
            val ok = catching {
                when (job) {
                    is ZeppJob.Gdsp ->
                        if (job.spec.followedBy == HistoryDataType.SPORT_DETAIL) syncWorkouts(job.spec) else syncType(job.spec)
                    is ZeppJob.Readiness -> channelJobs.readiness(job.op)
                    ZeppJob.Pai -> channelJobs.pai()
                }
            }.getOrElse { e ->
                // A timeout is a CancellationException too, but catching gives back only a cancelled sync's (audit C2).
                // The link is gone: every later type would fail the same way, and the sync must not end as Done.
                if (!gatt.connected.value) throw e
                log("history ${jobLabel(job)}: $e")
                false
            }
            // Zepp ignores the breath events' result (`stk`).
            val ignored = job is ZeppJob.Gdsp && job.spec.type == HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT
            if (!ok && !ignored) failedTypes++
        }
        // A session the strap ran alongside a phone workout has just come in: its figures replace the model's.
        catching {
            org.openstrap.app.data.WorkoutEffectEngine(dao)
                .takeStrapEffects(System.currentTimeMillis() / 1000 - WORKOUT_LOOKBACK, WorkoutSession.LOCK)
        }
            .onFailure { log("strap effect: $it") }
        // Sleep deleted over a week ago goes for good, and a copy the strap just sent again with it.
        catching { org.openstrap.app.data.SleepSource(dao).purgeExpired(System.currentTimeMillis() / 1000) }
            .onSuccess { if (it > 0) log("history: removed $it sleep sessions deleted over a week ago") }
            .onFailure { log("deleted sleep: $it") }
        state.value = when {
            fastRecharge -> SyncState.Failed(Strings.app.get(R.string.system_sync_fast_charge))
            failedTypes > 0 -> {
                log("history: $failedTypes types failed")
                SyncState.Failed(Strings.app.get(R.string.system_sync_failed))
            }
            else -> SyncState.Done(System.currentTimeMillis() / 1000)
        }
    }

    /**
     * The GDSP version reply, asked once per connection as Zepp does (`vwi.b`): kept only when it succeeded, so a
     * failed query is asked again at the next sync.
     */
    private suspend fun versionFor(s: ChannelSession): GdspMessage.VersionReply? {
        synchronized(versions) { versions[s] }?.let { return it }
        val reply = client.queryVersion()?.takeIf { it.isSuccess }
        if (reply != null) synchronized(versions) { versions[s] = reply }
        return reply
    }

    /** The strap answered a START with fast-recharge mode: Zepp then starts no job until it ends (cached state). */
    private var fastRecharge = false

    /**
     * Debug aid: fetches every type the strap offers since [fromEpoch] and writes each chunk's payload to
     * [dir] as `<code>_<headerTime>.bin`, without storing records or moving sync cursors.
     */
    suspend fun dumpRaw(fromEpoch: Long, dir: java.io.File, only: Set<Int>? = null) = coroutineScope {
        if (only == null) dir.deleteRecursively()
        dir.mkdirs()
        val listeners = launch {
            launch { gatt.notifications.filter { it.characteristic == HISTORY_DATA }.collect { data.send(it.value) } }
            if (useChannel) launch { session.messagesFor(Endpoints.HISTORY).collect { log("dump gdsp < ${it.toHex()}"); control.send(FetchCodec.parse(it)) } }
            else launch { gatt.notifications.filter { it.characteristic == HISTORY_CONTROL }.collect { control.send(FetchCodec.parse(it.value)) } }
        }
        try {
            gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_DATA)
            if (!useChannel) gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_CONTROL)
            val version = versionFor(session)
            val codes = only?.sorted() ?: version?.supportedTypes()?.sorted().orEmpty()
            log("dump: types $codes (strap offers ${version?.supportedTypes()?.sorted()})")
            for (code in codes) {
                state.value = SyncState.Running(Strings.app.format(R.string.system_sync_dumping, code))
                catching {
                    var cursor = fromEpoch
                    repeat(DUMP_CHUNKS) {
                        val chunk = when (val f = client.fetch(code, cursor, zone)) {
                            is GdspFetch.Chunk -> f
                            is GdspFetch.Failed -> { log("dump $code: ${f.reason}"); return@catching }
                            GdspFetch.Empty -> { log("dump $code: empty"); return@catching }
                        }
                        val header = chunk.headerTime
                        val payload = chunk.payload
                        java.io.File(dir, "${code}_$header.bin").writeBytes(payload)
                        log("dump $code: ${payload.size} bytes, size field ${chunk.sizeField}, from ${Instant.ofEpochSecond(header)}")
                        val spec = HistoryTypeSpecs.forCode(code)
                        val parsed = spec?.parser?.parse(payload, ParseContext(header, activityRecordSize = recordSize(code, payload, chunk.sizeField)))
                        val next = (parsed as? ParseResult.Success)?.nextCursor
                        if (next == null || next <= cursor || spec.loops == false) return@catching
                        cursor = next
                    }
                }.onFailure { log("dump $code: $it") }
            }
            state.value = SyncState.Done(System.currentTimeMillis() / 1000)
            log("dump: done")
        } finally {
            listeners.cancel()
        }
    }

    private suspend fun send(bytes: ByteArray) {
        if (useChannel) session.send(Endpoints.HISTORY, bytes)
        else gatt.write(StrapUuids.MAIN_SERVICE, HISTORY_CONTROL, bytes, withResponse = true)
    }

    /**
     * Undo damage from earlier builds: 0xFF padding stored as 255-step minutes, minutes dated before any strap clock
     * was set, and sport effects left with no workout to own them. Cursors are left alone: Zepp corrects them as
     * they are read ([ZeppCursors.onRead]).
     *
     * Activity dated after now is kept. The stale slots dated weeks ahead that builds before 16 Oct stored are gone,
     * and [keepable] keeps new ones out, so a minute after now means the phone's clock went back, and deleting it
     * lost that day's activity.
     */
    private suspend fun repairEarlierSyncs() {
        val now = System.currentTimeMillis() / 1000
        val padding = dao.deletePaddingActivity()
        val outside = dao.deleteActivityOutside(MIN_VALID_EPOCH, Long.MAX_VALUE)
        if (padding + outside > 0) log("history: removed $padding padding and $outside misdated activity rows")
        // Earlier builds stored the whole mode byte; the mode is its low nibble.
        val modes = dao.fixActivityModeNibble()
        if (modes > 0) log("history: fixed the mode of $modes activity rows")
        val gaps = dao.closeSleepStageGaps()
        if (gaps > 0) log("history: closed $gaps one-minute gaps after sleep stages")
        dao.deleteNapStagesInsideMainSleep()
        val naps = dao.deleteNapsInsideMainSleep()
        if (naps > 0) log("history: removed $naps naps inside a main sleep")
        removeUnownedEffects(now)
    }

    /**
     * Once, the cursors stored by builds before Zepp's rules move to them: second HR v2 (87) now reads and writes
     * 76's cursor, and workout detail (6) is written with the summary's (5).
     */
    private suspend fun migrateCursorsOnce() {
        if (cursors.get(CURSORS_ZEPP_KEY) != null) return
        listOfNotNull(cursors.get(keyOf(HistoryDataType.SEC_HR)), cursors.get(keyOf(HistoryDataType.SEC_HR_V2))).maxOrNull()
            ?.let { cursors.set(keyOf(HistoryDataType.SEC_HR), it) }
        cursors.get(keyOf(HistoryDataType.SPORT_SUMMARY))?.let { cursors.set(keyOf(HistoryDataType.SPORT_DETAIL), it) }
        cursors.set(CURSORS_ZEPP_KEY, System.currentTimeMillis() / 1000)
        log("history: cursors moved to Zepp's rules")
    }

    /** Effects stored before a sync left them out ([unownedEffects]) go, remembered as a sync would. */
    private suspend fun removeUnownedEffects(now: Long) {
        val leftover = unownedEffects(dao.sportEffectsOnce(MIN_VALID_EPOCH, Long.MAX_VALUE).map { it.end }, now)
        if (leftover.isEmpty()) return
        dao.inTransaction {
            leftover.forEach { dao.deleteSportEffect(it) }
            dao.insertDeletedWorkouts(leftover.map { effectMarker(it, now) })
        }
        log("history: removed ${leftover.size} sport effects over no workout")
    }

    /**
     * The ends of the sport effects in [ends] that no workout owns. The strap sends an effect for a phone workout ended
     * under a minute, or one discarded, but no workout record, and the phone has already deleted that workout and
     * remembered its time ([DismissedWorkout]). Kept, the effect waits for a later strap workout with none of its own
     * to adopt it, which takes its end and recovery time. So an effect that ends inside a dismissed time, or up to
     * [EFFECT_GRACE] after it, goes, unless a strap workout or a phone workout runs to it.
     */
    private suspend fun unownedEffects(ends: List<Long>, now: Long): Set<Long> {
        if (ends.isEmpty()) return emptySet()
        val dismissed = dao.dismissedWorkoutsAll()
        return ends.filter { inDismissedTime(it, dismissed) }.filterTo(HashSet()) { !ownedEffect(it, workoutsBefore(it, now)) }
    }

    /** The times of the strap and phone workouts that could own an effect ending at [end], each to its real end. */
    private suspend fun workoutsBefore(end: Long, now: Long): List<LongRange> {
        val from = end - Workouts.EFFECT_WINDOW_S
        val strap = dao.workoutsOnce(from, end).map { w ->
            w.start..Workouts.endOf(w, dao.workoutHeartRateOnce(w.start).lastOrNull()?.timestamp, null)
        }
        return strap + dao.appWorkoutsOnce(from, end).map { Workouts.phoneRange(it, now) }
    }

    /** A type's stored cursor as Zepp reads it: corrected, or its default when there is none ([ZeppCursors]). */
    private suspend fun read(type: HistoryDataType, now: Long): Long = ZeppCursors.read(cursors, keyOf(type), now, zone)

    private suspend fun startOf(type: HistoryDataType, now: Long): Long = when {
        // Statistics ask from now. A sleep plan with none stored falls back to the bind time, as Zepp's job does
        // with no cursor: the first minute stored stands in for it.
        type == HistoryDataType.DEVICE_STATISTIC -> now
        type == HistoryDataType.SLEEP_PLAN && cursors.get(keyOf(type)) == null -> dao.firstActivityAfter(MIN_VALID_EPOCH) ?: now
        else -> {
            val stored = HashMap<HistoryDataType, Long>()
            for (t in ZEPP_READS[type] ?: listOf(type)) stored[t] = read(t, now)
            ZeppCursors.start(type, now, zone) { stored.getValue(it) }
        }
    }

    private suspend fun save(type: HistoryDataType, value: Long, now: Long) =
        cursors.set(keyOf(ZeppCursors.keyType(type)), ZeppCursors.onWrite(value, now))

    /**
     * Whether the strap workout [row] is the strap's copy ([Workouts.sameWorkout]) of a time marked as no workout: a
     * phone workout discarded or ended under a minute, a strap workout deleted, or a suggestion turned down.
     */
    private suspend fun discardedPhoneWorkout(row: Workout): Boolean =
        dao.dismissedWorkoutsAll().any { Workouts.sameWorkout(row.start..row.end, it.start..it.end) }

    /**
     * One type, as Zepp's `l59` loop: chunks from the type's start ([ZeppCursors.start]) while the next cursor moves
     * on, then the records stored together and the cursor written once ([ZeppCursors.saved]). False when it failed
     * with nothing fetched, which fails the sync.
     */
    // Each break ends the type for a reason of its own.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
    private suspend fun syncType(spec: HistoryTypeSpec): Boolean {
        val now = System.currentTimeMillis() / 1000
        val start = startOf(spec.type, now)
        var cursor = start
        // Zepp's `l59.f`: up to 3 failed exchanges per type in a sync (1 for 79 and 96), each repeating the same cursor,
        // and no cap on chunks while the cursor moves on. The ACK has gone out before anything here is parsed.
        var retries = if (spec.code in SINGLE_TRY) 1 else RETRIES
        var previous: PreviousChunk? = null
        val got = mutableListOf<HistoryRecord>()
        var next: Long? = null
        var ok = true
        loop@ while (true) {
            check(gatt.connected.value) { Strings.app.get(R.string.system_sync_link_dropped) }
            if (fastRecharge) { ok = got.isNotEmpty(); break }
            val chunk = when (val f = client.fetch(spec.code, cursor, zone, previous)) {
                is GdspFetch.Chunk -> f
                GdspFetch.Empty -> break@loop
                is GdspFetch.Failed -> {
                    log("history ${spec.type}: ${f.reason}")
                    if (f.fastRecharge) fastRecharge = true
                    if (f.cyclic || --retries <= 0 || fastRecharge) { ok = got.isNotEmpty(); break@loop }
                    continue@loop
                }
            }
            val headerTime = chunk.headerTime
            val payload = chunk.payload
            // The size field counts records, not bytes (activity: size 4 = four 8-byte minutes).
            val unit = chunk.unit ?: run {
                log("history ${spec.type}: got ${payload.size} bytes for size ${chunk.sizeField}, not read")
                break@loop
            }
            log(
                "history ${spec.type}: ${payload.size} bytes (${chunk.sizeField}×$unit) from ${Instant.ofEpochSecond(headerTime)}: " +
                    payload.copyOf(minOf(32, payload.size)).toHex(),
            )
            val context = ParseContext(headerTime, activityRecordSize = recordSize(spec.code, payload, chunk.sizeField))
            val result = spec.parser!!.parse(payload, context)
            // Zepp parses after the ACK and tells the strap nothing of a parse failure.
            if (result is ParseResult.Failure) {
                log("history ${spec.type}: parse failed: ${result.reason}")
                break@loop
            }
            val records = (result as ParseResult.Success).records
            log("history ${spec.type}: ${records.size} records")
            got += records
            val following = ZeppCursors.next(spec.type, headerTime, records, result.nextCursor)
            if (following != null) next = following
            if (!HistoryTypeSpecs.shouldContinue(spec, cursor, following)) break@loop
            previous = PreviousChunk(cursor, headerTime)
            cursor = following ?: break@loop
        }
        if (got.isNotEmpty()) dataOut(spec.type, got, next, start)
        if (spec.type == HistoryDataType.DEVICE_STATISTIC && ok) save(spec.type, now, now)
        return ok
    }

    /**
     * What Zepp's handler does with a type's records once the job ends: stores them, then writes the cursor (recovery
     * HR writes it first). Records with impossible times are not stored, as before.
     */
    private suspend fun dataOut(type: HistoryDataType, received: List<HistoryRecord>, next: Long?, start: Long) {
        val now = System.currentTimeMillis() / 1000
        val records = if (type == HistoryDataType.ACTIVITY) ZeppCursors.activityKept(received, now) else received
        val saved = ZeppCursors.saved(type, records, next)
        if (saved != null && type == HistoryDataType.RECOVERY_HEART_RATE) save(type, saved, now)
        val kept = storable(type, records, now)
        val dropped = records.count { !(it is ActivitySample && it.isBlank) } - kept.size
        log("history $type: storing ${kept.size} records" + if (dropped > 0) " ($dropped with impossible times dropped)" else "")
        // The minute the second HR started in was stored partly by the last sync: it stays as it was.
        val partMinute = if (type == HistoryDataType.SEC_HR || type == HistoryDataType.SEC_HR_V2) start / 60 * 60 else null
        dao.inTransaction { store(kept, partMinute) }
        if (saved == null || type == HistoryDataType.RECOVERY_HEART_RATE) return
        // Zepp writes the sleep result's cursor only when it moved (`upg0`).
        if (type == HistoryDataType.SLEEP_RESULT && cursors.get(keyOf(type)) == saved) return
        save(type, saved, now)
    }

    /** Device statistics (44) run at most once a day. */
    private suspend fun statisticsDue(): Boolean {
        val last = cursors.get(keyOf(HistoryDataType.DEVICE_STATISTIC)) ?: return true
        return Days.start(last, zone) < Days.start(System.currentTimeMillis() / 1000, zone)
    }

    /** A job as the log names it: the GDSP type, or the channel endpoint and opcode. */
    private fun jobLabel(job: ZeppJob) = when (job) {
        is ZeppJob.Gdsp -> job.spec.type.name
        is ZeppJob.Readiness -> "%04x %02x".format(java.util.Locale.ROOT, Endpoints.READINESS, job.op)
        ZeppJob.Pai -> "%04x %02x".format(java.util.Locale.ROOT, Endpoints.PAI, PaiCodec.QUERY)
    }

    private fun jobName(job: ZeppJob) = when (job) {
        is ZeppJob.Gdsp -> syncKindName(job.spec.type)
        is ZeppJob.Readiness -> Strings.app.get(R.string.system_sync_kind_other)
        ZeppJob.Pai -> syncKindName(HistoryDataType.PAI)
    }

    /**
     * Workouts as Zepp's `pxk`: the summary, then its detail from the same cursor (04 §3.16), the next pair from the
     * summary's header + 1 s. The pairs are stored when the loop ends, then the cursor goes to the last workout + 1 s
     * (at most now) for both 5 and 6. A failed detail fails the job and stores nothing, so the cursor stays.
     */
    // Each return or break ends the loop for a reason of its own.
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private suspend fun syncWorkouts(spec: HistoryTypeSpec): Boolean {
        var cursor = startOf(spec.type, System.currentTimeMillis() / 1000)
        val pairs = mutableListOf<WorkoutFetch.Workout>()
        while (true) {
            if (fastRecharge) return false
            when (val w = client.fetchWorkout(cursor, zone)) {
                WorkoutFetch.None -> break
                is WorkoutFetch.Failed -> {
                    log("history workout: ${w.reason}")
                    if (w.fastRecharge) fastRecharge = true
                    return false
                }
                is WorkoutFetch.Workout -> {
                    if (w.retryDetail) { log("history workout: detail failed, nothing stored"); return false }
                    pairs += w
                    if (w.nextCursor <= cursor) break
                    cursor = w.nextCursor
                }
            }
        }
        if (pairs.isEmpty()) return true
        for (w in pairs) storeWorkout(w)
        val now = System.currentTimeMillis() / 1000
        val last = pairs.maxOf { it.summary.trackId } + 1
        val saved = if (last > now) now else last
        save(HistoryDataType.SPORT_SUMMARY, saved, now)
        save(HistoryDataType.SPORT_DETAIL, saved, now)
        return true
    }

    private suspend fun storeWorkout(w: WorkoutFetch.Workout) {
        val now = System.currentTimeMillis() / 1000
        val deleted = dao.deletedWorkoutsAll().any { it.start == w.summary.trackId }
        val discarded = !deleted && discardedPhoneWorkout(workoutRow(w.summary))
        if (deleted) {
            log("history workout ${Instant.ofEpochSecond(w.summary.trackId)}: deleted in OpenStrap, skipped")
        } else if (discarded) {
            // The strap's copy of a phone workout discarded before it synced: deleted like one deleted here.
            log("history workout ${Instant.ofEpochSecond(w.summary.trackId)}: over a discarded workout, skipped")
            dao.insertDeletedWorkouts(listOf(DeletedWorkout(w.summary.trackId, null, now)))
        } else if (w.summary.timestamp in MIN_VALID_EPOCH..now + FUTURE_SLACK) {
            log("history workout ${Instant.ofEpochSecond(w.summary.trackId)}: type ${w.summary.sportType}, subtype ${w.summary.subtype}, ${w.detail?.heartRate?.size ?: 0} HR samples")
            val storedBefore = dao.workout(w.summary.trackId).first() != null
            dao.inTransaction { store(listOfNotNull(w.summary, w.detail)) }
            val row = workoutRow(w.summary)
            // One you started on the phone over the same time is the same workout: nothing new to tell.
            val yours = Workouts.phoneTwin(row, dao.appWorkoutsOnce(row.start, row.end), now) != null
            if (!yours && Workouts.notifyAutoDetected(row, storedBefore, now)) {
                catching { onAutoDetected(row) }.onFailure { log("auto-detect notification: $it") }
            }
        } else {
            log("history workout with impossible time ${w.summary.timestamp} dropped")
        }
    }

    private suspend fun store(received: List<HistoryRecord>, partMinute: Long? = null) {
        val deleted = if (received.any { it is WorkoutSummary || it is WorkoutDetail || it is SportEffect || it is RecoveryHeartRate }) {
            dao.deletedWorkoutsAll()
        } else emptyList()
        val kept = withoutDeletedWorkouts(received, deleted)
        if (kept.size < received.size) log("history: skipped ${received.size - kept.size} records of workouts deleted in OpenStrap")
        val now = System.currentTimeMillis() / 1000
        val unowned = unownedEffects(kept.filterIsInstance<SportEffect>().map { it.timestamp }, now)
        if (unowned.isNotEmpty()) {
            log("history: skipped ${unowned.size} sport effects over no workout")
            dao.insertDeletedWorkouts(unowned.map { effectMarker(it, now) })
        }
        val records = if (unowned.isEmpty()) kept else kept.filter { it !is SportEffect || it.timestamp !in unowned }
        val activity = mutableListOf<ActivityMinute>()
        val hr = mutableListOf<HeartRateSample>()
        val perSecond = mutableMapOf<Long, MutableList<Int>>()
        val spo2 = mutableListOf<Spo2Sample>()
        val stress = mutableListOf<DbStress>()
        val hrv = mutableListOf<HrvSample>()
        val resting = mutableListOf<RestingHeartRate>()
        val restingIfMissing = mutableListOf<RestingHeartRate>()
        val skin = mutableListOf<SkinTemperature>()
        val breath = mutableListOf<BreathRate>()
        val pai = mutableListOf<PaiSample>()
        val sdnn = mutableListOf<HrvSdnnSample>()
        val workouts = mutableListOf<Workout>()
        val effects = mutableListOf<SportEffectRow>()
        val sleepSpo2 = mutableListOf<SleepSpo2Sample>()
        val osaEvents = mutableListOf<OsaEventRow>()
        val osaResults = mutableListOf<OsaResultRow>()
        val maxHr = mutableMapOf<LocalDate, Int>()
        val recoveryHr = mutableListOf<RecoveryHeartRateRow>()
        val extended = mutableListOf<ActivityExtendedMinute>()
        val raw = mutableListOf<RawRecord>()
        for (r in records) when (r) {
            is ActivitySample -> if (!r.isBlank) activity += ActivityMinute(r.timestamp, r.steps, r.intensity, r.mode, r.heartRate)
            // Per-second HR is averaged per minute to keep the database small.
            is SecondHeartRate -> r.heartRate?.let { perSecond.getOrPut(r.timestamp / 60 * 60) { mutableListOf() } += it }
            is SpotHeartRate -> r.heartRate?.let { hr += HeartRateSample(r.timestamp, it) }
            is SpO2Reading -> if (r.isPlausible) spo2 += Spo2Sample(r.timestamp, r.spo2)
            is StrapStress -> if (!r.isLikelyGap) stress += DbStress(r.timestamp, r.stress)
            is HrvRmssd -> if (r.rmssd > 0) hrv += HrvSample(r.timestamp, r.rmssd)
            is HrvSdnn -> if (r.sdnnMs in 1.0..400.0) sdnn += HrvSdnnSample(r.timestamp, r.sdnnMs.toFloat())
            is WorkoutSummary -> workouts += workoutRow(r)
            is WorkoutDetail -> {
                // One sample per second; the strap re-sends a workout whole, so replace it.
                val samples = r.heartRate.associate { (ms, bpm) -> ms / 1000 to bpm }
                    .map { (t, bpm) -> WorkoutHeartRate(r.timestamp, t, bpm) }
                dao.replaceWorkoutDetail(r.timestamp, samples)
            }
            is SportEffect -> effects += SportEffectRow(
                r.timestamp, r.recoveryTimeMinutes, r.vo2max, r.epoc, r.trainingLoadWeekly, r.trainingLoadMonthly,
                r.optimalLoadMin, r.optimalLoadMax, r.maximalHeartRate,
            )
            is StrapRestingHr -> if (r.heartRate in 30..120) resting += RestingHeartRate(dayOf(r.timestamp), r.heartRate)
            // A realtime value (86) must not replace the strap's daily resting HR (58) for the same day.
            is RestHrRealtime -> if (r.realtimeRestHr in 30..120) restingIfMissing += RestingHeartRate(dayOf(r.timestamp), r.realtimeRestHr)
            is SleepResult -> storeSleep(r)
            is SkinTemperatureSample -> r.skinCelsius?.let { skin += SkinTemperature(r.timestamp, it) }
            is SleepBreathRate -> if (r.breathRate in 4..40) breath += BreathRate(r.timestamp, r.breathRate)
            is PaiRecord -> {
                pai += PaiSample(r.timestamp, r.dailyPai, r.totalPai, r.restHr, r.maxHr, r.nextActivityScores?.getOrNull(1))
                // The strap's resting-HR profile; used only for days without a real resting value.
                if (r.restHr in 30..120) restingIfMissing += RestingHeartRate(dayOf(r.timestamp), r.restHr)
            }
            // Every record as sent, extras and all, so they can be decoded later ([SleepSpo2Sample]).
            is SleepSpO2Sample -> sleepSpo2 += SleepSpo2Sample(
                r.timestamp, r.spo2, r.time, r.v2?.high, r.v2?.low, r.v2?.signalQuality, r.v2?.extend,
            )
            is SpO2OsaEvent -> osaEvents += OsaEventRow(
                r.timestamp, r.spo2Decrease, r.spo2?.map { it.toByte() }?.toByteArray(), r.heartRate?.map { it.toByte() }?.toByteArray(),
            )
            is OsaResult -> osaResults += OsaResultRow(r.timestamp, r.odiValidTime, r.odiRefined, r.odiScore, r.odiDispCode, r.odNumRefined, r.version)
            // The strap updates the day's value as it rises; keep the highest seen.
            is DailyMaxHeartRate -> HeartRateSentinel.valid(r.maxHeartRate)?.let { bpm ->
                maxHr.merge(dayOf(r.timestamp), bpm, ::maxOf)
            }
            is RecoveryHeartRate -> if (r.samples.isNotEmpty()) recoveryHr += RecoveryHeartRateRow(r.trackId, r.samples.map { it.toByte() }.toByteArray())
            // A record of 0xFF bytes is buffer padding, not a minute (keepable drops it already).
            is TodayActivityExtended -> if (!isBlank(r)) extended += ActivityExtendedMinute(
                r.timestamp, r.floorCount, r.runDuration, r.fatBurnDuration, r.standDuration, r.walkDuration, r.sitDuration,
                r.steps, r.calories, r.distanceCm,
            )
            is SleepRespiratoryEvent -> raw += RawRecord(HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT.code, r.timestamp, r.raw)
            is org.openstrap.protocol.history.RawHistoryRecord -> raw += RawRecord(r.code, r.timestamp, r.raw)
            else -> Unit
        }
        val partial = mutableListOf<HeartRateSample>()
        perSecond.forEach { (minute, values) ->
            val sample = HeartRateSample(minute, values.average().toInt())
            if (minute == partMinute) partial += sample else hr += sample
        }
        if (activity.isNotEmpty()) dao.insertActivity(activity)
        if (hr.isNotEmpty()) dao.insertHeartRate(hr)
        if (partial.isNotEmpty()) dao.insertHeartRateIfMissing(partial)
        if (spo2.isNotEmpty()) dao.insertSpo2(spo2)
        if (stress.isNotEmpty()) dao.insertStress(stress)
        if (hrv.isNotEmpty()) dao.insertHrv(hrv)
        if (resting.isNotEmpty()) dao.insertRestingHr(resting)
        if (restingIfMissing.isNotEmpty()) dao.insertRestingHrIfMissing(restingIfMissing)
        if (skin.isNotEmpty()) dao.insertSkinTemperature(skin)
        if (breath.isNotEmpty()) dao.insertBreathRate(breath)
        if (pai.isNotEmpty()) dao.insertPai(pai)
        if (sdnn.isNotEmpty()) dao.insertHrvSdnn(sdnn)
        if (workouts.isNotEmpty()) dao.insertWorkouts(workouts)
        if (effects.isNotEmpty()) dao.insertSportEffects(effects)
        if (sleepSpo2.isNotEmpty()) dao.insertSleepSpo2(sleepSpo2)
        if (osaEvents.isNotEmpty()) dao.insertOsaEvents(osaEvents)
        if (osaResults.isNotEmpty()) dao.insertOsaResults(osaResults)
        if (maxHr.isNotEmpty()) dao.insertDailyMaxHr(maxHr.map { (day, bpm) -> DailyMaxHr(day, bpm) })
        if (recoveryHr.isNotEmpty()) dao.insertRecoveryHeartRate(recoveryHr)
        if (extended.isNotEmpty()) dao.insertActivityExtended(extended)
        if (raw.isNotEmpty()) dao.insertRawRecords(raw)
    }

    /**
     * Stores a day's sleep result (02 §3.3): the main sleep with the strap's own score and totals, and each
     * nap. The strap re-sends a day as it updates it, so the day's earlier version is replaced.
     */
    private suspend fun storeSleep(r: SleepResult) {
        // The strap's day for the result (02 §3.3), stored as its date ([DayKey]). Its times stay moments from r.day.
        val day = Days.date(r.day(zone), zone)
        var mainWindow: Pair<Long, Long>? = null
        // Every stage the count covers is real; Zepp shows one with an unknown code as light sleep (04 §3.5).
        val main = r.mainStages.filter { it.stop >= it.start }
        main.filter { it.rawType !in SleepStageCodes.VALID }.takeIf { it.isNotEmpty() }
            ?.let { log("history: ${it.size} sleep stages with unknown codes ${it.map { s -> s.rawType }.toSet()}, counted as light") }
        if (main.isNotEmpty()) {
            val start = r.resolveTime(main.first().start, zone)
            val end = r.resolveTime(r.mainSleepEndFixed.takeIf { it > main.first().start } ?: main.last().stop + 1, zone)
            if (end <= start || end - start > 20 * 3600) {
                log("history: implausible sleep window $start..$end, skipped")
            } else {
                mainWindow = start to end
                val session = SleepSession(
                    start, end, DayKey.of(day), nap = false,
                    score = r.sleepScore.takeIf { it in 1..100 },
                    averageHeartRate = HeartRateSentinel.valid(r.averageHeartRate),
                    deepMinutes = r.deepSleepLength, lightMinutes = r.lightSleepLength,
                    remMinutes = r.remSleepLength.takeIf { r.isRemSupported }, awakeMinutes = r.wakeLength,
                    wakeCount = r.wakeCount,
                )
                dao.replaceSleepDay(day, nap = false, listOf(session), main.map { stage(it, start, r) })
            }
        }
        // The newest copy of a day replaces its naps, including when it has none: the strap can report a stretch
        // of broken night sleep as a nap and later fold it into the main sleep. The empty record the strap writes
        // at midnight doesn't claim nap support, so it leaves them alone.
        if (!r.isShortSleepSupported) return
        val stages = r.shortStages.map { stage(it, 0, r) }.filter { it.end > it.start }.sortedBy { it.start }
        val naps = mutableListOf<SleepSession>()
        val napStages = mutableListOf<SleepStage>()
        for ((start, end) in napWindows(r, stages)) {
            if (mainWindow != null && start < mainWindow.second && end > mainWindow.first) continue
            naps += SleepSession(start, end, DayKey.of(day), nap = true)
            napStages += stages.filter { it.start >= start && it.end <= end }.map { it.copy(sessionStart = start) }
        }
        dao.replaceSleepDay(day, nap = true, naps, napStages)
    }

    /** One window per nap: the strap's nap list when it gives one, otherwise runs of back-to-back nap stages. */
    private fun napWindows(r: SleepResult, stages: List<SleepStage>): List<Pair<Long, Long>> {
        val listed = r.shortSleeps.map { r.resolveTime(it.start, zone) to r.resolveTime(it.stop + 1, zone) }.filter { it.second > it.first }
        if (listed.isNotEmpty()) return listed
        val runs = mutableListOf<Pair<Long, Long>>()
        for (s in stages) {
            val last = runs.lastOrNull()
            if (last != null && s.start <= last.second) runs[runs.size - 1] = last.first to maxOf(last.second, s.end)
            else runs += s.start to s.end
        }
        return runs
    }

    /** A stage's stop is the last minute it covers (the next stage starts at stop + 1), so the end is a minute later. */
    private fun stage(s: org.openstrap.protocol.history.SleepStage, sessionStart: Long, r: SleepResult) = SleepStage(
        start = r.resolveTime(s.start, zone),
        end = r.resolveTime(s.stop + 1, zone),
        sessionStart = sessionStart,
        stage = when (s.type) {
            SleepStageType.AWAKE -> SleepStage.AWAKE
            SleepStageType.DEEP -> SleepStage.DEEP
            SleepStageType.REM -> SleepStage.REM
            SleepStageType.LIGHT, SleepStageType.UNKNOWN -> SleepStage.LIGHT
        },
    )

    /** Activity record size: the START size field counts records; then the 0x0028 feature blob, then a guess. */
    private fun recordSize(code: Int, payload: ByteArray, sizeField: Long): Int =
        if (code != HistoryDataType.ACTIVITY.code) 4 else ActivityLayout.recordSize(payload, sizeField, session.features)

    /** The day a record's moment falls on in the phone's zone, the day its row is stored under. */
    private fun dayOf(epoch: Long) = DayKey.dayOf(epoch, zone)

    companion object {
        private val HISTORY_CONTROL = java.util.UUID.fromString("00000004-0000-3512-2118-0009af100700")
        private val HISTORY_DATA = java.util.UUID.fromString("00000005-0000-3512-2118-0009af100700")
        /** How far back a strap session's effects may replace a phone workout's model figures. */
        private const val WORKOUT_LOOKBACK = 30L * 24 * 3600

        /** Set once the stored cursors were moved to Zepp's rules ([migrateCursorsOnce]). */
        internal const val CURSORS_ZEPP_KEY = "cursors_zepp_10105"


        internal fun keyOf(type: HistoryDataType) = "gdsp_${type.code}"

        /** The stored cursors a type's start is made from ([ZeppCursors.start]). */
        private val ZEPP_READS: Map<HistoryDataType, List<HistoryDataType>> = mapOf(
            HistoryDataType.DAILY_MAX_EXERCISE_HR to listOf(HistoryDataType.ACTIVITY),
            HistoryDataType.SEC_HR to listOf(HistoryDataType.SEC_HR),
            HistoryDataType.SEC_HR_V2 to listOf(HistoryDataType.SEC_HR),
            HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT to listOf(HistoryDataType.SLEEP_BREATH_RATE),
            HistoryDataType.SPORT_DETAIL to listOf(HistoryDataType.SPORT_SUMMARY),
        )

        /** 2024-01-01: anything older comes from a strap whose clock was never set. */
        private const val MIN_VALID_EPOCH = 1_704_067_200L
        private const val FUTURE_SLACK = 120L
        /** Zepp's `xr3.d()`: failed exchanges allowed per type in one sync, 1 for types 79 and 96. */
        private const val RETRIES = 3
        private val SINGLE_TRY = setOf(79, 96)
        /** The version reply per connection (Zepp clears its copy on disconnect; a gone session is never asked again). */
        private val versions = java.util.WeakHashMap<ChannelSession, GdspMessage.VersionReply>()
        private const val DUMP_CHUNKS = 5

        /**
         * The records worth storing. Unwritten minutes (all 0xFF) hold nothing. Others need a time after any strap
         * clock was set, and all but the minute series one no later than a strap clock a little ahead.
         */
        internal fun <R : HistoryRecord> storable(type: HistoryDataType, records: List<R>, now: Long): List<R> =
            if (type in MINUTE_SERIES) {
                records.filter { !isBlank(it) && it.timestamp >= MIN_VALID_EPOCH }
            } else {
                records.filter { it.timestamp in MIN_VALID_EPOCH..now + FUTURE_SLACK }
            }

        /** A minute slot the strap hasn't written: all 0xFF (extended activity: its version byte is too). */
        private fun isBlank(r: HistoryRecord): Boolean =
            (r is TodayActivityExtended && r.version == BLANK_VERSION) || (r is ActivitySample && r.isBlank)

        private const val BLANK_VERSION = 0xFF

        /** One row a minute, written by the strap a little after the minute ends. */
        private val MINUTE_SERIES = setOf(HistoryDataType.ACTIVITY, HistoryDataType.TODAY_ACTIVITY_EXTENDED)
    }
}

/**
 * [records] less those of strap workouts deleted in OpenStrap ([deleted]), which the strap keeps and re-sends: the
 * summary, HR stream and recovery HR by the workout's start (all keyed by its trackId), and the sport effect deleted
 * with it by its end.
 */
internal fun withoutDeletedWorkouts(records: List<HistoryRecord>, deleted: Collection<DeletedWorkout>): List<HistoryRecord> {
    if (deleted.isEmpty()) return records
    val starts = deleted.mapTo(HashSet()) { it.start }
    val effectEnds = deleted.mapNotNullTo(HashSet()) { it.effectEnd }
    return records.filter { r ->
        when (r) {
            is WorkoutSummary, is WorkoutDetail, is RecoveryHeartRate -> r.timestamp !in starts
            is SportEffect -> r.timestamp !in effectEnds
            else -> true
        }
    }
}

/** How long after a dismissed time the strap's effect for it may end: it writes the effect as the session closes. */
internal const val EFFECT_GRACE = 120L

/** Whether a sport effect ending at [end] falls inside one of the [dismissed] times, or up to [EFFECT_GRACE] after it. */
internal fun inDismissedTime(end: Long, dismissed: Collection<DismissedWorkout>): Boolean =
    dismissed.any { end >= it.start && end <= it.end + EFFECT_GRACE }

/** Whether one of the [workouts] (start to real end) runs to a sport effect ending at [end], so it owns it. */
internal fun ownedEffect(end: Long, workouts: Collection<LongRange>): Boolean =
    workouts.any { end > it.first && end <= it.last + EFFECT_GRACE }

/**
 * The deleted marker for a sport effect no workout owns, so a re-send is skipped ([withoutDeletedWorkouts]) and a
 * restore leaves it out. It has no workout, so its effect's end stands for the start: no strap workout starts the
 * second an effect ends.
 */
internal fun effectMarker(end: Long, now: Long) = DeletedWorkout(end, end, now)

/** A strap workout summary (type 5) as stored. The subtype is kept so auto-detected workouts can be told apart (codemap 05 §8). */
internal fun workoutRow(r: WorkoutSummary): Workout = Workout(
    r.trackId, r.trackId + (r.exerciseTime ?: r.totalTime ?: 0), r.sportType, r.exerciseTime ?: r.totalTime,
    r.calories, r.averageHeartRate, r.maxHeartRate, r.minHeartRate, r.aerobicTrainingEffect,
    r.anaerobicTrainingEffect, r.trainingLoad, r.vo2max, r.steps, subtype = r.subtype,
)

/** The kind of data a sync step fetches, as "Syncing heart rate (3/12)" names it. Any other type is "other data". */
internal fun syncKindName(t: HistoryDataType, strings: Strings = Strings.app): String =
    strings.get(SYNC_KIND_NAMES[t] ?: R.string.system_sync_kind_other)

/** Each named kind of data and the strap types it covers. */
private val SYNC_KIND_NAMES: Map<HistoryDataType, Int> = listOf(
    R.string.system_sync_kind_activity to listOf(HistoryDataType.ACTIVITY, HistoryDataType.TODAY_ACTIVITY_EXTENDED),
    R.string.system_sync_kind_sleep to listOf(HistoryDataType.SLEEP_RESULT, HistoryDataType.SLEEP_PLAN),
    R.string.system_sync_kind_hrv to listOf(HistoryDataType.HRV_RMSSD, HistoryDataType.HRV_SDNN),
    R.string.system_sync_kind_resting_hr to listOf(HistoryDataType.REST_HR, HistoryDataType.RESTING_HEART_RATE),
    R.string.system_sync_kind_heart_rate to listOf(HistoryDataType.HEART_RATE_MANUAL, HistoryDataType.SEC_HR, HistoryDataType.SEC_HR_V2),
    R.string.system_sync_kind_peak_hr to listOf(HistoryDataType.DAILY_MAX_EXERCISE_HR),
    R.string.system_sync_kind_recovery_hr to listOf(HistoryDataType.RECOVERY_HEART_RATE),
    R.string.system_sync_kind_blood_oxygen to listOf(HistoryDataType.SPO2_SINGLE, HistoryDataType.SPO2_OSA),
    R.string.system_sync_kind_sleep_breathing to listOf(
        HistoryDataType.SPO2_OSA_EVENT, HistoryDataType.SPO2_OSA_EVENT_ALT, HistoryDataType.OSA_RESULT,
        HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT,
    ),
    R.string.system_sync_kind_breathing_rate to listOf(HistoryDataType.SLEEP_BREATH_RATE),
    R.string.system_sync_kind_stress to listOf(HistoryDataType.STRESS_SINGLE, HistoryDataType.STRESS_ALL_DAY),
    R.string.system_sync_kind_heart_points to listOf(HistoryDataType.PAI),
    R.string.system_sync_kind_skin_temperature to listOf(
        HistoryDataType.BODY_TEMPERATURE_ALL_DAY, HistoryDataType.BODY_TEMPERATURE_SINGLE, HistoryDataType.BODY_TEMPERATURE_80S,
        HistoryDataType.TEMPERATURE_LEGACY,
    ),
    R.string.system_sync_kind_strap_stats to listOf(HistoryDataType.DEVICE_STATISTIC),
    R.string.system_sync_kind_workouts to listOf(HistoryDataType.SPORT_SUMMARY, HistoryDataType.SPORT_DETAIL, HistoryDataType.SPORT_EFFECT),
).flatMap { (id, types) -> types.map { it to id } }.toMap()
