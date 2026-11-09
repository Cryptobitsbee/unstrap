package org.openstrap.protocol.history

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.protocol.ByteReader
import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.crypto.crc32
import java.time.ZoneOffset

/**
 * A simulated strap speaking the device side of GDSP (04 §1): START replies with a size and header time,
 * indexed data packets on 0005, `10 02 st crc32`, and ACKs. It serves one recorded workout (types 5 and 6).
 */
private class SimulatedGdspStrap(private val workouts: Map<Long, Pair<ByteArray, ByteArray>>) {
    val control = Channel<GdspMessage>(Channel.UNLIMITED)
    val data = Channel<ByteArray>(Channel.UNLIMITED)

    /** (type, since epoch) of every START, in order. */
    val starts = mutableListOf<Pair<Int, Long>>()
    val acks = mutableListOf<Int?>()
    var fastRecharge = false
    /** Number of STARTs to leave unanswered. */
    var silentStarts = 0
    /** Number of STARTs to refuse with INVALID_STATE, as when an earlier START is still open. */
    var invalidStateStarts = 0
    /** A status for the next START reply instead of success. */
    var nextStatus: Int? = null
    /** Leave out the packet with this index in the next transfer. */
    var dropPacket: Int? = null
    /** Send every packet twice in the next transfer. */
    var doublePackets = false
    /** Send a wrong CRC in the next `10 02`. */
    var badCrc = false
    /** A fixed header time for every START reply. */
    var header: Long? = null
    /** Pushed `11 <type>` before the next START reply. */
    var pushBeforeStart: Int? = null
    private var pending: ByteArray? = null

    suspend fun send(bytes: ByteArray) {
        when (bytes[0].toInt()) {
            FetchCodec.OP_START -> {
                val type = bytes[1].toInt() and 0xFF
                val since = GdspTime.read(ByteReader(bytes, 2)).toEpochSecond()
                starts += type to since
                pushBeforeStart?.let { pushBeforeStart = null; reply(byteArrayOf(0x11, it.toByte())) }
                if (fastRecharge) return reply(byteArrayOf(0x10, 1, GdspStatus.FAST_RECHARGE_MODE.code.toByte()))
                if (silentStarts > 0) { silentStarts--; return }
                if (invalidStateStarts > 0) { invalidStateStarts--; return reply(byteArrayOf(0x10, 1, GdspStatus.INVALID_STATE.code.toByte())) }
                nextStatus?.let { nextStatus = null; return reply(byteArrayOf(0x10, 1, it.toByte())) }
                val track = workouts.keys.sorted().firstOrNull { it >= since }
                val payload = track?.let { workouts.getValue(it).let { (s, d) -> if (type == 5) s else if (type == 6) d else null } }
                pending = payload
                val w = ByteWriter().u8(0x10).u8(1).u8(1).u32((payload?.size ?: 0).toLong())
                GdspTime.of(header ?: track ?: since, ZoneOffset.UTC).writeTo(w)
                reply(w.u8(0).toByteArray())
            }
            FetchCodec.OP_TRANSFER -> {
                val p = pending ?: ByteArray(0)
                p.toList().chunked(19).forEachIndexed { i, c ->
                    if (i == dropPacket) return@forEachIndexed
                    val packet = byteArrayOf(i.toByte()) + c.toByteArray()
                    data.send(packet)
                    if (doublePackets) data.send(packet)
                }
                dropPacket = null
                doublePackets = false
                reply(ByteWriter().u8(0x10).u8(2).u8(1).u32(crc32(p) xor if (badCrc) 1L else 0L).toByteArray())
                badCrc = false
            }
            FetchCodec.OP_ACK -> {
                acks += bytes.getOrNull(1)?.toInt()?.and(0xFF)
                pending = null
                reply(byteArrayOf(0x10, 3, 1))
            }
        }
    }

    private suspend fun reply(bytes: ByteArray) = control.send(FetchCodec.parse(bytes))
}

class GdspClientTest {
    private val trackId = 1_792_141_453L
    private val zone = ZoneOffset.UTC
    private fun fixture(name: String) = javaClass.getResourceAsStream("/workout/$name")!!.readBytes()

    private fun strap() = SimulatedGdspStrap(mapOf(trackId to (fixture("summary_5.bin") to fixture("detail_6.bin"))))

    @Test
    fun detailFollowsSummaryWithTheSameCursor() = runTest {
        val strap = strap()
        val client = GdspClient(strap::send, strap.control, strap.data)
        val since = trackId - 3600

        val w = client.fetchWorkout(since, zone) as WorkoutFetch.Workout
        assertEquals(trackId, w.summary.trackId)
        assertEquals(223, w.summary.sportType)
        val detail = w.detail!!
        assertEquals(trackId, detail.timestamp)
        assertEquals(1307, detail.heartRate.size)
        assertEquals(trackId + 1, w.nextCursor)
        // Type 6 is asked from the summary's cursor, not from a cursor of its own; both are acknowledged.
        assertEquals(listOf(5 to since, 6 to since), strap.starts)
        assertEquals(listOf<Int?>(1, 1), strap.acks)

        // The next round starts after the workout and finds nothing (closed with an ACK, no detail START).
        assertEquals(WorkoutFetch.None, client.fetchWorkout(w.nextCursor, zone))
        assertEquals(5 to trackId + 1, strap.starts.last())
        assertEquals(3, strap.acks.size)
    }

