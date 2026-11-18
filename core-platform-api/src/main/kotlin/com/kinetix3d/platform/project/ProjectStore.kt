/*
 * Kinetix3D Studio — core-platform-api
 * Durable project store: atomic `.kproj` snapshots plus WAL replay. The store
 * keeps the snapshot small and lets the journal carry the newest edits, so a
 * crash between saves still recovers the last trim.
 */

package com.kinetix3d.platform.project

import android.content.Context
import com.kinetix3d.model.ClipNode
import com.kinetix3d.model.ProjectFileCodec
import com.kinetix3d.model.ProjectMutationCodec
import com.kinetix3d.model.ProjectReducer
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.WalJournal
import com.kinetix3d.platform.wal.AndroidWalJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ProjectStore(
    private val directory: File,
    private val wal: WalJournal,
    private val projectId: String,
) : AutoCloseable {
    private val mutex = Mutex()
    private val projectFile = File(directory, "$projectId.kproj")
    private val tempFile = File(directory, "$projectId.kproj.tmp")

    @Volatile
    private var lastSaveNanos = 0L

    suspend fun load(): ProjectState? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!projectFile.isFile) return@withLock null
                ProjectFileCodec.decode(projectFile.readBytes())
            }
        }

    suspend fun save(state: ProjectState): File =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.mkdirs()
                val bytes = ProjectFileCodec.encode(state)
                FileOutputStream(tempFile).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                publish(tempFile, projectFile)
                projectFile
            }
        }

    /**
     * Appends the clip update to the WAL immediately and refreshes the snapshot
     * at most once per [SAVE_THROTTLE_NANOS] while a trim drag is in flight.
     */
    suspend fun recordClipUpdate(
        state: ProjectState,
        clip: ClipNode,
    ): Long {
        val mutation = ProjectMutationCodec.updateClip(state.activeCompId, clip)
        val seq = wal.append(mutation)
        val now = System.nanoTime()
        if (now - lastSaveNanos >= SAVE_THROTTLE_NANOS) {
            lastSaveNanos = now
            save(state)
        }
        return seq
    }

    /** Replays journal rows newer than the last applied sequence, then checkpoints. */
    suspend fun recover(base: ProjectState): ProjectState {
        val from = wal.lastAppliedSeq()
        val records = wal.readRecordsFrom(from)
        if (records.isEmpty()) return base
        val recovered = records.fold(base) { state, record -> ProjectReducer.apply(state, record.mutation) }
        save(recovered)
        wal.markApplied(records.last().seqId)
        wal.checkpoint(KEEP_LAST)
        return recovered
    }

    override fun close() {
        (wal as? AutoCloseable)?.close()
    }

    private fun publish(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (target.exists() && !target.delete()) {
                error("Failed to replace ${target.name}")
            }
            check(source.renameTo(target)) { "Failed to publish ${target.name}" }
        }
    }

    companion object {
        private const val SAVE_THROTTLE_NANOS = 500_000_000L
        private const val KEEP_LAST = 64

        fun create(
            context: Context,
            projectId: String,
        ): ProjectStore =
            ProjectStore(
                directory = File(context.filesDir, "projects"),
                wal = AndroidWalJournal(context, projectId),
                projectId = projectId,
            )
    }
}
