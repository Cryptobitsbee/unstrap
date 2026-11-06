package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the sync worker makes of the requests queued: kept, merged or dropped as stale. */
class SyncPlanTest {
    private val now = Any()
    private val old = Any()

    @Test fun `a full request covers pushed types`() {
        val p = SyncPlan.of(listOf(SyncRequest.Types(setOf(1), now), SyncRequest.Full(manual = false, session = now)), now)
        assertTrue(p.full)
        assertNull(p.only)
    }

    @Test fun `pushed types merge into one sync of those types`() {
        val p = SyncPlan.of(listOf(SyncRequest.Types(setOf(1), now), SyncRequest.Types(setOf(2, 3), now)), now)
        assertFalse(p.full)
        assertEquals(setOf(1, 2, 3), p.only)
    }

    @Test fun `stale auto and type requests are dropped on a new session`() {
        val p = SyncPlan.of(listOf(SyncRequest.Types(setOf(1), old), SyncRequest.Full(manual = false, session = old)), now)
        assertTrue(p.isEmpty)
    }

    @Test fun `a kept Sync now runs on whatever session is current`() {
        val p = SyncPlan.of(listOf(SyncRequest.Full(manual = true, session = null)), now)
        assertTrue(p.full)
        assertTrue(p.manual)
    }

    @Test fun `with no session only a Sync now is left to keep`() {
        val p = SyncPlan.of(
            listOf(SyncRequest.Full(manual = true, session = null), SyncRequest.Types(setOf(1), old), SyncRequest.Full(false, old)),
            null,
        )
        assertTrue(p.manual)
        assertTrue(SyncPlan.of(listOf(SyncRequest.Types(setOf(1), old), SyncRequest.Full(false, old)), null).isEmpty)
    }

    @Test fun `an auto sync is not manual`() {
        assertFalse(SyncPlan.of(listOf(SyncRequest.Full(manual = false, session = now)), now).manual)
    }

    @Test fun `a dump is not a sync`() {
        assertTrue(SyncPlan.of(listOf(SyncRequest.Dump(1, null)), now).isEmpty)
    }
}
