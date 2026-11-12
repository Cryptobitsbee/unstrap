package org.openstrap.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A version 31 database opens at 32 without the sleep edits that match no session, those of a real night and the markers of an expired delete kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrphanSleepEditMigrationTest {
    @Test
    fun orphanEditsGoOthersStay() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 31 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/31.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            val old = System.currentTimeMillis() / 1000 - 8 * 86_400L
            val now = System.currentTimeMillis() / 1000
            // 21 October 2026 22:00 UTC, inside the weeks minute nights were made for.
            val night = 1_792_620_000L
            fun edit(strapStart: Long, nap: Int, start: Long?, end: Long?, deleted: Int, at: Long) =
                db.execSQL("INSERT INTO sleep_edit (strapStart, nap, start, `end`, deleted, editedAt) VALUES ($strapStart, $nap, $start, $end, $deleted, $at)")
            db.execSQL("INSERT INTO sleep_session (start, `end`, day, nap, source) VALUES ($night, ${night + 30_000}, 86400, 0, 0)")
            // A strap night's edits stay: exact, and resent 10 minutes off. A nap edit is never touched.
            edit(night, 0, night - 1000, night + 31_000, 0, now)
            edit(night - 600, 0, null, null, 1, now)
            edit(night + 500, 1, night, night + 3000, 0, now)
            // Left by minute nights: a move and a delete with no night near go; an expired delete marker stays.
            edit(night + 400_000, 0, night + 399_000, night + 430_000, 0, now)
            edit(night + 500_000, 0, null, null, 1, now)
            edit(night + 600_000, 0, null, null, 1, old)
            // Outside those weeks nothing is touched, with a night near or not.
            edit(100_000, 0, 99_000, 130_000, 0, now)
            db.version = 31
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.readableDatabase
            assertEquals(
                listOf(100_000L, 1_792_620_000L - 600, 1_792_620_000L, 1_792_620_000L + 500, 1_792_620_000L + 600_000),
                db.query("SELECT strapStart FROM sleep_edit ORDER BY strapStart").use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } },
            )
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
