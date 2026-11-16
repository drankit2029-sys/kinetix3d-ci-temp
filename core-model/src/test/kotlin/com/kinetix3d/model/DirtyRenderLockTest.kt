package com.kinetix3d.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirtyRenderLockTest {
    private val dir: File = Files.createTempDirectory("k3d-render-lock").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `missing lock file is reported as missing`() {
        DirtyRenderLock(dir).use { lock ->
            assertEquals(DirtyRenderLock.State.MISSING, lock.inspect().state)
            assertFalse(lock.inspect().requiresRecovery)
        }
    }

    @Test
    fun `frame in flight is a recoverable active record`() {
        DirtyRenderLock(dir).use { lock ->
            lock.beginFrame(generation = 42L, nodeIdHash = 0x00C0FFEEL)

            val inspection = lock.inspect()
            assertEquals(DirtyRenderLock.State.ACTIVE_RECORD, inspection.state)
            assertEquals(42L, inspection.generation)
            assertEquals(0x00C0FFEEL, inspection.nodeIdHash)
            assertTrue(inspection.requiresRecovery)
        }
    }

    @Test
    fun `swap clears the record`() {
        DirtyRenderLock(dir).use { lock ->
            lock.beginFrame(generation = 7L, nodeIdHash = 99L)
            lock.clearAfterSwap()

            val inspection = lock.inspect()
            assertEquals(DirtyRenderLock.State.CLEAN, inspection.state)
            assertFalse(inspection.requiresRecovery)
        }
    }

    @Test
    fun `torn write with a bad crc is corrupt and never triggers recovery`() {
        DirtyRenderLock(dir).use { lock ->
            lock.beginFrame(generation = 11L, nodeIdHash = 5L)
        }

        // Simulate a partially flushed mmap: flip the generation byte under the stored CRC.
        RandomAccessFile(File(dir, DirtyRenderLock.FILE_NAME), "rw").use { raf ->
            raf.seek(4)
            val original = raf.readByte()
            raf.seek(4)
            raf.writeByte((original.toInt() xor 0xFF))
        }

        DirtyRenderLock(dir).use { lock ->
            val inspection = lock.inspect()
            assertEquals(DirtyRenderLock.State.CORRUPT, inspection.state)
            assertFalse(inspection.requiresRecovery)
        }
    }

    @Test
    fun `bad magic is corrupt`() {
        DirtyRenderLock(dir).use { lock ->
            lock.clearAfterSwap()
        }
        RandomAccessFile(File(dir, DirtyRenderLock.FILE_NAME), "rw").use { raf ->
            raf.seek(0)
            raf.writeInt(0xDEADBEEF.toInt())
        }

        DirtyRenderLock(dir).use { lock ->
            assertEquals(DirtyRenderLock.State.CORRUPT, lock.inspect().state)
        }
    }

    @Test
    fun `zero generation is rejected at the API boundary`() {
        DirtyRenderLock(dir).use { lock ->
            assertFailsWith<IllegalArgumentException> {
                lock.beginFrame(generation = 0L, nodeIdHash = 1L)
            }
        }
    }
}
