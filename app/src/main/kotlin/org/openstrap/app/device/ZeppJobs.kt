package org.openstrap.app.device

import org.openstrap.protocol.history.HistoryTypeSpec
import org.openstrap.protocol.history.HistoryTypeSpecs
import org.openstrap.protocol.services.DeviceFeatures
import org.openstrap.protocol.services.ReadinessCodec

/** One job of Zepp 10.10.5's history sync. */
internal sealed interface ZeppJob {
    /** A GDSP type, fetched on 0x004B. */
    data class Gdsp(val spec: HistoryTypeSpec) : ZeppJob

    /** A readiness module (0x0049) request since a cursor: `08` score, `0C` stress threshold, `06` data. */
    data class Readiness(val op: Int) : ZeppJob

    /** PAI from the PAI module (0x0022 `0C`), Zepp's `whx`. */
    data object Pai : ZeppJob
}

/**
 * Zepp 10.10.5's job list and order (codemap sync-zepp-10.10.5 §3, sync-zepp-vs-unstrap §4), with its gates. On the
 * Helio Strap (firmware 3.27.0.1) it gives 58, 66, 72, 1, readiness `08`, 5→6, `0C`, `06`, 73, 19, 18, 37, 38, 39, 59,
 * PAI, 56, 57, 70, 61, 74, 76, 87, 88, 78, 44 (once a day).
 *
 * Not here because they never run on this strap: the AF group (the cloud doesn't allow it), 86 and the dynamic
 * readiness score (no handler), BioCharge (no 0x0051), 2 (config flag off), 96 (not advertised), the HLD, BP and
 * weight types. Zepp never fetches 46, 43 or 7 from it.
 */
internal object ZeppJobs {
    /**
     * What Zepp's gates read. [features] null (no 0x0028 reply) falls back to the type being [advertised].
     * [readinessVersion] and [paiVersion] are the modules' version replies, null when not asked or not listed.
     */
    class Gates(
        val advertised: Set<Int>?,
        val features: DeviceFeatures?,
        val hasEndpoint: (Int) -> Boolean,
        val readinessVersion: Int?,
        val paiVersion: Int?,
        val sleepPlan: Boolean,
        val statisticsDue: Boolean,
    ) {
        fun bit(bit: Int, code: Int): Boolean = features?.group2(bit) ?: (advertised?.contains(code) == true)

        fun mapped(code: Int): Boolean = advertised?.contains(code) == true
    }

    // One line per Zepp job, in its order: the length is the list.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber")
    fun list(g: Gates): List<ZeppJob> = buildList {
        fun gdsp(code: Int) {
            HistoryTypeSpecs.forCode(code)?.takeIf { it.parser != null }?.let { add(ZeppJob.Gdsp(it)) }
        }
        val readiness = g.hasEndpoint(org.openstrap.protocol.services.Endpoints.READINESS)
        if (g.bit(49, 58)) gdsp(58)
        if (g.sleepPlan) gdsp(66)
        if (g.bit(65, 72)) gdsp(72)
        gdsp(1)
        if (readiness) add(ZeppJob.Readiness(ReadinessCodec.SCORE))
        gdsp(5)
        if (readiness && (g.readinessVersion ?: 0) >= 3) add(ZeppJob.Readiness(ReadinessCodec.STRESS_THRESHOLD))
        if (readiness) add(ZeppJob.Readiness(ReadinessCodec.DATA))
        if (g.bit(66, 73)) gdsp(73)
        if (g.bit(3, 19)) { gdsp(19); gdsp(18) }
        if (g.bit(4, 37)) {
            gdsp(37)
            if (g.bit(21, 38)) { gdsp(38); gdsp(39) }
        }
        // Sports effect through `vf70` when bit 10 (FirstBeat) is clear.
        if (g.features?.group2(10) != true && g.bit(50, 59)) gdsp(59)
        if ((g.paiVersion ?: 0) >= 5 && g.hasEndpoint(org.openstrap.protocol.services.Endpoints.PAI)) add(ZeppJob.Pai) else gdsp(13)
        gdsp(56)
        gdsp(57)
        if (g.bit(62, 70)) gdsp(70)
        if (g.bit(52, 61)) gdsp(61)
        if (g.bit(68, 74)) gdsp(74)
        for (code in listOf(76, 87, 88, 78)) if (g.mapped(code)) gdsp(code)
        if (g.bit(40, 44) && g.statisticsDue) gdsp(44)
    }

    /** A sync on demand (a data-available push) runs only the GDSP jobs of [codes], in the same order. */
    fun only(jobs: List<ZeppJob>, codes: Set<Int>): List<ZeppJob> = jobs.filter { it is ZeppJob.Gdsp && it.spec.code in codes }
}
