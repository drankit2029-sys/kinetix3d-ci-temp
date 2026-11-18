/*
 * Kinetix3D Studio — engine-render
 * MediaCodec video source: a dedicated decode thread feeds a SurfaceTexture
 * (GL_TEXTURE_EXTERNAL_OES) while the render thread only latches the newest
 * frame. Seek requests are coalesced and decoded to the exact target frame.
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
import com.kinetix3d.media.DecoderPool
import java.io.File

class MediaCodecSource(
    private val path: String,
    private val decoderPool: DecoderPool? = null,
    private val onFrameAvailable: (() -> Unit)? = null,
) : SourceTexture {
    override var textureId = 0
        private set

    override val isExternal: Boolean = true

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var lease: DecoderPool.Lease? = null
    private var decodeThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var playing = false

    @Volatile
    private var seekTargetUs = NO_SEEK

    @Volatile
    private var decodedPtsUs = -1L

    @Volatile
    private var lastTargetUs = -1L

    @Volatile
    private var ready = false

    private val wakeLock = Object()
    private val transform = FloatArray(16)

    override fun initialize(): Boolean {
        try {
            val file = File(path)
            if (!file.isFile) return false
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            if (textureId == 0) return false
            configureTexture(textureId)
            val st = SurfaceTexture(textureId)
            st.setOnFrameAvailableListener { onFrameAvailable?.invoke() }
            surfaceTexture = st
            surface = Surface(st)
            if (decoderPool != null) {
                lease = decoderPool.acquire(path)
                if (lease == null) {
                    Log.w(TAG, "decoder pool exhausted for $path")
                    release()
                    return false
                }
            }
            running = true
            val thread = Thread({ decodeLoop() }, "KinetixDecode")
            decodeThread = thread
            thread.start()
            val deadline = System.nanoTime() + READY_TIMEOUT_NANOS
            while (!ready && running && System.nanoTime() < deadline) {
                Thread.sleep(READY_POLL_MS)
            }
            if (!ready) {
                Log.w(TAG, "decoder did not become ready for $path")
                release()
                return false
            }
            Log.d(TAG, "decoder ready for $path")
            return true
        } catch (throwable: Throwable) {
            Log.w(TAG, "MediaCodec source init failed for $path", throwable)
            release()
            return false
        }
    }

    override fun setPlaying(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        if (playing) {
            val target = lastTargetUs
            if (target >= 0L && (decodedPtsUs < 0L || target - decodedPtsUs > SEEK_THRESHOLD_US)) {
                seekTargetUs = target
            }
            wake()
        }
    }

    override fun update(ptsUs: Long) {
        val st = surfaceTexture ?: return
        val target = ptsUs.coerceAtLeast(0L)
        val needsSeek =
            target < decodedPtsUs ||
                target - decodedPtsUs > SEEK_THRESHOLD_US ||
                (!playing && target != lastTargetUs)
        if (needsSeek) {
            seekTargetUs = target
            wake()
        }
        lastTargetUs = target
        runCatching {
            st.updateTexImage()
            st.getTransformMatrix(transform)
        }
    }

    override fun textureMatrix(): FloatArray? = transform

    override fun release() {
        running = false
        playing = false
        wake()
        // Drain the consumer queue so a producer blocked in releaseOutputBuffer
        // can observe running=false and exit.
        repeat(RELEASE_DRAIN_ATTEMPTS) {
            runCatching { surfaceTexture?.updateTexImage() }
        }
        val thread = decodeThread
        decodeThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(RELEASE_JOIN_MS) }
            if (thread.isAlive) {
                Log.w(TAG, "decode thread still alive; deferring surface release")
            }
        }
        if (thread == null || !thread.isAlive) {
            runCatching { surface?.release() }
            surface = null
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
        }
        lease?.let { decoderPool?.release(it) }
        lease = null
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val mediaExtractor = MediaExtractor()
            extractor = mediaExtractor
            mediaExtractor.setDataSource(File(path).absolutePath)
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
                Log.w(TAG, "no video track in $path")
                return
            }
            mediaExtractor.selectTrack(trackIndex)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            surfaceTexture?.setDefaultBufferSize(width, height)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, surface, null, 0)
            decoder.start()
            ready = true
            val state = DecodeState()
            val info = MediaCodec.BufferInfo()
            while (running) {
                val target = seekTargetUs
                if (target != NO_SEEK) {
                    seekTargetUs = NO_SEEK
                    mediaExtractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    decoder.flush()
                    state.reset()
                    decodedPtsUs = decodeToTarget(decoder, mediaExtractor, target, info, state)
                    if (!playing) continue
                }
                if (!playing) {
                    waitForWake(IDLE_WAIT_MS)
                    continue
                }
                val progressed = decodeNext(decoder, mediaExtractor, info, state)
                if (!progressed) waitForWake(IDLE_WAIT_MS)
                if (state.outputDone) {
                    playing = false
                    waitForWake(IDLE_WAIT_MS)
                }
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "decode loop failed for $path", throwable)
        } finally {
            ready = false
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun decodeNext(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        info: MediaCodec.BufferInfo,
        state: DecodeState,
    ): Boolean {
        var progressed = feedInput(decoder, extractor, state)
        val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
        if (outputIndex >= 0) {
            decoder.releaseOutputBuffer(outputIndex, true)
            state.currentPtsUs = info.presentationTimeUs
            decodedPtsUs = info.presentationTimeUs
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) state.outputDone = true
            progressed = true
        }
        return progressed
    }

    private fun decodeToTarget(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        targetUs: Long,
        info: MediaCodec.BufferInfo,
        state: DecodeState,
    ): Long {
        val deadline = System.nanoTime() + SEEK_TIMEOUT_NANOS
        var lastPts = -1L
        while (running && System.nanoTime() < deadline) {
            feedInput(decoder, extractor, state)
            val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (outputIndex >= 0) {
                val pts = info.presentationTimeUs
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (pts >= targetUs || end) {
                    decoder.releaseOutputBuffer(outputIndex, true)
                    state.currentPtsUs = pts
                    state.outputDone = end
                    return pts
                }
                decoder.releaseOutputBuffer(outputIndex, false)
                lastPts = pts
            }
        }
        return lastPts
    }

    private fun feedInput(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        state: DecodeState,
    ): Boolean {
        if (state.inputDone) return false
        val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return false
        val buffer = decoder.getInputBuffer(inputIndex)
        val sampleSize = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
        if (sampleSize < 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            state.inputDone = true
        } else {
            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
        return true
    }

    private fun waitForWake(timeoutMs: Long) {
        synchronized(wakeLock) {
            runCatching { wakeLock.wait(timeoutMs) }
        }
    }

    private fun wake() {
        synchronized(wakeLock) {
            wakeLock.notifyAll()
        }
    }

    private fun configureTexture(texture: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
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
    }

    private class DecodeState {
        var inputDone = false
        var outputDone = false
        var currentPtsUs = -1L

        fun reset() {
            inputDone = false
            outputDone = false
            currentPtsUs = -1L
        }
    }

    companion object {
        private const val TAG = "KinetixMediaCodecSource"
        private const val NO_SEEK = Long.MIN_VALUE
        private const val SEEK_THRESHOLD_US = 1_000_000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val IDLE_WAIT_MS = 10L
        private const val READY_POLL_MS = 5L
        private const val READY_TIMEOUT_NANOS = 2_000_000_000L
        private const val SEEK_TIMEOUT_NANOS = 2_000_000_000L
        private const val RELEASE_JOIN_MS = 1_000L
        private const val RELEASE_DRAIN_ATTEMPTS = 4
    }
}
