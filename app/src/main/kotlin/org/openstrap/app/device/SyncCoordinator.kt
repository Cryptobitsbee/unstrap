package org.openstrap.app.device

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.ble.GattClient
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.protocol.ChannelSession

/**
 * History sync: one at a time, a full sync or the types the strap pushed queued behind it, the hourly auto sync, and
 * the work that follows each sync (body signals, workout gap fill, workout effect, daily calories).
 */
// The sync queue and each piece of work after a sync, as small functions.
@Suppress("TooManyFunctions")
internal class SyncCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val dao: HealthDao,
    private val link: LiveLink,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val notifiers: Notifiers,
    private val log: (String) -> Unit,
) {
    /** Where a sync's findings go: a detected workout, and the morning's body-signal alert. */
    class Notifiers(val autoDetected: AutoDetectNotifier, val healthAlerts: HealthAlertNotifier)

    val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncRecord = MutableStateFlow(SyncRecord())
    val syncEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var lastAutoSync = 0L

    init {
        // Every sync's start and end, pulled or in the background, so Today can tell a stuck sync from a slow one.
        scope.launch {
            var previous: SyncState = SyncState.Idle
            sync.collect { s ->
                syncRecord.update { it.next(previous, s, nowSeconds()) }
                previous = s
            }
        }
    }

    /**
     * Sync right after connecting (unless one ran recently and didn't fail), then every [AUTO_SYNC_SECONDS] while
     * connected. A failed auto sync is retried at once: it was most likely cut off by the link this connect replaces.
     * A failed Sync now isn't a reason: the user asked for that one, and the next auto sync isn't owed anything. A Sync
     * now kept while there was no session ([held]) runs first.
     */
    fun autoSync(s: ChannelSession) = scope.launch {
        while (isActive && link.session === s) {
            if (held) {
                held = false
                requests.trySend(SyncRequest.Full(manual = true, session = s))
            }
            val now = nowSeconds()
            val due = now - lastAutoSync >= MIN_SYNC_GAP_SECONDS || (autoFailed && sync.value is SyncState.Failed)
            if (due && sync.value !is SyncState.Running && !firmwareUpdating.value) {
                lastAutoSync = now
                log("auto sync")
                requests.trySend(SyncRequest.Full(manual = false, session = s))
            }
            delay(AUTO_SYNC_SECONDS * MILLIS_PER_SECOND)
        }
    }

    /** The user's Sync now. Kept, never lost: it waits for a session or a firmware update to end. */
    fun syncNow() {
        requests.trySend(SyncRequest.Full(manual = true, session = link.activeSession))
    }

    /** The strap pushed new data of [types]: sync them now, or after the sync running. */
    fun syncPushed(types: Set<Int>) {
        val s = link.activeSession ?: return
        requests.trySend(SyncRequest.Types(types, s))
    }

    /**
     * Everything asked for, in order. One worker reads it, so there is one sync at a time and a request made meanwhile
     * is read when it ends.
     */
    private val requests = Channel<SyncRequest>(Channel.UNLIMITED)

    /** The worker's turn: a sync holds the lock, so a firmware install mustn't start. */
    private val syncLock = Mutex()

    /** A Sync now that found no session: it runs at the next connect ([autoSync]). */
    @Volatile private var held = false

    /** Whether the last sync that ran was an auto sync and failed. */
    @Volatile private var autoFailed = false

    val isSyncing: Boolean get() = syncLock.isLocked

    init {
        scope.launch {
            for (first in requests) {
                catching { work(listOf(first) + drain()) }.onFailure { log("sync worker: $it") }
            }
        }
    }

    private fun drain(): List<SyncRequest> {
        val out = mutableListOf<SyncRequest>()
        while (true) out += requests.tryReceive().getOrNull() ?: break
        return out
    }

    /** Runs one batch of [asked] requests: the syncs they merge into, then the debug dumps. */
    // Each return is a reason not to sync now.
    @Suppress("ReturnCount")
    private suspend fun work(asked: List<SyncRequest>) {
        var batch = asked
        if (firmwareUpdating.value) {
            // The strap is busy installing: keep the user's request, drop what the old firmware pushed.
            firmwareUpdating.first { !it }
            batch = batch.filter { it is SyncRequest.Full && it.manual || it is SyncRequest.Dump } + drain()
        }
        val s = link.activeSession
        val client = link.gatt
        val dumps = batch.filterIsInstance<SyncRequest.Dump>()
        if (s == null || client == null) {
            if (SyncPlan.of(batch, null).manual) held = true
            if (dumps.isNotEmpty()) log("dump: not connected")
            return
        }
        var leftover = false
        var ran = false
        syncLock.withLock {
            var plan = SyncPlan.of(batch, s)
            while (!plan.isEmpty) {
                ran = true
                HistorySync(s, client, dao, settings, sync, log, notifiers.autoDetected::show).run(plan.only)
                autoFailed = !plan.manual && sync.value is SyncState.Failed
                (sync.value as? SyncState.Done)?.let { d ->
                    catching { settings.setLastSyncDone(d.atEpochSeconds) }.onFailure { log("sync time not kept: $it") }
                }
                val more = drain()
                if (link.activeSession !== s || firmwareUpdating.value) {
                    // The session ended or an install began: a kept Sync now goes round again, the rest is stale.
                    leftover = SyncPlan.of(more, null).manual
                    break
                }
                plan = SyncPlan.of(more, s)
            }
            for (d in dumps) dump(s, client, d)
            if (ran) {
                afterSync()
                // Everything a widget shows may have moved (widgets spec 7): they watch for this.
                syncEnded.tryEmit(Unit)
            }
        }
        // A sync cut off by a lost link can end only after the reconnect, which skipped its own sync while this one
        // still showed as running. Run it again on the new link.
        val current = link.activeSession
        if (ran && sync.value is SyncState.Failed && current != null && current !== s) {
            log("sync cut off by the old link, syncing again")
            requests.trySend(SyncRequest.Full(manual = false, session = current))
        }
        if (leftover) requests.trySend(SyncRequest.Full(manual = true, session = null))
    }

    /** One debug dump, which writes Running and must not leave it behind. */
    private suspend fun dump(s: ChannelSession, client: GattClient, d: SyncRequest.Dump) {
        try {
            val since = nowSeconds() - d.days * SECONDS_PER_DAY
            HistorySync(s, client, dao, settings, sync, log).dumpRaw(since, java.io.File(context.filesDir, "raw"), d.types)
        } finally {
            if (sync.value is SyncState.Running) sync.value = SyncState.Idle
        }
    }

    private suspend fun afterSync() {
        catching { notifyBodySignals() }.onFailure { log("body signals: $it") }
        catching { WorkoutGapFill(dao) { settings.profile.first() }.run()?.let { log(it) } }
            .onFailure { log("workout gap fill: $it") }
        catching { updateEffectModel() }.onFailure { log("workout effect: $it") }
        catching { updateDailyCalories() }.onFailure { log("daily energy: $it") }
    }

    /**
     * After a sync, pushes the body-signal alert for this morning once, when the state machine is in Alert
     * ([org.openstrap.app.data.BodySignals]). Watch stays in the app.
     */
    // Each return is a reason not to push.
    @Suppress("ReturnCount")
    private suspend fun notifyBodySignals() {
        val today = java.time.LocalDate.now()
        val day = org.openstrap.app.data.DayWindow.of(today).from
        // By date, so after a flight west the same morning doesn't push again.
        val pushDay = org.openstrap.app.data.DayKey.of(today)
        if ((settings.lastSync(BODY_SIGNALS_PUSHED) ?: 0L) >= pushDay) return
        val r = org.openstrap.app.data.RecoveryEngine(dao).compute(today, settings.profile.first())
        val body = r.readiness.bodySignals ?: return
        if (body.day != day || body.state != org.openstrap.app.data.BodySignals.State.Alert) return
        notifiers.healthAlerts.showBodySignals(body.families, body.nightsOff)
        settings.setLastSync(BODY_SIGNALS_PUSHED, pushDay)
    }

    /**
     * Once per change of [org.openstrap.app.data.WorkoutEffect.MODEL], works out every phone workout's effect again,
     * so a workout saved under the old model shows the new one's Load. The strap's own figures, where it kept a
     * record, still win ([org.openstrap.app.data.WorkoutEffectEngine.strapEffect]).
     */
    private suspend fun updateEffectModel() {
        val model = org.openstrap.app.data.WorkoutEffect.MODEL.toLong()
        if (settings.lastSync(EFFECT_MODEL) == model) return
        val n = org.openstrap.app.data.WorkoutEffectEngine(dao).recomputeAll(settings.profile.first(), WorkoutSession.LOCK)
        settings.setLastSync(EFFECT_MODEL, model)
        log("workout effect: model $model, $n workouts again")
    }

    /**
     * After a sync, keeps each day's calories row ([org.openstrap.app.data.DailyCaloriesHistory]): the first time every day
     * with data, then the recent days, and all of them again when the profile they were worked out with has changed.
     */
    private suspend fun updateDailyCalories() {
        val profile = settings.profile.first()
        val key = org.openstrap.app.data.DailyCaloriesHistory.profileKey(profile)
        val changed = settings.lastSync(DAILY_CALORIES_PROFILE) != key
        val written = org.openstrap.app.data.DailyCaloriesHistory(dao).update(profile, profileChanged = changed)
        settings.setLastSync(DAILY_CALORIES_PROFILE, key)
        if (written > 0) log("daily energy: $written days")
    }

    /** Debug aid, see [HistorySync.dumpRaw]. It takes its turn like any sync. */
    fun dumpRaw(days: Int, types: Set<Int>? = null) {
        requests.trySend(SyncRequest.Dump(days, types))
    }

    private fun nowSeconds() = System.currentTimeMillis() / MILLIS_PER_SECOND

    private companion object {
        const val MIN_SYNC_GAP_SECONDS = 15 * 60L
        const val AUTO_SYNC_SECONDS = 60 * 60L
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_DAY = 86_400L
        /**
         * Settings key: the last morning a body-signal alert was pushed ([org.openstrap.app.data.DayKey]). Builds before
         * kept its local midnight under another key, so the upgrade morning can push once more at most.
         */
        const val BODY_SIGNALS_PUSHED = "body_signals_push_day"
        /**
         * [org.openstrap.app.data.DailyCaloriesHistory.profileKey] of the profile the daily calories rows were last
         * written with. Stored under its first name, so the key saved before the rename still counts.
         */
        const val DAILY_CALORIES_PROFILE = "daily_energy_profile"
        /** The [org.openstrap.app.data.WorkoutEffect.MODEL] phone workouts' effects were last worked out with. */
        const val EFFECT_MODEL = "workout_effect_model"
    }
}

