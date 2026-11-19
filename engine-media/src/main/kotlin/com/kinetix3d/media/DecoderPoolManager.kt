/*
 * Kinetix3D Studio — engine-media
 * Interval scheduler for the hardware decoder pool. Pure Kotlin: the render
 * engine supplies the clip intervals and consumes the ordered schedule.
 */

package com.kinetix3d.media

data class ScheduledClip(
    val clipId: String,
    val trackId: String,
    val startUs: Long,
    val endUs: Long,
    val sourcePath: String,
    val priority: Int = 0,
) {
    init {
        require(endUs > startUs) { "Scheduled clip range must be non-empty" }
    }

    fun contains(ptsUs: Long): Boolean = ptsUs >= startUs && ptsUs < endUs

    fun overlaps(
        fromUs: Long,
        toUs: Long,
    ): Boolean = startUs < toUs && endUs > fromUs
}

/**
 * Allocates the `maxHwDecoders` ceiling to the clips overlapping
 * `[playheadUs, playheadUs + lookaheadUs]`. Higher [ScheduledClip.priority]
 * wins (top video track first); ties break by earlier start.
 */
class DecoderPoolManager(
    private val maxDecoders: Int,
    private val lookaheadUs: Long = DEFAULT_LOOKAHEAD_US,
) {
    init {
        require(maxDecoders >= 1) { "maxDecoders must be at least 1" }
        require(lookaheadUs >= 0L) { "lookaheadUs must be non-negative" }
    }

    fun schedule(
        playheadUs: Long,
        clips: List<ScheduledClip>,
    ): List<ScheduledClip> {
        val from = playheadUs.coerceAtLeast(0L)
        val to = from + lookaheadUs
        return clips
            .asSequence()
            .filter { it.overlaps(from, to) }
            .sortedWith(compareByDescending<ScheduledClip> { it.priority }.thenBy { it.startUs })
            .take(maxDecoders)
            .toList()
    }

    fun activeClip(
        playheadUs: Long,
        clips: List<ScheduledClip>,
    ): ScheduledClip? =
        clips
            .asSequence()
            .filter { it.contains(playheadUs) }
            .maxWithOrNull(compareBy<ScheduledClip> { it.priority }.thenBy { it.startUs })

    companion object {
        const val DEFAULT_LOOKAHEAD_US: Long = 1_500_000L
    }
}
