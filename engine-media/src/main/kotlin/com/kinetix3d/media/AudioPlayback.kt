/*
 * Kinetix3D Studio — engine-media
 * Minimal audio playback: decodes the active media's audio track to PCM and
 * writes it to AudioTrack while the transport is playing.
 */

package com.kinetix3d.media

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPlayback(
    private val sourcePath: String,
) : AutoCloseable {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false
    private var startPtsUs = 0L

    private val wakeLock = Object()

    fun start(ptsUs: Long) {
        if (running) return
        startPtsUs = ptsUs.coerceAtLeast(0L)
        running = true
        thread =
            Thread({ decodeLoop() }, "KinetixAudioDecode").also { it.start() }
    }

    fun setVolume(volume: Float) {
        runCatching { audioTrack?.setVolume(volume.coerceIn(0f, 1f)) }
    }

    fun stop() {
        running = false
        synchronized(wakeLock) { wakeLock.notifyAll() }
        val worker = thread
        thread = null
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(JOIN_TIMEOUT_MS) }
        }
        releaseInternal()
    }

    private fun decodeLoop() {
        try {
            val mediaExtractor = MediaExtractor()
            extractor = mediaExtractor
            mediaExtractor.setDataSource(sourcePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until mediaExtractor.trackCount) {
                val candidate = mediaExtractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "no audio track in $sourcePath")
                return
            }
            mediaExtractor.selectTrack(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val channelOut =
                if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelOut, ENCODING)
            val track =
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelOut,
                    ENCODING,
                    maxOf(minBuffer, MIN_BUFFER_BYTES),
                    AudioTrack.MODE_STREAM,
                )
            audioTrack = track
            track.play()
            Log.d(TAG, "audio track playing: ${track.playState} ${sampleRate}Hz ${channels}ch")
            mediaExtractor.seekTo(startPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (running) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        val size = if (buffer != null) mediaExtractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, mediaExtractor.sampleTime, 0)
                            mediaExtractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outputIndex >= 0) {
                    val buffer = decoder.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        val shorts = ShortArray(info.size / 2)
                        buffer.asShortBuffer().get(shorts)
                        val floats = FloatArray(shorts.size) { shorts[it] / 32768f }
                        runCatching { KinetixAudioNative.applyGainAndLimit(floats, 1f, 0.98f) }
                        val pcm = ByteBuffer.allocate(floats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        floats.forEach { sample ->
                            pcm.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        }
                        track.write(pcm.array(), 0, pcm.capacity(), AudioTrack.WRITE_BLOCKING)
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
            track.stop()
        } catch (throwable: Throwable) {
            Log.w(TAG, "audio playback failed for $sourcePath", throwable)
        } finally {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        runCatching { audioTrack?.release() }
        audioTrack = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor?.release() }
        extractor = null
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val TAG = "KinetixAudioPlayback"
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MIN_BUFFER_BYTES = 8192
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val JOIN_TIMEOUT_MS = 500L
    }
}
