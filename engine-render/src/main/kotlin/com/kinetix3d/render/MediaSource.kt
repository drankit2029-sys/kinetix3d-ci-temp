/*
 * Kinetix3D Studio — engine-render
 * Loads a real image or the first frame of a video from app-specific external
 * storage into a GL texture. Full video decode remains Phase 3.
 */

package com.kinetix3d.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.io.File

class MediaSource(
    private val path: String,
) : SourceTexture {
    override var textureId = 0
        private set

    override fun initialize(): Boolean {
        val bitmap = loadBitmap() ?: return false
        val flipped = flipVertically(bitmap)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        if (textureId == 0) {
            flipped.recycle()
            if (flipped !== bitmap) bitmap.recycle()
            return false
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flipped, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        flipped.recycle()
        if (flipped !== bitmap) bitmap.recycle()
        return true
    }

    private fun flipVertically(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(1f, -1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    private fun loadBitmap(): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        return decodeImage(file) ?: decodeVideoFrame(file)
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_TEXTURE_SIZE || bounds.outHeight / sample > MAX_TEXTURE_SIZE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeVideoFrame(file: File): Bitmap? =
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            val timeUs = if (durationMs > 0L) (durationMs / 2L) * 1000L else 0L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            scaleDown(frame)
        } catch (throwable: Throwable) {
            Log.w(TAG, "video frame decode failed for ${file.name}", throwable)
            null
        }

    private fun scaleDown(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= MAX_TEXTURE_SIZE) return bitmap
        val scale = MAX_TEXTURE_SIZE.toFloat() / maxSide
        val scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        bitmap.recycle()
        return scaled
    }

    companion object {
        private const val TAG = "KinetixMediaSource"
        private const val MAX_TEXTURE_SIZE = 4096
    }
}
