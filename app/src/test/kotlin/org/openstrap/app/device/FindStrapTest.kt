package org.openstrap.app.device

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.SettingsStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Find strap (03 §3.16) ends on any STOP, not only the app's own Stop: a ringing call ending sends the same `06`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FindStrapTest {
    @Test
    fun aPlainStopEndsFind() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OpenStrapDatabase::class.java).allowMainThreadQueries().build()
        try {
            val strap = StrapManager(context, SettingsStore(context), db.healthDao())
            strap.findingStrap.value = true
            // Not connected, so the STOP itself can't go out; Find strap still ends rather than waiting out its 60 s.
            runCatching { strap.stopBuzzing() }
            assertFalse(strap.findingStrap.value)
        } finally {
            db.close()
        }
    }

    @Test
    fun aStartThatFailsLeavesFindingOff() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OpenStrapDatabase::class.java).allowMainThreadQueries().build()
        try {
            val strap = StrapManager(context, SettingsStore(context), db.healthDao())
            // Not connected, so the start is refused: Find strap is set before it goes out and rolled back after.
            assertTrue(runCatching { strap.findStrap() }.exceptionOrNull() is StrapCommandException)
            assertFalse(strap.findingStrap.value)
        } finally {
            db.close()
        }
    }
}
