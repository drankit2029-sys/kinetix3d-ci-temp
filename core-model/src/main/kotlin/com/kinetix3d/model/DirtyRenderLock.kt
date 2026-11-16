/*
 * Kinetix3D Studio — core-model
 * RenderThread breadcrumbs: before a frame is compiled the renderer writes a
 * CRC-validated 64-byte guard record; eglSwapBuffers clears it. A torn mmap from
 * a power loss must never trigger Safe Recovery Mode, hence magic + CRC checks.
 */

package com.kinetix3d.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.CRC32

/**
 * Memory-mapped `render_guard.lock` wrapper.
 *
 * Layout (little-endian, 64 bytes):
 * ```
 *   0  magic        int32  ('K3DL')
 *   4  generation   int64  (0 = clean, non-zero = frame in flight)
 *  12  nodeIdHash   int64  (stable hash of the node currently being drawn)
 *  20  crc32        int32  (over bytes 0..19)
 *  24  reserved    40 bytes, zero
 * ```
 */
class DirtyRenderLock(
    directory: File,
) : AutoCloseable {
    enum class State { MISSING, CLEAN, ACTIVE_RECORD, CORRUPT }

    data class Inspection(
        val state: State,
        val generation: Long = 0L,
        val nodeIdHash: Long = 0L,
    ) {
        /** True only for a CRC-valid record left behind by a crash mid-frame. */
        val requiresRecovery: Boolean get() = state == State.ACTIVE_RECORD
    }

    private val lockFile = File(directory, FILE_NAME)

    @Volatile
    private var access: RandomAccessFile? = null

    @Volatile
    private var channel: FileChannel? = null

    @Volatile
    private var mapped: MappedByteBuffer? = null

    /** Starts (or advances) a frame: generation must be non-zero while drawing. */
    @Synchronized
    fun beginFrame(
        generation: Long,
        nodeIdHash: Long,
    ) {
        require(generation > 0L) { "generation must be non-zero while a frame is in flight" }
        writeRecord(generation, nodeIdHash)
    }

    /** Called after eglSwapBuffers: a clean record has generation == 0. */
    @Synchronized
    fun clearAfterSwap() {
        writeRecord(0L, 0L)
    }

    /** Validates magic + CRC before trusting a non-zero generation (torn write = CORRUPT). */
    @Synchronized
    fun inspect(): Inspection {
        if (!lockFile.exists()) return Inspection(State.MISSING)
        val buffer = open()
        if (buffer.capacity() < RECORD_BYTES) return Inspection(State.CORRUPT)
        val magic = buffer.getInt(OFFSET_MAGIC)
        if (magic != MAGIC) return Inspection(State.CORRUPT)
        val generation = buffer.getLong(OFFSET_GENERATION)
        val nodeIdHash = buffer.getLong(OFFSET_NODE_HASH)
        val storedCrc = buffer.getInt(OFFSET_CRC)
        if (storedCrc != crcOf(buffer)) return Inspection(State.CORRUPT)
        return if (generation == 0L) {
            Inspection(State.CLEAN)
        } else {
            Inspection(State.ACTIVE_RECORD, generation, nodeIdHash)
        }
    }

    private fun writeRecord(
        generation: Long,
        nodeIdHash: Long,
    ) {
        val buffer = open()
        buffer.putInt(OFFSET_MAGIC, MAGIC)
        buffer.putLong(OFFSET_GENERATION, generation)
        buffer.putLong(OFFSET_NODE_HASH, nodeIdHash)
        val crc = crcOf(buffer)
        buffer.putInt(OFFSET_CRC, crc)
        for (offset in OFFSET_PAYLOAD until RECORD_BYTES) {
            buffer.put(offset, 0)
        }
        buffer.force()
    }

    private fun open(): MappedByteBuffer {
        mapped?.let { return it }
        val parent = lockFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val raf = RandomAccessFile(lockFile, "rw")
        if (raf.length() < RECORD_BYTES) {
            raf.setLength(RECORD_BYTES.toLong())
        }
        val ch = raf.channel
        val buffer = ch.map(FileChannel.MapMode.READ_WRITE, 0L, RECORD_BYTES.toLong())
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        access = raf
        channel = ch
        mapped = buffer
        return buffer
    }

    private fun crcOf(buffer: ByteBuffer): Int {
        val crc = CRC32()
        for (offset in 0 until OFFSET_CRC) {
            crc.update(buffer.get(offset).toInt())
        }
        return crc.value.toInt()
    }

    @Synchronized
    override fun close() {
        mapped = null
        channel?.close()
        access?.close()
        channel = null
        access = null
    }

    companion object {
        const val FILE_NAME = "render_guard.lock"
        const val RECORD_BYTES = 64
        const val MAGIC = 0x4B33444C // 'K3DL'
        private const val OFFSET_MAGIC = 0
        private const val OFFSET_GENERATION = 4
        private const val OFFSET_NODE_HASH = 12
        private const val OFFSET_CRC = 20
        private const val OFFSET_PAYLOAD = 24
    }
}
