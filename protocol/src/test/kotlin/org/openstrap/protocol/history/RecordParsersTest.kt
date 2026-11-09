package org.openstrap.protocol.history

import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordParsersTest {

    private val t0 = 1_760_000_000L
    private val ctx = ParseContext(headerTime = t0)

    private fun <T : HistoryRecord> ok(r: ParseResult<T>): ParseResult.Success<T> =
        r as? ParseResult.Success<T> ?: run { fail("expected success, got $r"); throw AssertionError() }

    private fun assertFailure(r: ParseResult<*>) = assertTrue("expected failure, got $r", r is ParseResult.Failure)

    private fun w() = ByteWriter()
    private fun ByteWriter.f32(v: Float) = u32(v.toRawBits().toLong() and 0xFFFFFFFFL)
    private fun ByteWriter.repeat(n: Int, b: Int) = apply { repeat(n) { u8(b) } }

    // ---- activity ----

    @Test
    fun activityModeIsLowNibble() {
        val p = w().u8(0x73).u8(0).u8(0).u8(255).u8(5).u8(0).u8(0).u8(0)
            .u8(0x70).u8(56).u8(8).u8(87).u8(5).u8(0).u8(0).u8(0).toByteArray()
        val (notWorn, worn) = ok(HistoryParsers.activity.parse(p, ctx.copy(activityRecordSize = 8))).records
        assertEquals(ActivityMode.NOT_WORN, notWorn.mode); assertFalse(notWorn.isWorn); assertNull(notWorn.heartRate)
        assertEquals(ActivityMode.SILENT, worn.mode); assertEquals(0x70, worn.rawMode); assertTrue(worn.isWorn)
        assertEquals(8, worn.steps); assertEquals(87, worn.heartRate)
    }

    @Test
    fun activity4Byte() {
        val p = w().u8(1).u8(40).u8(12).u8(72) .u8(3).u8(0).u8(0).u8(254) .u8(0).u8(1).u8(0).u8(255) .u8(9).toByteArray()
        val res = ok(HistoryParsers.activity.parse(p, ctx))
        assertEquals(3, res.records.size)
        assertEquals(1, res.droppedBytes)
        val a = res.records[0]
        assertEquals(t0, a.timestamp)
        assertEquals(ActivityMode.WALKING, a.mode)
        assertEquals(40, a.intensity); assertEquals(12, a.steps); assertEquals(72, a.heartRate)
        assertNull(a.sleepExtra)
        assertEquals(t0 + 60, res.records[1].timestamp)
        assertNull(res.records[1].heartRate) // 254
        assertEquals(254, res.records[1].rawHeartRate)
        assertNull(res.records[2].heartRate) // 255
        assertEquals(t0 + 3 * 60, res.nextCursor)
    }

    @Test
    fun activity8Byte() {
        val p = w().u8(5).u8(2).u8(0).u8(55).u8(9).u8(8).u8(7).u8(6).toByteArray()
        val res = ok(HistoryParsers.activity.parse(p, ctx.copy(activityRecordSize = 8)))
        val a = res.records.single()
        assertEquals(55, a.heartRate)
        assertEquals(ActivitySleepExtra(9, 8, 7, 6), a.sleepExtra)
        // Size <= 4 falls back to 4
        assertEquals(2, ok(HistoryParsers.activity.parse(p, ctx.copy(activityRecordSize = 0))).records.size)
    }

    @Test
    fun activityEmpty() {
        val res = ok(HistoryParsers.activity.parse(ByteArray(0), ctx))
        assertTrue(res.records.isEmpty()); assertNull(res.nextCursor)
    }

    @Test
    fun todayExtended() {
        val rec = w().u8(1).u8(0).u8(2).u8(10).u8(20).u8(30).u8(40).u8(0).u8(90).u8(5).u16(7000)
        val p = rec.toByteArray() + rec.toByteArray()
        val res = ok(HistoryParsers.todayActivityExtended.parse(p, ctx))
        assertEquals(2, res.records.size)
        assertEquals(7000, res.records[1].distanceCm)
        assertEquals(90, res.records[0].steps)
        assertEquals(t0 + 60, res.records[1].timestamp)
        // Zepp's f40: header + N minutes + 1 s.
        assertEquals(t0 + 121, res.nextCursor)
        assertFailure(HistoryParsers.todayActivityExtended.parse(p.copyOf(23), ctx))
    }

    // ---- per-second HR ----

    @Test
    fun secHrV1() {
        val p = w().u32(t0).u8(80).u32(t0 + 1).u8(255).u8(1).toByteArray()
        val res = ok(HistoryParsers.secondHr.parse(p, ctx))
        assertEquals(80, res.records[0].heartRate)
        assertNull(res.records[1].heartRate)
        assertEquals(t0 + 2, res.nextCursor)
        assertEquals(1, res.droppedBytes)
    }

    @Test
    fun secHrV2() {
        val p = w().u32(t0).u16(4).u8(60).u8(61).u8(0xFF).u8(0xFF)
            .u32(t0 + 100).u16(0)
            .u32(t0 + 200).u16(2).u8(70).u8(71).toByteArray()
        val res = ok(HistoryParsers.secondHrV2.parse(p, ctx))
        assertEquals(listOf(t0, t0 + 1, t0 + 200, t0 + 201), res.records.map { it.timestamp })
        assertEquals(listOf(60, 61, 70, 71), res.records.map { it.heartRate })
        assertEquals(t0 + 202, res.nextCursor)
    }

    @Test
    fun secHrV2OnlyPaddingEndsTheLoop() {
        // Zepp's next is the last kept sample + 1 s: with none, the loop stops there.
        val p = w().u32(t0).u16(3).u8(0xFF).u8(0xFF).u8(0xFF).toByteArray()
        val res = ok(HistoryParsers.secondHrV2.parse(p, ctx))
        assertTrue(res.records.isEmpty())
        assertEquals(null, res.nextCursor)
    }

    @Test
    fun secHrV2Truncated() {
        val p = w().u32(t0).u16(10).u8(60).toByteArray()
        assertFailure(HistoryParsers.secondHrV2.parse(p, ctx))
    }

    // ---- spot HR ----

    @Test
    fun spotHr() {
        val p = w().u32(t0).u8(-8).u8(66).u32(t0 + 5).u8(4).u8(0).toByteArray()
        val res = ok(HistoryParsers.spotHr.parse(p, ctx))
        assertEquals(-8, res.records[0].tzQuarters)
        assertEquals(66, res.records[0].heartRate)
        assertNull(res.records[1].heartRate)
        assertFailure(HistoryParsers.spotHr.parse(p.copyOf(7), ctx))
    }

    // ---- sleep result ----

    private fun sleepRecord(
        ts: Long,
        mainStages: List<Triple<Int, Int, Int>>,
        shortCount: Int = 1,
        naps: List<Triple<Int, Int, Int>> = emptyList(),
    ): ByteArray {
        val b = w().u32(ts).u32(ts - 3600).u8(2).u8(1)
            .u16(-60).u16(420).u16(-70).u16(430)
            .u8(1).u8(1).u8(1).u8(58).u8(84).u8(shortCount)
        repeat(10) { b.u16(it * 10).u16(it * 10 + 5).u16(5) }
        b.u8(mainStages.size).u8(0)
        repeat(50) { i ->
            val s = mainStages.getOrNull(i)
            if (s == null) b.u16(0).u16(0).u8(0) else b.u16(s.first).u16(s.second).u8(s.third)
        }
        repeat(50) { i ->
            val s = naps.getOrNull(i)
            if (s == null) b.u16(0).u16(0).u8(0) else b.u16(s.first).u16(s.second).u8(s.third)
        }
        b.u16(90).u16(200).u16(80).u16(30)
        return b.toByteArray().also { assertEquals(HistoryParsers.SLEEP_RESULT_SIZE, it.size) }
    }

    @Test
    fun sleepResult() {
        val stages = listOf(Triple(-60, 0, 7), Triple(0, 120, 4), Triple(120, 200, 5), Triple(200, 300, 8), Triple(300, 420, 9))
        val p = sleepRecord(t0, stages) + sleepRecord(t0 + 86400, emptyList(), shortCount = 0)
        val res = ok(HistoryParsers.sleepResult.parse(p, ctx))
        assertEquals(2, res.records.size)
        val s = res.records[0]
        assertEquals(t0, s.timestamp); assertEquals(t0 - 3600, s.belongTime)
        assertEquals(2, s.version); assertTrue(s.hasMainSleep)
        assertEquals(-60, s.mainSleepStart); assertEquals(420, s.mainSleepEnd)
        assertEquals(-70, s.onBedTime); assertEquals(430, s.offBedTime)
        assertEquals(58, s.averageHeartRate); assertEquals(84, s.sleepScore)
        assertEquals(listOf(ShortSleep(0, 5, 5)), s.shortSleeps)
        assertEquals(5, s.mainStages.size)
        assertEquals(
            listOf(SleepStageType.AWAKE, SleepStageType.LIGHT, SleepStageType.DEEP, SleepStageType.REM, SleepStageType.UNKNOWN),
            s.mainStages.map { it.type },
        )
        assertEquals(9, s.mainStages[4].rawType)
        assertTrue(s.shortStages.isEmpty())
        assertEquals(90, s.remSleepLength); assertEquals(200, s.lightSleepLength)
        assertEquals(80, s.deepSleepLength); assertEquals(30, s.wakeLength)
        assertEquals(1, s.wakeCount)
        assertTrue(res.records[1].mainStages.isEmpty())
        assertEquals(t0 + 86401, res.nextCursor)

        // Truncated: partial record is dropped, not crashed on.
        val trunc = ok(HistoryParsers.sleepResult.parse(p.copyOf(594 + 100), ctx))
        assertEquals(1, trunc.records.size); assertEquals(100, trunc.droppedBytes)
    }

    @Test
    fun sleepTimesAreMinutesFromTheDayBefore() {
        // A night from 23:30 to 07:00, belonging to 2026-10-16 (belongTime is that day's local midnight).
        val zone = java.time.ZoneId.of("Asia/Dubai")
        val midnight = java.time.LocalDate.of(2026, 10, 16).atStartOfDay(zone).toEpochSecond()
        val raw = sleepRecord(midnight, listOf(Triple(1410, 1500, 4), Triple(1500, 1860, 5)), naps = listOf(Triple(2280, 2310, 4)))
            .also { it[4] = 0; it[5] = 0; it[6] = 0; it[7] = 0 }
        val b = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(4, midnight.toInt())
        val s = ok(HistoryParsers.sleepResult.parse(raw, ctx)).records.single()
        assertEquals(midnight, s.day(zone))
        assertEquals(midnight - 30 * 60, s.resolveTime(1410, zone))
        assertEquals(midnight + 7 * 3600, s.resolveTime(1860, zone))
        assertEquals(listOf(SleepStageType.LIGHT, SleepStageType.DEEP), s.mainStages.map { it.type })
        // Naps are every valid nap slot, whatever the nap stage count says (it's 0 here).
        assertEquals(listOf(SleepStage(2280, 2310, 4)), s.shortStages)
        assertEquals(midnight + 14 * 3600, s.resolveTime(s.shortStages[0].start, zone))
    }

    // ---- SpO2 ----

    @Test
    fun spo2Legacy() {
        val p = w().u32(t0).u8(0x80 or 97).u32(t0 + 60).u8(70).toByteArray()
        val res = ok(HistoryParsers.spo2Single.parse(p, ctx))
        assertEquals(97, res.records[0].spo2); assertTrue(res.records[0].isAuto); assertTrue(res.records[0].isPlausible)
        assertEquals(70, res.records[1].spo2); assertFalse(res.records[1].isAuto); assertFalse(res.records[1].isPlausible)
        assertNull(res.records[0].history)
    }

    @Test
    fun spo2Versioned() {
        val v = ctx.copy(spo2Versioned = true)
        val v1 = w().u8(1).u32(t0).u8(95).toByteArray()
        assertEquals(95, ok(HistoryParsers.spo2Single.parse(v1, v)).records.single().spo2)

        val v2 = w().u8(2).u32(t0).u8(0x80 or 99).repeat(60, 96).toByteArray()
        val r2 = ok(HistoryParsers.spo2Single.parse(v2, v)).records.single()
        assertEquals(99, r2.spo2); assertTrue(r2.isAuto); assertEquals(60, r2.history!!.size)

        assertFailure(HistoryParsers.spo2Single.parse(w().u8(7).u32(t0).u8(1).toByteArray(), v))
        assertTrue(ok(HistoryParsers.spo2Single.parse(ByteArray(0), v)).records.isEmpty())
    }

    @Test
    fun spo2VersionDetectedWithoutFeatureBit() {
        // Helio Strap 3.20 sent 131 bytes: `02` then two 65-byte readings, starting 02 97 3c d1 6a 63 ...
        val ts = 0x6AD13C97L
        val hw = w().u8(2).u32(ts).u8(99).repeat(60, 0).u32(ts + 600).u8(98).repeat(60, 0).toByteArray()
        assertEquals(131, hw.size)
        val res = ok(HistoryParsers.spo2Single.parse(hw, ParseContext(headerTime = ts)))
        assertEquals(listOf(99, 98), res.records.map { it.spo2 })
        assertEquals(listOf(ts, ts + 600), res.records.map { it.timestamp })

        // A legacy payload whose first time byte happens to be 2 is still read as legacy.
        val legacyTs = (t0 and 0xFFFFFF00L) or 2L
        val legacy = w().u32(legacyTs).u8(96).toByteArray()
        assertEquals(96, ok(HistoryParsers.spo2Single.parse(legacy, ParseContext(headerTime = legacyTs))).records.single().spo2)
    }

    @Test
    fun spo2Sleep() {
        val legacy = w().u32(t0).u8(94).u8(10).toByteArray()
        assertEquals(10, ok(HistoryParsers.spo2Sleep.parse(legacy, ctx)).records.single().time)
        val v2 = w().u8(2).u32(t0).u8(93).u8(4).repeat(6, 1).repeat(6, 2).repeat(8, 3).repeat(4, 4).toByteArray()
        val s = ok(HistoryParsers.spo2Sleep.parse(v2, ctx.copy(spo2Versioned = true))).records.single()
        assertEquals(93, s.spo2)
        assertEquals(3, s.v2!!.signalQuality[0].toInt())
        assertEquals(4, s.v2.extend.size)
    }

    @Test
    fun spo2OsaEventsAndResult() {
        val long = w().u32(t0).u8(5).repeat(240, 90).repeat(240, 60).toByteArray()
        val e = ok(HistoryParsers.spo2OsaEvent.parse(long, ctx)).records.single()
        assertEquals(5, e.spo2Decrease); assertEquals(240, e.heartRate!!.size)
        val short = w().u8(1).u32(t0).u8(3).toByteArray()
        assertNull(ok(HistoryParsers.spo2OsaEvent.parse(short, ctx.copy(spo2Versioned = true))).records.single().spo2)
        assertEquals(3, ok(HistoryParsers.spo2OsaEventAlt.parse(w().u32(t0).u8(3).toByteArray(), ctx)).records.single().spo2Decrease)

        val osa = w().u32(t0).u32(3600).u32(12).u8(80).u8(1).u8(1).u8(2)
        repeat(240) { osa.u32(t0 + it).u32(-4L and 0xFFFFFFFFL).u32(30) }
        val o = ok(HistoryParsers.osaResult.parse(osa.toByteArray(), ctx)).records.single()
        assertEquals(80, o.odiScore); assertEquals(-4, o.events[0].odValue); assertEquals(240, o.events.size)
    }

    // ---- stress ----

    @Test
    fun stress() {
        val single = w().u32(t0).u8(42).u32(t0 + 9).u8(0).toByteArray()
        val s = ok(HistoryParsers.stressSingle.parse(single, ctx))
        assertEquals(42, s.records[0].stress); assertTrue(s.records[1].isLikelyGap)
        assertFailure(HistoryParsers.stressSingle.parse(single.copyOf(9), ctx))

        val all = byteArrayOf(10, 20, 0xFF.toByte())
        val a = ok(HistoryParsers.stressAllDay.parse(all, ctx))
        assertEquals(listOf(t0, t0 + 60, t0 + 120), a.records.map { it.timestamp })
        assertTrue(a.records[2].isLikelyGap); assertTrue(a.records.all { it.allDay })
        assertEquals(t0 + 180 + 1, a.nextCursor)
    }

    // ---- HRV / resting HR family ----

    @Test
    fun hrvAndRestingFamily() {
        val rmssd = ok(HistoryParsers.hrvRmssd.parse(w().u32(t0).u8(4).u8(48).toByteArray(), ctx)).records.single()
        assertEquals(HrvRmssd(t0, 4, 48), rmssd)

        // Captured from the strap: ts 1792106880, tz +4 h, 60.56 ms.
        val sdnn = ok(HistoryParsers.hrvSdnn.parse("8061d16a10a8170000".hexToBytes(), ctx)).records.single()
        assertEquals(HrvSdnn(1_792_106_880, 16, 6056), sdnn)
        assertEquals(60.56, sdnn.sdnnMs, 1e-9)

        val rest = ok(HistoryParsers.restHrRealtime.parse(w().u32(t0).u8(8).u8(55).u8(57).u8(2).toByteArray(), ctx))
        assertEquals(RestHrRealtime(t0, 8, 55, 57, 2), rest.records.single())

        assertEquals(RestingHeartRate(t0, 0, 52), ok(HistoryParsers.restingHr.parse(w().u32(t0).u8(0).u8(52).toByteArray(), ctx)).records.single())
        assertEquals(DailyMaxHeartRate(t0, 0, 181), ok(HistoryParsers.dailyMaxHr.parse(w().u32(t0).u8(0).u8(181).toByteArray(), ctx)).records.single())

        // Truncated record dropped gracefully.
        val trunc = ok(HistoryParsers.hrvRmssd.parse(w().u32(t0).u8(4).toByteArray(), ctx))
        assertTrue(trunc.records.isEmpty()); assertEquals(5, trunc.droppedBytes)
    }

    // ---- respiratory ----

    @Test
    fun respiratory() {
        val r = ok(HistoryParsers.sleepBreathRate.parse(w().u32(t0).u8(4).u8(14).u8(20).u8(1).toByteArray(), ctx)).records.single()
        assertEquals(SleepBreathRate(t0, 4, 14, 20, 1), r)
        val ev = w().u32(t0).u8(4).repeat(564, 7).toByteArray()
        val e = ok(HistoryParsers.sleepRespiratoryEvent.parse(ev, ctx)).records.single()
        assertEquals(t0, e.timestamp); assertEquals(569, e.raw.size)
    }

    // ---- recovery HR / temperature ----

    @Test
    fun recoveryHr() {
        val p = w().u32(t0).u32(3).u8(150).u8(140).u8(130).u32(t0 + 5000).u32(10).u8(1).toByteArray()
        val res = ok(HistoryParsers.recoveryHr.parse(p, ctx))
        val rec = res.records.single() // second block truncated -> stop
        assertEquals(t0, rec.trackId); assertEquals(listOf(150, 140, 130), rec.samples)
        assertEquals(t0 + 1, res.nextCursor)
    }

    @Test
    fun legacyTemperature() {
        val p = w().u8(1).u8(36).u32(t0).toByteArray()
        assertEquals(LegacyTemperature(t0, 36), ok(HistoryParsers.legacyTemperature.parse(p, ctx)).records.single())
    }

    // ---- PAI ----

    private fun paiPayload(version: Int): ByteArray {
        val b = w().u8(version).u32(t0).u32(8)
        repeat(7) { b.f32(it + 0.5f) }
        b.f32(1.5f).f32(2.5f).f32(3.5f).u16(10).u16(20).u16(30).f32(7.5f).f32(99.25f).u16(55).u16(190).u8(3).u8(1)
        if (version >= 2) repeat(7) { b.f32(10f + it) }
        if (version >= 4) b.u8(1).u8(35)
        if (version >= 5) b.u8(100).u8(130).u8(160)
        return b.toByteArray()
    }

    @Test
    fun paiVersions() {
        val v1 = ok(HistoryParsers.pai.parse(paiPayload(1), ctx)).records.single()
        assertEquals(t0, v1.timestamp); assertEquals(99.25f, v1.totalPai)
        assertEquals(listOf(0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5.5f, 6.5f), v1.activityScores)
        assertEquals(190, v1.maxHr); assertNull(v1.nextActivityScores); assertNull(v1.gender)

        val v5 = HistoryParsers.pai.parse(paiPayload(5), ctx)
        val r5 = ok(v5).records.single()
        assertEquals(16f, r5.nextActivityScores!![6]); assertEquals(35, r5.age); assertEquals(160, r5.highZoneLimit)
        assertEquals(t0 + 1, ok(v5).nextCursor)

        assertFailure(HistoryParsers.pai.parse(paiPayload(5).copyOf(80), ctx))
        assertFailure(HistoryParsers.pai.parse(paiPayload(1).copyOf(30), ctx))
    }

    // ---- generic robustness ----

    @Test
    fun allParsersSurviveGarbage() {
        val garbage = ByteArray(37) { (it * 37 + 11).toByte() }
        for (spec in HistoryTypeSpecs.all) {
            val p = spec.parser ?: continue
            for (len in 0..garbage.size) {
                for (c in listOf(ctx, ctx.copy(spo2Versioned = true, activityRecordSize = 8))) {
                    p.parse(garbage.copyOf(len), c) // must not throw
                }
            }
        }
    }
}
