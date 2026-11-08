package org.openstrap.protocol.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.protocol.hexToBytes
import org.openstrap.protocol.history.HistoryParsers
import org.openstrap.protocol.history.ParseContext
import org.openstrap.protocol.history.ParseResult
import org.openstrap.protocol.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZeppChannelJobsTest {
    @Test
    fun versions() {
        // Probed on firmware 3.27.0.1.
        assertEquals(3, ReadinessCodec.parseVersion("0100033c3c01fe".hexToBytes()))
        assertEquals(5, PaiCodec.parseVersion("010005".hexToBytes()))
        assertEquals(3, SleepModuleCodec.parseVersion("020301312e302e3200342e302e313700".hexToBytes()))
        assertNull(PaiCodec.parseVersion("0d00".hexToBytes()))
    }

    @Test
    fun requests() {
        assertEquals("0c00e1f505", PaiCodec.query(100_000_000).toHex())
        assertEquals("0600e1f505", ReadinessCodec.request(ReadinessCodec.DATA, 100_000_000).toHex())
        assertEquals("0c00e1f505", ReadinessCodec.request(ReadinessCodec.STRESS_THRESHOLD, 100_000_000).toHex())
    }

    @Test
    fun readinessRecordsKeptWhole() {
        // Stress threshold: u32 time, i8 tz, u8 threshold, u32 update, u32 reserved.
        val reply = "0d0002" + "00e1f505" + "10" + "32" + "01e1f505" + "00000000" + "80e1f505" + "10" + "33" + "81e1f505" + "00000000"
        val records = ReadinessCodec.parseRecords(ReadinessCodec.STRESS_THRESHOLD, reply.hexToBytes())!!
        assertEquals(listOf(100_000_000L, 100_000_128L), records.map { it.first })
        assertEquals(14, records[0].second.size)
        assertEquals(emptyList<Any>(), ReadinessCodec.parseRecords(ReadinessCodec.DATA, "070200".hexToBytes()))
        assertNull(ReadinessCodec.parseRecords(ReadinessCodec.DATA, "070500".hexToBytes()))
        assertNull(ReadinessCodec.parseRecords(ReadinessCodec.DATA, "0d0000".hexToBytes()))
    }

    private fun paiRecord(time: Int, daily: Float, version: Int = 5) = ByteBuffer.allocate(102).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(version.toByte()); putInt(time); putInt(16)
        repeat(10) { putFloat(0f) }
        repeat(3) { putShort(0) }
        putFloat(daily); putFloat(80f); putShort(52); putShort(180); put(0); put(0)
    }.array()

    @Test
    fun paiRecordsAre102Bytes() {
        val reply = byteArrayOf(0x0D, 0, 2) + paiRecord(1_000, 12.5f, version = 3) + paiRecord(87_400, 3f)
        val records = PaiCodec.parse(reply)!!
        assertEquals(listOf(1_000L, 87_400L), records.map { it.timestamp })
        assertEquals(listOf(12.5f, 3f), records.map { it.dailyPai })
        assertEquals(listOf(3, 5), records.map { it.version })
        assertEquals(52, records[0].restHr)
        // Seen on firmware 3.27.0.1 with nothing new: Zepp's codec gives an empty list, not an error.
        assertEquals(emptyList<Any>(), PaiCodec.parse(byteArrayOf(0x0D, 1)))
        assertNull(PaiCodec.parse(byteArrayOf(0x09, 0, 0)))
        assertEquals(emptyList<Any>(), PaiCodec.parse(byteArrayOf(0x0D, 0, 0)))
    }

    @Test
    fun sleepPlanStructs() {
        val struct = ByteBuffer.allocate(59).order(ByteOrder.LITTLE_ENDIAN).putInt(1_000).array()
        val two = struct + ByteBuffer.allocate(59).order(ByteOrder.LITTLE_ENDIAN).putInt(2_000).array()
        val r = HistoryParsers.sleepPlan.parse(two, ParseContext(500)) as ParseResult.Success
        assertEquals(listOf(1_000L, 2_000L), r.records.map { it.timestamp })
        assertEquals(2_001L, r.nextCursor)
        assertEquals(59, r.records[0].raw.size)
        val padded = HistoryParsers.sleepPlan.parse(ByteArray(64), ParseContext(500)) as ParseResult.Success
        assertEquals(64, padded.records.single().raw.size)
    }
}
