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

/** A version 30 database opens at 31 without the nights unstrap made from the minute signal, the strap's own kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MinuteSleepMigrationTest {
    @Test
    fun minuteNightsGoStrapNightsStay() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 30 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/30.json").readText()).getJSONObject("database")
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
            // A strap night with a stage, and a night unstrap made (source 1) with a stand-in stage.
            db.execSQL("INSERT INTO sleep_session (start, `end`, day, nap, source) VALUES (1000, 9000, 86400, 0, 0)")
            db.execSQL("INSERT INTO sleep_stage (start, `end`, sessionStart, stage) VALUES (1000, 2000, 1000, 2)")
            db.execSQL("INSERT INTO sleep_session (start, `end`, day, nap, source) VALUES (90000, 99000, 172800, 0, 1)")
            db.execSQL("INSERT INTO sleep_stage (start, `end`, sessionStart, stage) VALUES (90000, 91000, 90000, 1)")
            db.version = 30
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.readableDatabase
            assertEquals(listOf(1000L), db.query("SELECT start FROM sleep_session").use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } })
            assertEquals(listOf(1000L), db.query("SELECT sessionStart FROM sleep_stage").use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } })
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
