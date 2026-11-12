package org.openstrap.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.openstrap.app.data.SleepEdits

/**
 * Version 31 to 32: the edits left behind by the nights [MinuteSleepMigration] deleted go. An edit finds a session of
 * its kind within [SleepEdits.MATCH_SECONDS], so one of those could have moved or hidden a real strap night near it.
 *
 * Only where those nights could be: main sleeps from [FROM] to [TO], the two weeks 3.0.0 to 3.0.2 looked back over and
 * the days they ran. Such an edit with no main sleep that close goes, except a marker for a deleted sleep whose time
 * to restore has run out ([SleepEdits.marker]), which has none by design. Every other edit stays as it was. No schema
 * change.
 */
@Suppress("MagicNumber") // The versions it runs between, and the dates the minute nights were made for, are what it is.
object OrphanSleepEditMigration : Migration(31, 32) {
    /** 2026-10-20 00:00 UTC, before the earliest night 3.0.0 (7 November) could make from the minutes. */
    const val FROM = 1_792_454_400L

    /** 2026-11-12 00:00 UTC, after 3.0.3 (10 November) deleted them and made no more. */
    const val TO = 1_794_441_600L

    override fun migrate(db: SupportSQLiteDatabase) {
        val keptBefore = System.currentTimeMillis() / 1000 - SleepEdits.KEEP_DAYS * 86_400L
        db.execSQL(
            "DELETE FROM `sleep_edit` WHERE `nap` = 0 AND `strapStart` BETWEEN ? AND ? " +
                "AND NOT (`deleted` = 1 AND `start` IS NULL AND `editedAt` < ?) AND NOT EXISTS " +
                "(SELECT 1 FROM `sleep_session` WHERE `sleep_session`.`nap` = 0 AND " +
                "ABS(`sleep_session`.`start` - `sleep_edit`.`strapStart`) <= ${SleepEdits.MATCH_SECONDS})",
            arrayOf<Any>(FROM, TO, keptBefore),
        )
    }
}
