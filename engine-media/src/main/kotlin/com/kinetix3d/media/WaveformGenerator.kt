/*
 * Kinetix3D Studio — engine-media
 * Offline audio peak extraction for timeline waveforms. Decodes the first audio
 * track to PCM and aggregates absolute peaks into a fixed bucket count.
 */

package com.kinetix3d.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlin.math.abs

object WaveformGenerator {
    fun generate(
        path: String,
        buckets: Int,
    ): FloatArray {
        val count = buckets.coerceAtLeast(1)
        val peaks = FloatArray(count)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val mediaExtractor = MediaExtractor()
            extractor = mediaExtractor
            mediaExtractor.setDataSource(path)
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
            if (trackIndex < 0 || format == null) return peaks
            mediaExtractor.selectTrack(trackIndex)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
                } else {
                    1L
                }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return peaks
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
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
                        val samples = ShortArray(info.size / 2)
                        buffer.asShortBuffer().get(samples)
                        val bucket =
                            ((info.presentationTimeUs.toDouble() / durationUs) * count)
                                .toInt()
                                .coerceIn(0, count - 1)
                        var peak = peaks[bucket]
                        for (sample in samples) {
                            val value = abs(sample.toInt()) / 32768f
                            if (value > peak) peak = value
                        }
                        peaks[bucket] = peak
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "waveform generation failed for $path", throwable)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
        return peaks
    }

    private const val TAG = "KinetixWaveform"
    private const val DEQUEUE_TIMEOUT_US = 10_000L
}
