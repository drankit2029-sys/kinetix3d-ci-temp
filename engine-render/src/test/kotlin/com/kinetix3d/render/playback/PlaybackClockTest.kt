package com.kinetix3d.render.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackClockTest {
    private val second = 1_000_000_000L

    @Test
    fun startsPausedAtZero() {
        val clock = PlaybackClock(durationUs = 10_000_000L)
        assertFalse(clock.isPlaying)
        assertEquals(0L, clock.ptsUsAt(0L))
    }

    @Test
    fun playAdvancesWithMonotonicTime() {
        val clock = PlaybackClock(durationUs = 10_000_000L)
        clock.play(nowNanos = 0L)
        assertTrue(clock.isPlaying)
        assertEquals(500_000L, clock.ptsUsAt(second / 2))
        assertEquals(2_000_000L, clock.ptsUsAt(2 * second))
    }

    @Test
    fun pauseFreezesThePlayhead() {
        val clock = PlaybackClock(durationUs = 10_000_000L)
        clock.play(nowNanos = 0L)
        clock.pause(nowNanos = second)
        assertEquals(1_000_000L, clock.ptsUsAt(5 * second))
        assertFalse(clock.isPlaying)
    }

    @Test
    fun seekClampsAndReanchors() {
        val clock = PlaybackClock(durationUs = 10_000_000L)
        clock.seekTo(4_000_000L, nowNanos = 0L)
        assertEquals(4_000_000L, clock.ptsUsAt(0L))
        clock.seekTo(-5L, nowNanos = 0L)
        assertEquals(0L, clock.ptsUsAt(0L))
        clock.seekTo(99_000_000L, nowNanos = 0L)
        assertEquals(10_000_000L, clock.ptsUsAt(0L))
    }

    @Test
    fun seekWhilePlayingKeepsPlayingFromTheNewPoint() {
        val clock = PlaybackClock(durationUs = 10_000_000L)
        clock.play(nowNanos = 0L)
        clock.seekTo(3_000_000L, nowNanos = second)
        assertTrue(clock.isPlaying)
        assertEquals(3_500_000L, clock.ptsUsAt(second + second / 2))
    }

    @Test
    fun speedScalesElapsedTime() {
        val clock = PlaybackClock(durationUs = 10_000_000L)
        clock.speed = 2f
        clock.play(nowNanos = 0L)
        assertEquals(2_000_000L, clock.ptsUsAt(second))
    }

    @Test
    fun stopsAtDurationWithoutLoop() {
        val clock = PlaybackClock(durationUs = 2_000_000L)
        clock.play(nowNanos = 0L)
        assertEquals(2_000_000L, clock.ptsUsAt(5 * second))
        assertFalse(clock.isPlaying)
    }

    @Test
    fun loopWrapsAtDuration() {
        val clock = PlaybackClock(durationUs = 2_000_000L)
        clock.loop = true
        clock.play(nowNanos = 0L)
        assertEquals(500_000L, clock.ptsUsAt(2_500_000_000L))
        assertTrue(clock.isPlaying)
    }

    @Test
    fun frameQuantizationFloorsToTheSourceGrid() {
        val clock = PlaybackClock(durationUs = 10_000_000L, frameRate = 60.0)
        clock.play(nowNanos = 0L)
        // 60fps -> 16_666us frames; 20_000us lands on frame 1.
        assertEquals(16_666L, clock.framePtsUsAt(20_000_000L))
    }

    @Test
    fun frameQuantizationKeepsTheEndMarker() {
        val clock = PlaybackClock(durationUs = 2_000_000L, frameRate = 60.0)
        clock.play(nowNanos = 0L)
        assertEquals(2_000_000L, clock.framePtsUsAt(5 * second))
    }

    @Test
    fun playFromEndRestartsAtZero() {
        val clock = PlaybackClock(durationUs = 2_000_000L)
        clock.seekTo(2_000_000L)
        clock.play(nowNanos = 0L)
        assertEquals(0L, clock.ptsUsAt(0L))
        assertTrue(clock.isPlaying)
    }

    @Test
    fun durationChangeClampsThePlayhead() {
        val clock = PlaybackClock(durationUs = 10_000_000L)
        clock.seekTo(8_000_000L)
        clock.durationUs = 4_000_000L
        assertEquals(4_000_000L, clock.ptsUsAt(0L))
    }
}
