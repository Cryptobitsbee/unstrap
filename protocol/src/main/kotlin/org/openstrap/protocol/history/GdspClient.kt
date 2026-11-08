package org.openstrap.protocol.history

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.protocol.catching
import org.openstrap.protocol.crypto.crc32
import org.openstrap.protocol.toHex
import java.time.ZoneId

/**
 * A GDSP command got no reply in time. A plain exception, not `withTimeout`'s cancellation, so one slow reply
 * fails that type instead of cancelling the whole sync.
 */
class GdspTimeoutException(op: Int) : Exception("no reply to GDSP command %02x".format(op))

/**
 * What one START to ACK exchange produced, after its ACK went out (Zepp 10.10.5 `v3j`, codemap sync-zepp-10.10.5
 * §5.1). [ack] is the code the ACK carried.
 */
sealed interface GdspFetch {
    val ack: Int

    /** Size 0: nothing newer than the cursor. */
    data object Empty : GdspFetch {
        override val ack get() = GdspStatus.SUCCESS.code
    }

    /** Anything else: a refused or unanswered START, a broken transfer, or the cyclic check. */
    data class Failed(override val ack: Int, val reason: String) : GdspFetch {
        val cyclic: Boolean get() = ack == GdspClient.CYCLIC_SYNC
        val fastRecharge: Boolean get() = ack == GdspStatus.FAST_RECHARGE_MODE.code
    }

    /**
     * One chunk, inflated. [sizeField] is the START size, which counts records for fixed-size types and bytes
     * otherwise.
     */
    class Chunk(val payload: ByteArray, val headerTime: Long, val sizeField: Long) : GdspFetch {
        override val ack get() = GdspStatus.SUCCESS.code

        /** Bytes per unit of [sizeField]: 1 when it counts bytes, null when the payload doesn't fit it. */
        val unit: Int?
            get() = when {
                payload.size.toLong() == sizeField -> 1
                sizeField > 0 && payload.size % sizeField == 0L -> (payload.size / sizeField).toInt()
                else -> null
            }
    }
}

/**
 * The previous chunk of the same type in this loop, for Zepp's cyclic check (`eha`): when the cursor moved since it
 * and the new header time is not later than its header time, the strap is going round in circles.
 */
data class PreviousChunk(val cursor: Long, val headerTime: Long)

/**
 * The phone side of a GDSP fetch, as Zepp 10.10.5 runs it (codemap sync-zepp-10.10.5 §5.1). [send] writes a
 * control command (channel 0x004B or HUAMI(0004)); [control] carries decoded control messages and [data] the indexed
 * packets from HUAMI(0005). Data-available pushes that arrive while waiting go to [onDataAvailable].
 */
