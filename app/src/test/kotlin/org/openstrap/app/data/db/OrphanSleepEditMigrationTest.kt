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
            db.execSQL("INSERT INTO sleep_session (start, `end`, day, nap, source) VALUES (100000, 130000, 86400, 0, 0)")
            // Exact and resent 10 minutes off stay; a nap edit near a main sleep has no nap near and goes.
            db.execSQL("INSERT INTO sleep_edit (strapStart, nap, start, `end`, deleted, editedAt) VALUES (100000, 0, 99000, 131000, 0, $now)")
            db.execSQL("INSERT INTO sleep_edit (strapStart, nap, start, `end`, deleted, editedAt) VALUES (99400, 0, NULL, NULL, 1, $now)")
            db.execSQL("INSERT INTO sleep_edit (strapStart, nap, start, `end`, deleted, editedAt) VALUES (100500, 1, 100000, 103000, 0, $now)")
            // Left by a minute night: a move and a delete with no session near, and an old delete marker that stays.
            db.execSQL("INSERT INTO sleep_edit (strapStart, nap, start, `end`, deleted, editedAt) VALUES (500000, 0, 499000, 530000, 0, $now)")
            db.execSQL("INSERT INTO sleep_edit (strapStart, nap, start, `end`, deleted, editedAt) VALUES (600000, 0, NULL, NULL, 1, $now)")
            db.execSQL("INSERT INTO sleep_edit (strapStart, nap, start, `end`, deleted, editedAt) VALUES (700000, 0, NULL, NULL, 1, $old)")
            db.version = 31
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.readableDatabase
            assertEquals(
                listOf(99400L, 100000L, 700000L),
                db.query("SELECT strapStart FROM sleep_edit ORDER BY strapStart").use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } },
            )
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
