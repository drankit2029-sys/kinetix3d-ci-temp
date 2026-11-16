/*
 * Kinetix3D Studio — core-platform-api
 * Media-import strategy: SAF descriptor access plus the positioned-read
 * fingerprint shared with the relink flow.
 */

package com.kinetix3d.platform.api

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

interface MediaImportApiManager {
    suspend fun openFileDescriptor(uri: Uri): ParcelFileDescriptor

    fun computeFastAssetFingerprint(
        fd: FileDescriptor,
        size: Long,
    ): String
}

enum class ImportSource {
    USER_PICKER,
    SHARE_INTENT,
    RELINK,
}

/**
 * Shared import contract handed to every engine module. The [descriptor] is owned
 * by the caller and must be closed; engines must only use positioned reads on it.
 */
data class ImportedAssetHandle(
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
    val sha256Header: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val source: ImportSource,
)

/**
 * SAF-backed import manager.
 *
 * The fingerprint hashes the first, middle and last window (64 KiB each) through
 * [Os.pread], which reads at an explicit offset and never advances the shared
 * open-file-description position. `ParcelFileDescriptor.dup()` must not be used:
 * POSIX `dup()` shares `f_pos`, so a concurrent MediaExtractor reader would corrupt
 * this stream (and vice versa). This is a re-encode/cache heuristic, not a
 * content-addressable hash — a match is never proof of identical content.
 */
class AndroidMediaImportApiManager(
    context: Context,
) : MediaImportApiManager {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    // Ownership transfers to the caller; closing here would break the import stream.
    @SuppressLint("Recycle")
    override suspend fun openFileDescriptor(uri: Uri): ParcelFileDescriptor =
        withContext(Dispatchers.IO) {
            resolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("Unable to open a read descriptor for $uri")
        }

    override fun computeFastAssetFingerprint(
        fd: FileDescriptor,
        size: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (size <= 0L) return digest.digest().toHexString()
        val window = minOf(FINGERPRINT_WINDOW_BYTES.toLong(), size).toInt()
        val offsets = longArrayOf(0L, (size - window) / 2L, size - window).distinct()
        val buffer = ByteBuffer.allocate(window)
        for (offset in offsets) {
            buffer.clear()
            var total = 0
            while (total < window) {
                val read =
                    try {
                        Os.pread(fd, buffer, offset + total)
                    } catch (e: ErrnoException) {
                        throw IOException("Positioned read failed at offset ${offset + total}", e)
                    }
                if (read <= 0) break
                total += read
            }
            digest.update(buffer.array(), 0, total)
        }
        return digest.digest().toHexString()
    }

    /**
     * Resolves MIME type and size, opens the descriptor and computes the header
     * fingerprint. Callers own the returned [ImportedAssetHandle.descriptor].
     */
    suspend fun inspectAsset(
        uri: Uri,
        source: ImportSource,
    ): ImportedAssetHandle =
        withContext(Dispatchers.IO) {
            val mimeType = resolver.getType(uri) ?: DEFAULT_MIME_TYPE
            val sizeBytes = resolveSize(uri)
            val descriptor = openFileDescriptor(uri)
            val header =
                if (sizeBytes > 0L) {
                    computeFastAssetFingerprint(descriptor.fileDescriptor, sizeBytes)
                } else {
                    null
                }
            ImportedAssetHandle(
                uri = uri,
                descriptor = descriptor,
                sha256Header = header,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                source = source,
            )
        }

    private fun resolveSize(uri: Uri): Long {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length >= 0L) return afd.length
        }
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                return cursor.getLong(sizeIndex)
            }
        }
        return UNKNOWN_SIZE
    }

    private fun ByteArray.toHexString(): String {
        val result = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            result.append(HEX_DIGITS[value ushr 4])
            result.append(HEX_DIGITS[value and 0x0F])
        }
        return result.toString()
    }

    private companion object {
        const val FINGERPRINT_WINDOW_BYTES = 64 * 1024
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val UNKNOWN_SIZE = -1L
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
