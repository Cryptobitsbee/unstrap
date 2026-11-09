package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.protocol.hexToBytes
import org.openstrap.protocol.services.DeviceFeatures
import org.openstrap.protocol.services.Endpoints

class ZeppJobsTest {
    /** The Helio Strap's 0x0028 reply on firmware 3.27.0.1 (probed 2026-11-07). */
    private val features = DeviceFeatures.parseMessage("02010b01190998022c020385fe4016020800".hexToBytes())!!

    /** Its GDSP version reply's type map. */
    private val advertised = setOf(1, 2, 5, 6, 7, 8, 11, 13, 17, 18, 19, 30, 37, 38, 39, 44, 46, 48, 56, 57, 58, 59, 61, 63, 64, 66, 70, 72, 73, 74, 76, 78, 85, 87, 88)

    private fun gates(
        endpoints: Set<Int> = setOf(Endpoints.READINESS, Endpoints.PAI, Endpoints.SLEEP),
        readiness: Int? = 3,
        pai: Int? = 5,
        statisticsDue: Boolean = true,
    ) = ZeppJobs.Gates(advertised, features, endpoints::contains, readiness, pai, sleepPlan = true, statisticsDue = statisticsDue)

    private fun names(jobs: List<ZeppJob>) = jobs.map {
        when (it) {
            is ZeppJob.Gdsp -> it.spec.code.toString()
            is ZeppJob.Readiness -> "r%02x".format(it.op)
            ZeppJob.Pai -> "pai"
        }
    }

    @Test
    fun theStrapsJobListIsZepps() {
        assertEquals(
            listOf(
                "58", "66", "72", "1", "r08", "5", "r0c", "r06", "73", "19", "18", "37", "38", "39", "59", "pai",
                "56", "57", "70", "61", "74", "76", "87", "88", "78", "44",
            ),
            names(ZeppJobs.list(gates())),
        )
    }

    @Test
    fun gatesThatChangeTheList() {
        // Statistics once a day, stress threshold from readiness version 3, PAI on GDSP 13 below PAI version 5.
        val jobs = names(ZeppJobs.list(gates(readiness = 2, pai = 4, statisticsDue = false)))
        assertTrue("r0c" !in jobs && "44" !in jobs && "pai" !in jobs && "13" in jobs)
        // No readiness module, no readiness jobs.
        assertTrue(names(ZeppJobs.list(gates(endpoints = emptySet()))).none { it.startsWith("r") || it == "pai" })
        // Never: 2, 46, 43, 86, 7.
        assertTrue(names(ZeppJobs.list(gates())).none { it in setOf("2", "46", "43", "86", "7") })
    }

    @Test
    fun onDemandRunsOnlyTheAskedGdspTypes() {
        assertEquals(listOf("5"), names(ZeppJobs.only(ZeppJobs.list(gates()), setOf(5, 6, 79))))
        assertEquals(listOf("70"), names(ZeppJobs.only(ZeppJobs.list(gates()), setOf(70))))
    }
}
