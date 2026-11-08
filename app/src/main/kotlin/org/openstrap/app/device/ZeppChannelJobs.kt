package org.openstrap.app.device

import org.openstrap.app.data.DayKey
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.RawRecord
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.PaiRecord
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.PaiCodec
import org.openstrap.protocol.services.ReadinessCodec
import org.openstrap.protocol.services.SleepModuleCodec
import org.openstrap.protocol.toHex
import org.openstrap.protocol.u8
import java.time.Instant
import java.time.ZoneId

/**
 * The history sync's jobs on channel modules rather than GDSP (Zepp 10.10.5): readiness `08`, `0C`, `06` (0x0049), PAI
 * `0C` (0x0022), and the module version queries that gate them and the sleep plan job.
 */
internal class ZeppChannelJobs(
    private val session: ChannelSession,
    private val dao: HealthDao,
    private val cursors: SyncCursors,
    private val zone: ZoneId,
    private val log: (String) -> Unit,
    private val storePai: suspend (List<PaiRecord>) -> Unit,
) {
    /**
     * A readiness module job (Zepp's `jo00`, `ma80`, `fm00`): `<op> since` from its own cursor, the records stored,
     * the cursor then at the last record + 1 s. The score keeps its table; stress threshold and data are kept raw.
     * False when the reply isn't one.
     */
    suspend fun readiness(op: Int): Boolean {
        val now = System.currentTimeMillis() / 1000
        val key = "gdsp_readiness_%02x".format(java.util.Locale.ROOT, op)
        val since = ZeppCursors.read(cursors, key, now, zone)
        val reply = session.request(Endpoints.READINESS, ReadinessCodec.request(op, since), CHANNEL_JOB_MS) {
            it.isNotEmpty() && it.u8(0) == op + 1
        }
        val last = if (op == ReadinessCodec.SCORE) storeScores(reply) else storeRaw(op, reply)
        last?.takeIf { it > 0 }?.let { cursors.set(key, ZeppCursors.onWrite(it + 1, now)) }
        return last != null
    }

    /** The newest score's day, 0 for none, null for no score reply. */
    private suspend fun storeScores(reply: ByteArray): Long? {
        val scores = ReadinessCodec.parseScores(reply) ?: return null
        log("readiness: ${scores.joinToString { "${Instant.ofEpochSecond(it.day)} ${it.score}" }.ifEmpty { "none" }}")
        // The strap's day is its local midnight, taken as its date in the phone's zone, as the strap keeps the phone's.
        val rows = scores.filter { it.score != null }.map {
            StrapReadinessRow(
                DayKey.fromLegacy(it.day, zone), it.updatedAt, it.score, it.hrvScore, it.restingHrScore,
                it.skinTemperatureScore, it.breathingScore, it.sleepHrv, it.sleepRestingHr, it.algorithmVersion,
            )
        }
        if (rows.isNotEmpty()) dao.insertStrapReadiness(rows)
        return scores.maxOfOrNull { it.day } ?: 0
    }

    /** The newest record's time, 0 for none, null for no reply. */
    private suspend fun storeRaw(op: Int, reply: ByteArray): Long? {
        val records = ReadinessCodec.parseRecords(op, reply) ?: return null
        log("readiness %02x: %d records".format(java.util.Locale.ROOT, op, records.size))
        val type = if (op == ReadinessCodec.DATA) READINESS_DATA_TYPE else STRESS_THRESHOLD_TYPE
        if (records.isNotEmpty()) dao.insertRawRecords(records.map { (t, raw) -> RawRecord(type, t, raw) })
        return records.maxOfOrNull { it.first } ?: 0
    }

    /** PAI from the PAI module, as Zepp's `whx`: `0C since` from PAI's cursor (13), the records stored, then the cursor. */
    suspend fun pai(): Boolean {
        val now = System.currentTimeMillis() / 1000
        val key = HistorySync.keyOf(HistoryDataType.PAI)
        val reply = session.request(Endpoints.PAI, PaiCodec.query(ZeppCursors.read(cursors, key, now, zone)), CHANNEL_JOB_MS) {
            PaiCodec.isReply(it)
        }
        val records = PaiCodec.parse(reply)
        log("pai: ${records?.size ?: reply.toHex()} records")
        if (!records.isNullOrEmpty()) {
            storePai(records)
            cursors.set(key, ZeppCursors.onWrite(records.maxOf { it.timestamp } + 1, now))
        }
        return records != null
    }

    /** Whether the strap's sleep module (0x0032 `01`) is version 2 or more, asked once per strap as Zepp caches it. */
    suspend fun sleepPlanSupported(): Boolean {
        val known = cursors.get(SLEEP_MODULE_KEY)
        val version = known ?: if (session.services.has(Endpoints.SLEEP)) {
            catching {
                session.request(Endpoints.SLEEP, SleepModuleCodec.versionRequest, CHANNEL_JOB_MS) { it.isNotEmpty() && it.u8(0) == 2 }
            }.getOrNull()?.let(SleepModuleCodec::parseVersion)?.toLong()
                .also { log("sleep module version $it") }
                ?.also { cursors.set(SLEEP_MODULE_KEY, it) }
        } else {
            null
        }
        return (version ?: 0) >= SLEEP_PLAN_VERSION
    }

    suspend fun paiVersion(): Int? = moduleVersion(Endpoints.PAI, PaiCodec.versionRequest, PaiCodec::parseVersion, paiVersions)

    suspend fun readinessVersion(): Int? =
        moduleVersion(Endpoints.READINESS, ReadinessCodec.versionRequest, ReadinessCodec::parseVersion, readinessVersions)

    /** A module's version reply, asked once per connection, only when the strap lists the module. */
    private suspend fun moduleVersion(
        endpoint: Int,
        request: ByteArray,
        parse: (ByteArray) -> Int?,
        cache: java.util.WeakHashMap<ChannelSession, Int>,
    ): Int? {
        if (!session.services.has(endpoint)) return null
        val cached = synchronized(cache) { cache[session] }
        val version = cached ?: catching { session.request(endpoint, request, CHANNEL_JOB_MS) { parse(it) != null } }
            .getOrNull()?.let(parse)
            .also { log("module %04x version %s".format(java.util.Locale.ROOT, endpoint, it)) }
            ?.also { synchronized(cache) { cache[session] = it } }
        return version
    }

    private companion object {
        /** The sleep module's version (0x0032), asked once per strap; cleared with the strap's cursors. */
        const val SLEEP_MODULE_KEY = "gdsp_sleep_module"
        const val SLEEP_PLAN_VERSION = 2

        /** Readiness records kept raw are typed as Zepp's cursor ids for them (`vt80`). */
        const val READINESS_DATA_TYPE = -1
        const val STRESS_THRESHOLD_TYPE = -3

        /** Zepp's 20 s for a channel job's reply. */
        const val CHANNEL_JOB_MS = 20_000L
        val paiVersions = java.util.WeakHashMap<ChannelSession, Int>()
        val readinessVersions = java.util.WeakHashMap<ChannelSession, Int>()
    }
}
