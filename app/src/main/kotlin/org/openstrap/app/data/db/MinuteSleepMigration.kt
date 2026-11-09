package org.openstrap.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 30 to 31: the nights unstrap made from the strap's minute sleep signal go. unstrap shows the strap's own
 * sleep result as is, as Zepp does, so a day whose result the strap left empty or wrong stays that way. Those nights
 * ([SleepSession.FROM_MINUTES]) had no stages, and the stages are cleared with them in case one was made. No schema
 * change.
 */
@Suppress("MagicNumber") // The versions it runs between are what the migration is.
object MinuteSleepMigration : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM `sleep_stage` WHERE `sessionStart` IN " +
                "(SELECT `start` FROM `sleep_session` WHERE `source` = ${SleepSession.FROM_MINUTES})",
        )
        db.execSQL("DELETE FROM `sleep_session` WHERE `source` = ${SleepSession.FROM_MINUTES}")
    }
}
