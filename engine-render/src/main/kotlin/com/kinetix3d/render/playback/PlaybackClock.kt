/*
 * Kinetix3D Studio — engine-render
 * Monotonic playback clock. Pure Kotlin so the transport logic is JVM-testable;
 * callers pass System.nanoTime() (or a fake clock in tests).
 */

package com.kinetix3d.render.playback

class PlaybackClock(
    durationUs: Long,
    frameRate: Double = 60.0,
) {
    var durationUs: Long = durationUs.coerceAtLeast(1L)
        set(value) {
            field = value.coerceAtLeast(1L)
            pausedPtsUs = pausedPtsUs.coerceIn(0L, field)
            anchorPtsUs = anchorPtsUs.coerceIn(0L, field)
        }

    var frameRate: Double = frameRate.coerceAtLeast(1.0)
        set(value) {
            field = value.coerceAtLeast(1.0)
        }

    var speed: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    var loop: Boolean = false

    var isPlaying: Boolean = false
        private set

    private var anchorPtsUs = 0L
    private var anchorNanos = 0L
    private var pausedPtsUs = 0L

    val frameDurationUs: Long
        get() = (1_000_000.0 / frameRate).toLong().coerceAtLeast(1L)

    fun seekTo(
        ptsUs: Long,
        nowNanos: Long = System.nanoTime(),
    ) {
        val clamped = ptsUs.coerceIn(0L, durationUs)
        pausedPtsUs = clamped
        anchorPtsUs = clamped
        anchorNanos = nowNanos
    }

    fun play(nowNanos: Long = System.nanoTime()) {
        if (isPlaying) return
        if (pausedPtsUs >= durationUs) pausedPtsUs = 0L
        anchorPtsUs = pausedPtsUs
        anchorNanos = nowNanos
        isPlaying = true
    }

    fun pause(nowNanos: Long = System.nanoTime()) {
        if (!isPlaying) return
        pausedPtsUs = rawPtsUs(nowNanos).coerceIn(0L, durationUs)
        isPlaying = false
    }

    fun toggle(nowNanos: Long = System.nanoTime()) {
        if (isPlaying) pause(nowNanos) else play(nowNanos)
    }

    fun ptsUsAt(nowNanos: Long): Long {
        if (!isPlaying) return pausedPtsUs
        val raw = rawPtsUs(nowNanos)
        if (raw < durationUs) return raw
        if (loop) {
            val wrapped = raw % durationUs
            anchorPtsUs = wrapped
            anchorNanos = nowNanos
            return wrapped
        }
        pausedPtsUs = durationUs
        isPlaying = false
        return durationUs
    }

    /** Playhead quantized down to the source frame grid (the frame visible at [nowNanos]). */
    fun framePtsUsAt(nowNanos: Long): Long {
        val pts = ptsUsAt(nowNanos)
        if (pts >= durationUs) return durationUs
        val frame = frameDurationUs
        return ((pts / frame) * frame).coerceIn(0L, durationUs)
    }

    private fun rawPtsUs(nowNanos: Long): Long {
        val elapsedNanos = (nowNanos - anchorNanos).coerceAtLeast(0L)
        val advancedUs = (elapsedNanos / 1_000.0 * speed).toLong()
        return (anchorPtsUs + advancedUs).coerceAtLeast(0L)
    }

    private companion object {
        private const val MIN_SPEED = 0.1f
        private const val MAX_SPEED = 8f
    }
}
