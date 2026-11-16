/*
 * Kinetix3D Studio — core-platform-api
 * SQLite WAL implementation of the :core-model WalJournal contract.
 * androidx.sqlite-framework (SupportSQLiteOpenHelper) is used directly; Room is
 * deliberately not involved.
 */

package com.kinetix3d.platform.wal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.kinetix3d.model.Mutation
import com.kinetix3d.model.MutationCodec
import com.kinetix3d.model.WalJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-recovery journal backed by a single SQLite database under
 * `filesDir/wal/<dbName>`.
 *
 * Sequence ids are assigned as `MAX(seq_id) + 1` and are never renumbered, so
 * replay `seq_id > lastAppliedSeq` stays stable across checkpoints.
 *
 * Corrupt payloads fail loudly with [IllegalStateException] carrying the row's
 * seq id: silently skipping a mutation would silently fork project state.
 */
class AndroidWalJournal(
    context: Context,
    dbName: String = "kinetix_wal.db",
) : WalJournal,
    AutoCloseable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val helper: SupportSQLiteOpenHelper
    private val database: SupportSQLiteDatabase

    init {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, WAL_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create WAL directory ${directory.absolutePath}")
        }
        val dbFile = File(directory, dbName)
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(appContext)
                .name(dbFile.absolutePath)
                .callback(JournalOpenCallback())
                .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helper.setWriteAheadLoggingEnabled(true)
        database = helper.writableDatabase
        if (!database.enableWriteAheadLogging()) {
            Log.d(TAG, "SQLite WAL mode was not accepted for ${dbFile.absolutePath}")
        }
        database.execSQL("PRAGMA synchronous = NORMAL")
        database.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        database.execSQL(CREATE_MUTATIONS_TABLE)
        database.execSQL(CREATE_STATE_TABLE)
        database.execSQL(SEED_STATE_ROW)
    }

    override suspend fun append(mutation: Mutation): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                val payload = MutationCodec.encode(mutation)
                val timestampMs = System.currentTimeMillis()
                database.beginTransaction()
                try {
                    val seqId = nextSeqId()
                    val values =
                        ContentValues().apply {
                            put(COLUMN_SEQ_ID, seqId)
                            put(COLUMN_TIMESTAMP_MS, timestampMs)
                            put(COLUMN_MUTATION_PROTO, payload)
                        }
                    val conflictAlgorithm = SQLiteDatabase.CONFLICT_REPLACE
                    val inserted = database.insert(TABLE_MUTATIONS, conflictAlgorithm, values)
                    check(inserted != -1L) { "Failed to append WAL mutation at seq_id=$seqId" }
                    database.setTransactionSuccessful()
                    seqId
                } finally {
                    database.endTransaction()
                }
            }
        }

    override suspend fun readFrom(seqId: Long): List<Mutation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                database
                    .query(
                        "SELECT $COLUMN_SEQ_ID, $COLUMN_MUTATION_PROTO FROM $TABLE_MUTATIONS " +
                            "WHERE $COLUMN_SEQ_ID > ? ORDER BY $COLUMN_SEQ_ID ASC",
                        arrayOf<Any?>(seqId),
                    ).use { cursor ->
                        val mutations = ArrayList<Mutation>(cursor.count)
                        while (cursor.moveToNext()) {
                            val rowSeqId = cursor.getLong(0)
                            val payload = cursor.getBlob(1)
                            try {
                                mutations.add(MutationCodec.decode(payload))
                            } catch (error: Exception) {
                                throw IllegalStateException(
                                    "Corrupt WAL mutation_proto at seq_id=$rowSeqId",
                                    error,
                                )
                            }
                        }
                        mutations
                    }
            }
        }

    /**
     * Keeps the newest [keepLast] rows without renumbering survivors. The
     * `wal_state.last_applied_seq` marker is untouched by pruning; markApplied
     * has already persisted it. Callers should checkpoint only after replay.
     */
    override suspend fun checkpoint(keepLast: Int): Unit =
        withContext(Dispatchers.IO) {
            require(keepLast >= 0) { "keepLast must be non-negative" }
            mutex.withLock {
                ensureOpen()
                database.beginTransaction()
                try {
                    database.execSQL(
                        "DELETE FROM $TABLE_MUTATIONS WHERE $COLUMN_SEQ_ID NOT IN (" +
                            "SELECT $COLUMN_SEQ_ID FROM $TABLE_MUTATIONS " +
                            "ORDER BY $COLUMN_SEQ_ID DESC LIMIT ?)",
                        arrayOf<Any?>(keepLast),
                    )
                    database.execSQL(SEED_STATE_ROW)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

    override suspend fun lastAppliedSeq(): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                database
                    .query(
                        "SELECT $COLUMN_LAST_APPLIED FROM $TABLE_WAL_STATE WHERE $COLUMN_ID = 1",
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                    }
            }
        }

    override suspend fun markApplied(seqId: Long): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                database.execSQL(SEED_STATE_ROW)
                database.execSQL(
                    "UPDATE $TABLE_WAL_STATE SET $COLUMN_LAST_APPLIED = MAX($COLUMN_LAST_APPLIED, ?) " +
                        "WHERE $COLUMN_ID = 1",
                    arrayOf<Any?>(seqId),
                )
            }
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            helper.close()
        }
    }

    private fun nextSeqId(): Long {
        database.query("SELECT MAX($COLUMN_SEQ_ID) FROM $TABLE_MUTATIONS").use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0) + 1L
            } else {
                1L
            }
        }
    }

    private fun ensureOpen() {
        check(database.isOpen && !closed.get()) { "AndroidWalJournal is closed" }
    }

    private class JournalOpenCallback : SupportSQLiteOpenHelper.Callback(SCHEMA_VERSION) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_MUTATIONS_TABLE)
            db.execSQL(CREATE_STATE_TABLE)
            db.execSQL(SEED_STATE_ROW)
        }

        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit

        override fun onDowngrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }

    private companion object {
        const val TAG = "KinetixWalJournal"
        const val WAL_DIRECTORY = "wal"
        const val SCHEMA_VERSION = 1
        const val TABLE_MUTATIONS = "wal_mutations"
        const val TABLE_WAL_STATE = "wal_state"
        const val COLUMN_SEQ_ID = "seq_id"
        const val COLUMN_TIMESTAMP_MS = "timestamp_ms"
        const val COLUMN_MUTATION_PROTO = "mutation_proto"
        const val COLUMN_ID = "id"
        const val COLUMN_LAST_APPLIED = "last_applied_seq"

        const val CREATE_MUTATIONS_TABLE =
            "CREATE TABLE IF NOT EXISTS $TABLE_MUTATIONS (" +
                "$COLUMN_SEQ_ID INTEGER PRIMARY KEY, " +
                "$COLUMN_TIMESTAMP_MS INTEGER NOT NULL, " +
                "$COLUMN_MUTATION_PROTO BLOB NOT NULL)"
        const val CREATE_STATE_TABLE =
            "CREATE TABLE IF NOT EXISTS $TABLE_WAL_STATE (" +
                "$COLUMN_ID INTEGER PRIMARY KEY CHECK($COLUMN_ID = 1), " +
                "$COLUMN_LAST_APPLIED INTEGER NOT NULL)"
        const val SEED_STATE_ROW =
            "INSERT OR IGNORE INTO $TABLE_WAL_STATE " +
                "($COLUMN_ID, $COLUMN_LAST_APPLIED) VALUES (1, 0)"
    }
}
