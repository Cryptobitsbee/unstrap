package org.openstrap.app.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.WidgetKind

/** The version a running widget watches: every bump counts, even from several threads at once. */
class WidgetRefreshTest {
    @Test fun `bumps from many threads are all counted`() = runBlocking {
        val kind = WidgetKind.Steps
        val before = WidgetRefresh.version(kind).value
        (1..8).map { async(Dispatchers.Default) { repeat(500) { WidgetRefresh.bump(kind) } } }.awaitAll()
        assertEquals(before + 4000, WidgetRefresh.version(kind).value)
    }
}
