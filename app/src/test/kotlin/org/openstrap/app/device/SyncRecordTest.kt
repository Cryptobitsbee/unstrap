package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sync record behind the chip and Today's stuck sync: attempts, the last good sync and when failing began. */
class SyncRecordTest {
    private val running = SyncState.Running("Syncing")

    @Test fun `an attempt is counted once, at its first running step`() {
        val r = SyncRecord().next(SyncState.Idle, running, 100).next(running, SyncState.Running("Step 2"), 160)
        assertEquals(100L, r.lastAttempt)
    }

    @Test fun `a good sync clears the failure`() {
        val failed = SyncRecord().next(SyncState.Idle, running, 100).next(running, SyncState.Failed("x"), 130)
        assertEquals(130L, failed.failingSince)
        val done = failed.next(SyncState.Failed("x"), running, 200).next(running, SyncState.Done(260), 260)
        assertEquals(260L, done.lastSuccess)
        assertEquals(200L, done.lastAttempt)
        assertNull(done.failingSince)
    }

    @Test fun `failing keeps the time of the first failure`() {
        var r = SyncRecord(lastSuccess = 50)
        var previous: SyncState = SyncState.Done(50)
        listOf(100L, 3700L, 7300L).forEach { t ->
            r = r.next(previous, running, t).next(running, SyncState.Failed("x"), t + 30)
            previous = SyncState.Failed("x")
        }
        assertEquals(130L, r.failingSince)
        assertEquals(7300L, r.lastAttempt)
        assertEquals(50L, r.lastSuccess)
    }

    @Test fun `a failure seen without its running state is an attempt of its own`() {
        val r = SyncRecord(lastAttempt = 10).next(SyncState.Done(10), SyncState.Failed("x"), 500)
        assertEquals(500L, r.lastAttempt)
        assertEquals(500L, r.failingSince)
    }

    @Test fun `a full sync's success ends the failure and a failure keeps the first time`() {
        val failing = SyncRecord(lastSuccess = 50).attempted(100).failed(130).attempted(200).failed(230)
        assertEquals(130L, failing.failingSince)
        assertEquals(50L, failing.lastSuccess)
        assertEquals(200L, failing.lastAttempt)
        val ok = failing.attempted(300).succeeded(330)
        assertEquals(330L, ok.lastSuccess)
        assertEquals(300L, ok.lastAttempt)
        assertNull(ok.failingSince)
    }
}
