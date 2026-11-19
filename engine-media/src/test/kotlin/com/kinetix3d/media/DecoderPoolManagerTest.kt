package com.kinetix3d.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DecoderPoolManagerTest {
    private fun clip(
        id: String,
        startUs: Long,
        endUs: Long,
        priority: Int = 0,
        track: String = "V1",
    ) = ScheduledClip(
        clipId = id,
        trackId = track,
        startUs = startUs,
        endUs = endUs,
        sourcePath = "/tmp/$id.mp4",
        priority = priority,
    )

    @Test
    fun scheduleIncludesLookaheadAndSortsByPriority() {
        val manager = DecoderPoolManager(maxDecoders = 4, lookaheadUs = 1_500_000L)
        val clips =
            listOf(
                clip("a", 0L, 2_000_000L, priority = 0),
                clip("b", 2_000_000L, 3_000_000L, priority = 2),
                clip("c", 2_400_000L, 3_500_000L, priority = 1),
                clip("d", 10_000_000L, 12_000_000L, priority = 3),
            )
        val schedule = manager.schedule(1_000_000L, clips)
        assertEquals(listOf("b", "c", "a"), schedule.map { it.clipId })
    }

    @Test
    fun scheduleCapsAtMaxDecoders() {
        val manager = DecoderPoolManager(maxDecoders = 2, lookaheadUs = 1_500_000L)
        val clips =
            listOf(
                clip("a", 0L, 2_000_000L, priority = 0),
                clip("b", 0L, 2_000_000L, priority = 1),
                clip("c", 0L, 2_000_000L, priority = 2),
            )
        assertEquals(listOf("c", "b"), manager.schedule(0L, clips).map { it.clipId })
    }

    @Test
    fun scheduleExcludesClipsOutsideTheWindow() {
        val manager = DecoderPoolManager(maxDecoders = 4, lookaheadUs = 1_000_000L)
        val clips =
            listOf(
                clip("past", 0L, 500_000L),
                clip("active", 1_000_000L, 2_000_000L),
                clip("future", 5_000_000L, 6_000_000L),
            )
        assertEquals(listOf("active"), manager.schedule(1_000_000L, clips).map { it.clipId })
    }

    @Test
    fun activeClipReturnsTheTopmostClip() {
        val manager = DecoderPoolManager(maxDecoders = 4)
        val clips =
            listOf(
                clip("v1", 0L, 5_000_000L, priority = 0, track = "V1"),
                clip("v2", 1_000_000L, 4_000_000L, priority = 1, track = "V2"),
            )
        assertEquals("v2", manager.activeClip(2_000_000L, clips)?.clipId)
        assertEquals("v1", manager.activeClip(500_000L, clips)?.clipId)
    }

    @Test
    fun activeClipIsHalfOpenAtTheEnd() {
        val manager = DecoderPoolManager(maxDecoders = 4)
        val clips = listOf(clip("a", 0L, 2_000_000L))
        assertEquals("a", manager.activeClip(1_999_999L, clips)?.clipId)
        assertNull(manager.activeClip(2_000_000L, clips))
    }

    @Test
    fun activeClipIsNullOutsideEveryClip() {
        val manager = DecoderPoolManager(maxDecoders = 4)
        assertNull(manager.activeClip(0L, emptyList()))
        assertNull(manager.activeClip(5_000_000L, listOf(clip("a", 0L, 2_000_000L))))
    }

    @Test
    fun zeroLookaheadOnlySchedulesTheActiveClip() {
        val manager = DecoderPoolManager(maxDecoders = 4, lookaheadUs = 0L)
        val clips =
            listOf(
                clip("active", 0L, 2_000_000L),
                clip("next", 2_000_000L, 4_000_000L),
            )
        assertEquals(listOf("active"), manager.schedule(1_000_000L, clips).map { it.clipId })
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { DecoderPoolManager(maxDecoders = 0) }
        assertFailsWith<IllegalArgumentException> { DecoderPoolManager(maxDecoders = 1, lookaheadUs = -1L) }
        assertFailsWith<IllegalArgumentException> { clip("bad", 2L, 2L) }
    }
}
