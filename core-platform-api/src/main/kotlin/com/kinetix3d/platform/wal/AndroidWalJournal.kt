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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-recovery journal backed by a single SQLite database under
 * `filesDir/wal/<dbName>`.
 *
 * The database is opened lazily on [Dispatchers.IO]: constructing the journal
 * (for example in `Application.onCreate`) never touches SQLite. [projectId]
 * scopes the file to `kinetix_wal_<sha256(projectId).take(16)>.db`, so switching
 * projects cannot replay another project's mutations.
 *
 * Sequence ids are cached in memory (`MAX(seq_id) + 1` at open, incremented per
 * insert) and never renumbered, so replay `seq_id > lastAppliedSeq` stays stable
 * across checkpoints.
 *
 * Corrupt payloads fail loudly with [IllegalStateException] carrying the row's
 * seq id: silently skipping a mutation would silently fork project state.
 */
class AndroidWalJournal(
    context: Context,
    projectId: String? = null,
) : WalJournal,
    AutoCloseable {
    private val appContext = context.applicationContext
    private val databaseName = databaseNameFor(projectId)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val initLock = Any()

    @Volatile
    private var helper: SupportSQLiteOpenHelper? = null

    @Volatile
    private var database: SupportSQLiteDatabase? = null

    /** Next sequence id, initialized from the database and advanced under [mutex]. */
    private var nextSeqIdCache: Long = 1L

    override suspend fun append(mutation: Mutation): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = requireDatabase()
                val payload = MutationCodec.encode(mutation)
                val timestampMs = System.currentTimeMillis()
                db.beginTransaction()
                try {
                    val seqId = nextSeqIdCache
                    val values =
                        ContentValues().apply {
                            put(COLUMN_SEQ_ID, seqId)
                            put(COLUMN_TIMESTAMP_MS, timestampMs)
                            put(COLUMN_MUTATION_PROTO, payload)
                        }
                    val conflictAlgorithm = SQLiteDatabase.CONFLICT_REPLACE
                    val inserted = db.insert(TABLE_MUTATIONS, conflictAlgorithm, values)
                    check(inserted != -1L) { "Failed to append WAL mutation at seq_id=$seqId" }
                    nextSeqIdCache = seqId + 1L
                    db.setTransactionSuccessful()
                    seqId
                } finally {
                    db.endTransaction()
                }
            }
        }

    override suspend fun readFrom(seqId: Long): List<Mutation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                requireDatabase()
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
     * Prunes only rows that were already replayed (`seq_id <= last_applied_seq`),
     * always keeping the newest [keepLast] rows. Unapplied work is never deleted,
     * and survivors keep their original sequence ids.
     */
    override suspend fun checkpoint(keepLast: Int): Unit =
        withContext(Dispatchers.IO) {
            require(keepLast >= 0) { "keepLast must be non-negative" }
            mutex.withLock {
                val db = requireDatabase()
                db.beginTransaction()
                try {
                    db.execSQL(
                        "DELETE FROM $TABLE_MUTATIONS " +
                            "WHERE $COLUMN_SEQ_ID <= " +
                            "(SELECT $COLUMN_LAST_APPLIED FROM $TABLE_WAL_STATE WHERE $COLUMN_ID = 1) " +
                            "AND $COLUMN_SEQ_ID NOT IN (" +
                            "SELECT $COLUMN_SEQ_ID FROM $TABLE_MUTATIONS " +
                            "ORDER BY $COLUMN_SEQ_ID DESC LIMIT ?)",
                        arrayOf<Any?>(keepLast),
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

    override suspend fun lastAppliedSeq(): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                requireDatabase()
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
                requireDatabase().execSQL(
                    "UPDATE $TABLE_WAL_STATE SET $COLUMN_LAST_APPLIED = MAX($COLUMN_LAST_APPLIED, ?) " +
                        "WHERE $COLUMN_ID = 1",
                    arrayOf<Any?>(seqId),
                )
            }
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            helper?.close()
            helper = null
            database = null
        }
    }

    /**
     * Double-checked lazy open. Every caller already runs on [Dispatchers.IO], but
     * the explicit `withContext` keeps the guarantee when a method is invoked from
     * a test or a future non-IO caller.
     */
    private suspend fun requireDatabase(): SupportSQLiteDatabase {
        check(!closed.get()) { "AndroidWalJournal is closed" }
        database?.let { return it }
        return withContext(Dispatchers.IO) {
            database ?: synchronized(initLock) {
                database ?: openDatabase().also { database = it }
            }
        }
    }

    private fun openDatabase(): SupportSQLiteDatabase {
        val directory = File(appContext.filesDir, WAL_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create WAL directory ${directory.absolutePath}")
        }
        val dbFile = File(directory, databaseName)
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(appContext)
                .name(dbFile.absolutePath)
                .callback(JournalOpenCallback())
                .build()
        val createdHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        createdHelper.setWriteAheadLoggingEnabled(true)
        val db =
            try {
                createdHelper.writableDatabase
            } catch (throwable: Throwable) {
                runCatching { createdHelper.close() }
                throw throwable
            }
        if (!db.enableWriteAheadLogging()) {
            Log.d(TAG, "SQLite WAL mode was not accepted for ${dbFile.absolutePath}")
        }
        db.execSQL("PRAGMA synchronous = NORMAL")
        db.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        db.execSQL(CREATE_MUTATIONS_TABLE)
        db.execSQL(CREATE_STATE_TABLE)
        db.execSQL(SEED_STATE_ROW)
        nextSeqIdCache = queryNextSeqId(db)
        helper = createdHelper
        return db
    }

    private fun queryNextSeqId(db: SupportSQLiteDatabase): Long =
        db.query("SELECT MAX($COLUMN_SEQ_ID) FROM $TABLE_MUTATIONS").use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0) + 1L
            } else {
                1L
            }
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
        const val DEFAULT_DB_NAME = "kinetix_wal.db"
        const val PROJECT_DB_PREFIX = "kinetix_wal_"
        const val PROJECT_HASH_CHARS = 16
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

        val HEX_DIGITS = "0123456789abcdef".toCharArray()

        fun databaseNameFor(projectId: String?): String {
            if (projectId.isNullOrBlank()) return DEFAULT_DB_NAME
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(projectId.toByteArray(Charsets.UTF_8))
            val hash =
                buildString(digest.size * 2) {
                    for (byte in digest) {
                        val value = byte.toInt() and 0xFF
                        append(HEX_DIGITS[value ushr 4])
                        append(HEX_DIGITS[value and 0x0F])
                    }
                }
            return "$PROJECT_DB_PREFIX${hash.take(PROJECT_HASH_CHARS)}.db"
        }
    }
}
