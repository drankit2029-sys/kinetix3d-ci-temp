/*
 * Kinetix3D Studio — core-platform-api
 * Atomic gallery/OTG export strategy. Phase 6's exporter only sees this interface;
 * scoped-storage differences never leak into the engine modules.
 */

package com.kinetix3d.platform.api

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

interface StorageAndExportApiManager {
    suspend fun publishExportAtomically(
        tempFile: File,
        displayName: String,
        mimeType: String,
    ): Uri

    suspend fun publishToOtg(
        uri: Uri,
        tempFile: File,
    ): Boolean
}

class AndroidStorageAndExportApiManager(
    context: Context,
) : StorageAndExportApiManager {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun publishExportAtomically(
        tempFile: File,
        displayName: String,
        mimeType: String,
    ): Uri =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(tempFile, displayName, mimeType)
            } else {
                publishLegacy(tempFile, displayName, mimeType)
            }
        }

    /**
     * SAF/OTG export into a tree the user already granted. Opening the document
     * with mode "wt" truncates it, so no `DocumentFile` dependency is required.
     */
    override suspend fun publishToOtg(
        uri: Uri,
        tempFile: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val output = resolver.openOutputStream(uri, "wt") ?: return@withContext false
                output.use { stream ->
                    tempFile.inputStream().use { input -> input.copyTo(stream) }
                }
                true
            } catch (error: Exception) {
                false
            }
        }

    private enum class ExportKind(
        val legacyDirectory: String,
    ) {
        VIDEO(Environment.DIRECTORY_MOVIES),
        IMAGE(Environment.DIRECTORY_PICTURES),
        AUDIO(Environment.DIRECTORY_MUSIC),
    }

    private fun exportKindFor(mimeType: String): ExportKind =
        when {
            mimeType.startsWith("video/", ignoreCase = true) -> ExportKind.VIDEO
            mimeType.startsWith("image/", ignoreCase = true) -> ExportKind.IMAGE
            mimeType.startsWith("audio/", ignoreCase = true) -> ExportKind.AUDIO
            else -> throw IllegalArgumentException("Unsupported export MIME type '$mimeType'")
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun collectionFor(kind: ExportKind): Uri =
        when (kind) {
            ExportKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ExportKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ExportKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishViaMediaStore(
        tempFile: File,
        displayName: String,
        mimeType: String,
    ): Uri {
        val kind = exportKindFor(mimeType)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${kind.legacyDirectory}/$EXPORT_DIRECTORY",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection = collectionFor(kind)
        val uri =
            resolver.insert(collection, values)
                ?: throw IOException("MediaStore refused to create $displayName")
        try {
            val output =
                resolver.openOutputStream(uri)
                    ?: throw IOException("MediaStore returned no output stream for $uri")
            output.use { stream ->
                tempFile.inputStream().use { input -> input.copyTo(stream) }
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            return uri
        } catch (throwable: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw throwable
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        tempFile: File,
        displayName: String,
        mimeType: String,
    ): Uri {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("WRITE_EXTERNAL_STORAGE is required to export on API 26-28")
        }
        val kind = exportKindFor(mimeType)
        val root = Environment.getExternalStoragePublicDirectory(kind.legacyDirectory)
        val directory = File(root, EXPORT_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create ${directory.absolutePath}")
        }
        val target = uniqueTarget(directory, displayName)
        tempFile.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return Uri.fromFile(target)
    }

    private fun uniqueTarget(
        directory: File,
        displayName: String,
    ): File {
        val direct = File(directory, displayName)
        if (!direct.exists()) return direct
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val extension = if (dot > 0) displayName.substring(dot) else ""
        return File(directory, "$base-${System.currentTimeMillis()}$extension")
    }

    private companion object {
        const val EXPORT_DIRECTORY = "Kinetix3D"
    }
}
