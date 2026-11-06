package org.openstrap.app.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.openstrap.app.data.db.OpenStrapDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The one lock for jobs that read or replace the whole database: the automatic backup, Save a copy and restore. Two of
 * them at once would copy a half restored database or restore over a running backup. A job that already holds the lock
 * and calls another (the automatic backup calling the zip writer) goes straight through instead of waiting on itself.
 */
object DataJobs {
    private val mutex = Mutex()

    /** Marks a coroutine that holds [mutex], and passes on to everything it starts in its context. */
    private object Holding : AbstractCoroutineContextElement(Key) {
        object Key : CoroutineContext.Key<Holding>
    }

    /** Runs [block] when no other data job is running; a block already inside one runs at once. */
    suspend fun <T> exclusive(block: suspend () -> T): T =
        if (coroutineContext[Holding.Key] != null) block() else mutex.withLock { withContext(Holding) { block() } }
}

/**
 * A copy of the database as it was at one moment, for a backup to read (data integrity item 23): a sync that writes
 * while the zip is being written can't leave one table in it from before and another from after. `VACUUM INTO` makes
 * the copy from committed data only, and holds the database only while it copies (a second or two for a year of
 * minutes), not while the zip is written, so a sync waits that long at most. Each copy has its own file, so two never
 * share one. [close] deletes the copy.
 */
class BackupSnapshot private constructor(private val file: File, val database: OpenStrapDatabase) : Closeable {
    override fun close() {
        database.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    companion object {
        /** The start of a copy's file name in the app's cache; every copy adds its own id, so no two share a file. */
        private const val PREFIX = "backup-snapshot-"

        /** Copies [source] into the cache and opens the copy. Blocking: call it off the main thread. */
        @Suppress("TooGenericExceptionCaught")
        fun take(context: Context, source: OpenStrapDatabase): BackupSnapshot {
            val file = File(context.cacheDir, "$PREFIX${UUID.randomUUID()}.db")
            try {
                // VACUUM takes no bind arguments, so the path, which is the app's own, goes in quoted.
                source.openHelper.writableDatabase.execSQL("VACUUM INTO '${file.path.replace("'", "''")}'")
                val copy = Room.databaseBuilder(context, OpenStrapDatabase::class.java, file.path).build()
                return BackupSnapshot(file, copy)
            } catch (e: Throwable) {
                // A copy that never opened is not left behind, whatever went wrong. Rethrown as it was.
                SQLiteDatabase.deleteDatabase(file)
                throw e
            }
        }
    }
}