/**
 * What was asked of the sync worker. [session] is the one it was asked on (null for a Sync now that found none): a
 * request for another session than the current one is stale, except a user's Sync now.
 */
internal sealed interface SyncRequest {
    /** Everything. [manual] for the user's Sync now, false for the hourly one. */
    data class Full(val manual: Boolean, val session: Any?) : SyncRequest

    /** The types the strap pushed. */
    data class Types(val types: Set<Int>, val session: Any?) : SyncRequest

    /** Debug: [HistorySync.dumpRaw]. */
    data class Dump(val days: Int, val types: Set<Int>?) : SyncRequest
}

/** A batch of [SyncRequest]s merged into one sync: everything, or just the pushed types. */
internal class SyncPlan(val full: Boolean, val types: Set<Int>, val manual: Boolean) {
    val isEmpty: Boolean get() = !full && types.isEmpty()

    /** What [HistorySync.run] takes: null for everything (a full sync covers anything pushed), else the types. */
    val only: Set<Int>? get() = if (full) null else types

    companion object {
        /**
         * The plan for [requests] on the [current] session. A request made on another session is dropped, so it can't
         * replay as a surprise sync; a user's Sync now stays whatever session it was made on. With no current session
         * only that is left, which is what a caller keeps to run later.
         */
        fun of(requests: List<SyncRequest>, current: Any?): SyncPlan {
            val live = requests.filter {
                when (it) {
                    is SyncRequest.Full -> it.manual || (current != null && it.session === current)
                    is SyncRequest.Types -> current != null && it.session === current
                    is SyncRequest.Dump -> false
                }
            }
            return SyncPlan(
                full = live.any { it is SyncRequest.Full },
                types = live.filterIsInstance<SyncRequest.Types>().flatMapTo(mutableSetOf()) { it.types },
                manual = live.any { it is SyncRequest.Full && it.manual },
            )
        }
    }
}