    @Test
    fun fastRechargeIsAcknowledgedWith33() = runTest {
        val strap = strap().apply { fastRecharge = true }
        val client = GdspClient(strap::send, strap.control, strap.data)
        val w = client.fetchWorkout(trackId, zone) as WorkoutFetch.Failed
        assertTrue(w.fastRecharge)
        val f = client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone) as GdspFetch.Failed
        assertTrue(f.fastRecharge)
        // Zepp closes every START with an ACK carrying the result, a refusal too.
        assertEquals(listOf<Int?>(0x33, 0x33), strap.acks)
    }

    @Test
    fun dataAvailablePushIsReported() = runTest {
        val strap = strap().apply { pushBeforeStart = 5 }
        val pushes = mutableListOf<GdspMessage.DataAvailable>()
        val client = GdspClient(strap::send, strap.control, strap.data, onDataAvailable = { pushes += it })
        assertTrue(client.fetchWorkout(trackId, zone) is WorkoutFetch.Workout)
        assertEquals(listOf(5, 6, 79), pushes.single().typesToSync)
    }

    @Test
    fun aFailedVersionQueryIsLoggedAndReadsAsNone() = runTest {
        val strap = strap()
        val logs = mutableListOf<String>()
        val client = GdspClient({ throw IllegalStateException("link lost") }, strap.control, strap.data, log = { logs += it })
        assertNull(client.queryVersion())
        assertTrue(logs.toString(), logs.any { "version query failed" in it && "link lost" in it })
    }

    @Test
    fun anUnansweredStartIsAcknowledgedWith04() = runTest {
        val strap = strap().apply { silentStarts = 1 }
        val logs = mutableListOf<String>()
        val client = GdspClient(strap::send, strap.control, strap.data, log = { logs += it })
        // A timeout must not surface as a cancellation, which would abort the whole sync.
        val f = client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone)
        assertEquals(GdspStatus.OPERATION_FAILED.code, (f as GdspFetch.Failed).ack)
        assertTrue(logs.toString(), logs.any { it.startsWith("history: start ${HistoryDataType.ACTIVITY.code}: ") && "no reply" in it })
        // The next exchange still works.
        assertEquals(GdspFetch.Empty, client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone))
        assertEquals(listOf<Int?>(4, 1), strap.acks)
    }

    @Test
    fun aRefusedStartIsAcknowledgedWithItsStatus() = runTest {
        val strap = strap().apply { invalidStateStarts = 1 }
        val client = GdspClient(strap::send, strap.control, strap.data)
        val f = client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone)
        assertEquals(GdspStatus.INVALID_STATE.code, (f as GdspFetch.Failed).ack)
        // One START, no retry inside the exchange: the caller's loop retries.
        assertEquals(1, strap.starts.size)
        assertEquals(listOf<Int?>(5), strap.acks)
    }

    @Test
    fun anUnknownStartStatusCountsAsSuccess() = runTest {
        val strap = strap().apply { nextStatus = 0x77 }
        val client = GdspClient(strap::send, strap.control, strap.data)
        assertEquals(GdspFetch.Empty, client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone))
        assertEquals(listOf<Int?>(1), strap.acks)
    }

    @Test
    fun emptyStartIsAcknowledged() = runTest {
        val strap = strap()
        val client = GdspClient(strap::send, strap.control, strap.data)
        assertEquals(GdspFetch.Empty, client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone))
        assertEquals(listOf<Int?>(1), strap.acks)
    }

    @Test
    fun aMissingPacketIsAcknowledgedWith06() = runTest {
        val strap = strap().apply { dropPacket = 2 }
        val client = GdspClient(strap::send, strap.control, strap.data)
        val f = client.fetch(5, trackId, zone)
        assertEquals(GdspStatus.PACKET_INDEX_ERROR.code, (f as GdspFetch.Failed).ack)
        assertEquals(listOf<Int?>(6), strap.acks)
    }

    @Test
    fun aRepeatedPacketIsIgnored() = runTest {
        val strap = strap().apply { doublePackets = true }
        val client = GdspClient(strap::send, strap.control, strap.data)
        val c = client.fetch(5, trackId, zone) as GdspFetch.Chunk
        assertEquals(fixture("summary_5.bin").toList(), c.payload.toList())
        assertEquals(listOf<Int?>(1), strap.acks)
    }

    @Test
    fun aWrongCrcIsAcknowledgedWith08() = runTest {
        val strap = strap().apply { badCrc = true }
        val client = GdspClient(strap::send, strap.control, strap.data)
        assertEquals(GdspStatus.CRC_MISMATCH.code, (client.fetch(5, trackId, zone) as GdspFetch.Failed).ack)
        assertEquals(listOf<Int?>(8), strap.acks)
    }

    @Test
    fun aHeaderNotLaterAfterTheCursorMovedIsCyclic() = runTest {
        val strap = strap().apply { header = trackId }
        val client = GdspClient(strap::send, strap.control, strap.data)
        val f = client.fetch(5, trackId - 60, zone, PreviousChunk(trackId - 120, trackId)) as GdspFetch.Failed
        assertTrue(f.cyclic)
        assertEquals(listOf<Int?>(0xFA), strap.acks)
        // The same cursor again is not cyclic.
        assertTrue(client.fetch(5, trackId - 60, zone, PreviousChunk(trackId - 60, trackId)) is GdspFetch.Chunk)
    }
}
