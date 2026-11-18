/*
 * Kinetix3D Studio — engine-render
 * MediaCodec video source: decodes to a SurfaceTexture (external OES) and seeks
 * to the requested playhead. Full continuous playback/decoder-pool scheduling
 * remains Phase 3; this is the real decode path for the preview.
 */

package com.kinetix3d.render

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.File

class MediaCodecSource(
    private val path: String,
) : SourceTexture {
    override var textureId = 0
        private set

    override val isExternal: Boolean = true

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var inputDone = false
    private var outputDone = false
    private var currentPtsUs = -1L
    private var lastTargetUs = -1L

    override fun initialize(): Boolean =
        try {
            val file = File(path)
            if (!file.isFile) return false
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            if (textureId == 0) return false
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            val st = SurfaceTexture(textureId)
            surfaceTexture = st
            val mediaExtractor = MediaExtractor()
            mediaExtractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until mediaExtractor.trackCount) {
                val candidate = mediaExtractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                mediaExtractor.release()
                st.release()
                return false
            }
            mediaExtractor.selectTrack(trackIndex)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            st.setDefaultBufferSize(width, height)
            val outputSurface = Surface(st)
            surface = outputSurface
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val decoder = MediaCodec.createDecoderByType(mime)
            extractor = mediaExtractor
            codec = decoder
            decoder.configure(format, outputSurface, null, 0)
            decoder.start()
            true
        } catch (throwable: Throwable) {
            Log.w(TAG, "MediaCodec source init failed for $path", throwable)
            release()
            false
        }

    override fun update(ptsUs: Long) {
        val st = surfaceTexture ?: return
        val decoder = codec ?: return
        val mediaExtractor = extractor ?: return
        val targetUs = ptsUs.coerceAtLeast(0L)
        if (targetUs == lastTargetUs && currentPtsUs >= 0L) {
            runCatching { st.updateTexImage() }
            return
        }
        try {
            if (currentPtsUs < 0L || targetUs < currentPtsUs || targetUs - currentPtsUs > 1_000_000L) {
                mediaExtractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                decoder.flush()
                inputDone = false
                outputDone = false
                currentPtsUs = -1L
            }
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 2_000_000_000L
            var outputPts = currentPtsUs
            while (outputPts < targetUs && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (buffer != null) mediaExtractor.readSampleData(buffer, 0) else -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, mediaExtractor.sampleTime, 0)
                            mediaExtractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outputIndex >= 0 -> {
                        decoder.releaseOutputBuffer(outputIndex, true)
                        outputPts = info.presentationTimeUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Unit
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Unit
                    }
                }
                if (outputDone && outputPts < targetUs) break
            }
            runCatching { st.updateTexImage() }
            currentPtsUs = outputPts
            if (outputPts >= 0L) lastTargetUs = targetUs
        } catch (throwable: Throwable) {
            Log.w(TAG, "MediaCodec update failed at $targetUs us", throwable)
        }
    }

    override fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        runCatching { extractor?.release() }
        extractor = null
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    companion object {
        private const val TAG = "KinetixMediaCodecSource"
    }
}