class GdspClient(
    private val send: suspend (ByteArray) -> Unit,
    private val control: ReceiveChannel<GdspMessage>,
    private val data: ReceiveChannel<ByteArray>,
    private val log: (String) -> Unit = {},
    private val onDataAvailable: (GdspMessage.DataAvailable) -> Unit = { log("history: device has new data of type ${it.typeCode}") },
) {
    /** Sends a command and waits for its `10 <op>` response. Throws [GdspTimeoutException] if none comes. */
    suspend fun command(bytes: ByteArray, timeoutMs: Long = TIMEOUT_MS): GdspMessage.Response {
        val op = bytes[0].toInt() and 0xFF
        send(bytes)
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                when (val m = control.receive()) {
                    is GdspMessage.Response -> if (m.opcode == op) return@withTimeoutOrNull m
                    is GdspMessage.DataAvailable -> onDataAvailable(m)
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw GdspTimeoutException(op)
    }

    /** QUERY_VERSION `00`, 20 s. Null on a timeout, a failed send or a refusal. */
    suspend fun queryVersion(): GdspMessage.VersionReply? =
        catching { command(FetchCodec.queryVersion()) }
            .onFailure { log("history: version query failed: $it") }.getOrNull() as? GdspMessage.VersionReply

    /**
     * One START to ACK exchange for [typeCode] from [since] (Zepp's `v3j`): START, the cyclic check against
     * [previous], TRANSFER, the end checks, then the ACK `03 <code>` in every case, before anything is parsed.
     */
    suspend fun fetch(typeCode: Int, since: Long, zone: ZoneId, previous: PreviousChunk? = null): GdspFetch {
        val start = startOrNull(typeCode, since, zone)
            ?: return closed(GdspFetch.Failed(GdspStatus.OPERATION_FAILED.code, "no reply to START"))
        // Zepp maps the status to its enum, and an unknown code counts as success (`hxi`).
        val known = GdspStatus.fromCode(start.statusCode) != GdspStatus.UNKNOWN
        if (known && !start.isSuccess) {
            return closed(GdspFetch.Failed(start.statusCode, "start refused (${start.status})"))
        }
        if (start !is GdspMessage.StartReply) return closed(GdspFetch.Failed(GdspStatus.OPERATION_FAILED.code, "no header"))
        if (start.size == 0L) return closed(GdspFetch.Empty)
        val headerTime = start.firstRecordEpoch
            ?: return closed(GdspFetch.Failed(GdspStatus.OPERATION_FAILED.code, "no header"))
        if (previous != null && since != previous.cursor && headerTime <= previous.headerTime) {
            return closed(GdspFetch.Failed(CYCLIC_SYNC, "cyclic: header $headerTime not after ${previous.headerTime}"))
        }
        return closed(transfer(typeCode, start, headerTime))
    }

    /** START, or null when the strap doesn't answer in 20 s. */
    private suspend fun startOrNull(typeCode: Int, since: Long, zone: ZoneId): GdspMessage.Response? =
        try {
            command(FetchCodec.startFetch(typeCode, GdspTime.of(since, zone)))
        } catch (e: GdspTimeoutException) {
            log("history: start $typeCode: $e")
            null
        }

    /**
     * TRANSFER `02`, the indexed packets until the strap's `10 02`, then Zepp's checks in its order: size (fewer
     * inflated bytes than the START size, except types 60, 80 and 84), the `10 02` itself (success with exactly 4
     * bytes), then its CRC32 over the raw wire bytes.
     */
    // One check per Zepp result code.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun transfer(typeCode: Int, start: GdspMessage.StartReply, headerTime: Long): GdspFetch {
        while (data.tryReceive().isSuccess) {} // drop packets left over from an earlier transfer
        val wire = java.io.ByteArrayOutputStream()
        var lastIndex = -1
        var lastPacket: ByteArray? = null
        send(FetchCodec.transfer())
        var complete: GdspMessage.TransferComplete? = null
        while (complete == null) {
            val got = withTimeoutOrNull(TIMEOUT_MS) {
                select<Any> {
                    data.onReceive { it }
                    control.onReceive { it }
                }
            } ?: run {
                log("history: no data for 20 s")
                return GdspFetch.Failed(GdspStatus.OPERATION_FAILED.code, "no data for 20 s")
            }
            when (got) {
                is ByteArray -> if (got.isNotEmpty()) {
                    val index = got[0].toInt() and 0xFF
                    when {
                        // The same packet again: Zepp ignores it.
                        index == lastIndex && lastPacket.contentEquals(got) -> Unit
                        index == (lastIndex + 1) and 0xFF && (lastIndex >= 0 || index == 0) -> {
                            lastIndex = index
                            lastPacket = got
                            wire.write(got, 1, got.size - 1)
                        }
                        else -> {
                            log("history: missing packet, $lastIndex then $index")
                            return GdspFetch.Failed(GdspStatus.PACKET_INDEX_ERROR.code, "packet gap $lastIndex -> $index")
                        }
                    }
                }
                is GdspMessage.TransferComplete -> complete = got
                is GdspMessage.DataAvailable -> onDataAvailable(got)
                is GdspMessage.Malformed -> log("history: unexpected during transfer: ${got.reason} ${got.raw.toHex()}")
                else -> log("history: unexpected during transfer: $got")
            }
        }
        // Packets on the data characteristic can reach the phone just after the `10 02` on the control path.
        withTimeoutOrNull(TRAILING_MS) {
            while (true) data.receive().let { p ->
                if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == (lastIndex + 1) and 0xFF) {
                    lastIndex = p[0].toInt() and 0xFF
                    wire.write(p, 1, p.size - 1)
                }
            }
        }
        val raw = wire.toByteArray()
        // Inflated only when the START's expansion says so (expansion[0] == 1).
        val payload = if (start.isCompressed == true) {
            runCatching { inflate(raw) }.getOrElse {
                return GdspFetch.Failed(GdspStatus.DECOMPRESS_FAILED.code, "inflate failed: $it")
            }
        } else raw
        if (payload.size < start.size && typeCode !in SIZE_UNCHECKED) {
            return GdspFetch.Failed(GdspStatus.DATA_SIZE_MISMATCH.code, "${payload.size} bytes for size ${start.size}")
        }
        if (!complete.isSuccess || complete.extra.size != CRC_BYTES) {
            return GdspFetch.Failed(GdspStatus.OPERATION_FAILED.code, "transfer ended with ${complete.status} ${complete.extra.toHex()}")
        }
        if (complete.crc32 != crc32(raw)) return GdspFetch.Failed(GdspStatus.CRC_MISMATCH.code, "CRC mismatch")
        return GdspFetch.Chunk(payload, headerTime, start.size)
    }

    /** The ACK every exchange ends with, `03 <code>`, then up to 20 s for the strap's `10 03`, which is ignored. */
    private suspend fun closed(result: GdspFetch): GdspFetch {
        send(FetchCodec.ack(result.ack))
        val reply = withTimeoutOrNull(TIMEOUT_MS) {
            while (true) {
                val m = control.receive()
                if (m is GdspMessage.Response && m.opcode == FetchCodec.OP_ACK) return@withTimeoutOrNull m
                if (m is GdspMessage.DataAvailable) onDataAvailable(m)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        log("history: ack %02x -> %s".format(java.util.Locale.ROOT, result.ack and 0xFF, reply?.status ?: "no reply"))
        return result
    }

    /**
     * One workout as Zepp's `pxk` fetches it: START(5, cursor) for the summary, then START(6, same cursor) for its
     * detail, each its own exchange with its own ACK. No retries and no cyclic check.
     */
    suspend fun fetchWorkout(since: Long, zone: ZoneId): WorkoutFetch {
        val s = fetch(HistoryDataType.SPORT_SUMMARY.code, since, zone)
        if (s is GdspFetch.Failed) return WorkoutFetch.Failed("summary: ${s.reason}", s.fastRecharge)
        if (s !is GdspFetch.Chunk) return WorkoutFetch.None
        val parsed = WorkoutParsers.summary.parse(s.payload, ParseContext(s.headerTime))
        // The detail is asked for whether or not the summary parses, as Zepp does.
        val d = fetch(HistoryDataType.SPORT_DETAIL.code, since, zone)
        if (parsed !is ParseResult.Success) return WorkoutFetch.Failed("summary: ${(parsed as ParseResult.Failure).reason}")
        val summary = parsed.records.singleOrNull() ?: return WorkoutFetch.None
        // Zepp's next START: the summary's header time + 1000 ms.
        val next = s.headerTime + 1
        var retry = false
        val detail = when (d) {
            is GdspFetch.Chunk -> when (val r = WorkoutParsers.detail.parse(d.payload, ParseContext(summary.trackId))) {
                is ParseResult.Success -> r.records.singleOrNull()
                is ParseResult.Failure -> { log("history 6: ${r.reason}"); null }
            }
            is GdspFetch.Failed -> { log("history 6: ${d.reason}"); retry = true; null }
            GdspFetch.Empty -> null
        }
        return WorkoutFetch.Workout(summary, detail, next, retry)
    }

    companion object {
        /** Zepp's CYCLIC_SYNC result, -6, sent as the ACK byte `FA`. */
        const val CYCLIC_SYNC = -6
        /** Zepp's 20 s for the version query, START, `10 02`, the ACK reply and data inactivity. */
        const val TIMEOUT_MS = 20_000L
        private const val TRAILING_MS = 300L
        private const val CRC_BYTES = 4
        /** Types Zepp doesn't size-check. */
        private val SIZE_UNCHECKED = setOf(60, 80, 84)
    }
}

/** One workout as Zepp's `pxk` fetches it: the summary, then its detail from the same cursor. */
sealed interface WorkoutFetch {
    data object None : WorkoutFetch
    data class Failed(val reason: String, val fastRecharge: Boolean = false) : WorkoutFetch
    /** [retryDetail] is set when the detail exchange failed (worth fetching again), not when it was empty or unreadable. */
    data class Workout(val summary: WorkoutSummary, val detail: WorkoutDetail?, val nextCursor: Long, val retryDetail: Boolean = false) : WorkoutFetch
}

internal fun isZlib(b: ByteArray): Boolean =
    b.size >= 2 && (b[0].toInt() and 0x0F) == 8 && ((b[0].toInt() and 0xFF) * 256 + (b[1].toInt() and 0xFF)) % 31 == 0

internal fun inflate(b: ByteArray): ByteArray {
    val inflater = java.util.zip.Inflater()
    try {
        inflater.setInput(b)
        val out = java.io.ByteArrayOutputStream(b.size * 8)
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("truncated zlib stream")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
