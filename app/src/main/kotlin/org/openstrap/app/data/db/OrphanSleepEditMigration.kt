package org.openstrap.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.openstrap.app.data.SleepEdits

/**
 * Version 31 to 32: the edits left behind by the nights [MinuteSleepMigration] deleted go. An edit finds a session of
 * its kind within [SleepEdits.MATCH_SECONDS], so one of those could have moved or hidden a real strap night near it.
 * An edit with no session that close goes, except a marker for a deleted sleep whose time to restore has run out
 * ([SleepEdits.marker]), which has none by design. No schema change.
 */
@Suppress("MagicNumber") // The versions it runs between are what the migration is.
object OrphanSleepEditMigration : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val keptBefore = System.currentTimeMillis() / 1000 - SleepEdits.KEEP_DAYS * 86_400L
        db.execSQL(
            "DELETE FROM `sleep_edit` WHERE NOT (`deleted` = 1 AND `start` IS NULL AND `editedAt` < ?) AND NOT EXISTS " +
                "(SELECT 1 FROM `sleep_session` WHERE `sleep_session`.`nap` = `sleep_edit`.`nap` AND " +
                "ABS(`sleep_session`.`start` - `sleep_edit`.`strapStart`) <= ${SleepEdits.MATCH_SECONDS})",
            arrayOf<Any>(keptBefore),
        )
    }
}
