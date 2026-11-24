/*
 * Kinetix3D Studio — ui-studio
 * Pure timeline hit-testing and trim math. Phone-first policy: clip edges use a
 * 24dp hit target even though the visual handle is only 4dp wide.
 */

package com.kinetix3d.ui

import com.kinetix3d.model.TimelineMarker
import kotlin.math.abs

internal const val MIN_CLIP_DURATION_US = 100_000L
internal const val TRIM_EDGE_HIT_DP = 24f

internal enum class ClipEdge { START, END }

internal data class ClipEdgeHit(
    val clip: TimelineClipUi,
    val edge: ClipEdge,
)

internal fun hitTestClip(
    x: Float,
    y: Float,
    project: TimelineProjectUi,
    widthPx: Float,
    rulerPx: Float,
    trackPx: Float,
    durationUs: Long,
): TimelineClipUi? {
    if (y < rulerPx || widthPx <= 0f || durationUs <= 0L) return null
    val trackIndex = ((y - rulerPx) / trackPx).toInt()
    val track = project.tracks.getOrNull(trackIndex) ?: return null
    return track.clips.firstOrNull { clip ->
        val left = (clip.startUs.toFloat() / durationUs.toFloat()) * widthPx
        val right = ((clip.startUs + clip.durationUs).toFloat() / durationUs.toFloat()) * widthPx
        x in left..right
    }
}

internal fun hitTestClipEdge(
    x: Float,
    y: Float,
    project: TimelineProjectUi,
    widthPx: Float,
    rulerPx: Float,
    trackPx: Float,
    durationUs: Long,
    edgeHitPx: Float,
): ClipEdgeHit? {
    val clip = hitTestClip(x, y, project, widthPx, rulerPx, trackPx, durationUs) ?: return null
    val left = (clip.startUs.toFloat() / durationUs.toFloat()) * widthPx
    val right = ((clip.startUs + clip.durationUs).toFloat() / durationUs.toFloat()) * widthPx
    val startHit = abs(x - left) <= edgeHitPx
    val endHit = abs(x - right) <= edgeHitPx
    return when {
        startHit && endHit -> {
            ClipEdgeHit(
                clip = clip,
                edge = if (abs(x - left) <= abs(x - right)) ClipEdge.START else ClipEdge.END,
            )
        }

        startHit -> {
            ClipEdgeHit(clip, ClipEdge.START)
        }

        endHit -> {
            ClipEdgeHit(clip, ClipEdge.END)
        }

        else -> {
            null
        }
    }
}

internal fun hitTestMarker(
    x: Float,
    y: Float,
    markers: List<com.kinetix3d.model.TimelineMarker>,
    widthPx: Float,
    rulerPx: Float,
    durationUs: Long,
    hitPx: Float,
): com.kinetix3d.model.TimelineMarker? {
    if (y > rulerPx || widthPx <= 0f || durationUs <= 0L) return null
    return markers
        .minByOrNull { marker ->
            abs(x - (marker.ptsUs.toFloat() / durationUs.toFloat()) * widthPx)
        }?.takeIf { marker ->
            abs(x - (marker.ptsUs.toFloat() / durationUs.toFloat()) * widthPx) <= hitPx
        }
}

internal fun xToUs(
    x: Float,
    widthPx: Float,
    durationUs: Long,
): Long {
    if (widthPx <= 0f) return 0L
    val fraction = (x.coerceIn(0f, widthPx) / widthPx)
    return (fraction * durationUs).toLong().coerceIn(0L, durationUs)
}

/** Snaps [candidateUs] to the nearest target within [thresholdUs]. */
internal fun snapUs(
    candidateUs: Long,
    targetsUs: List<Long>,
    thresholdUs: Long,
): Long {
    if (thresholdUs <= 0L) return candidateUs
    return targetsUs
        .minByOrNull { abs(it - candidateUs) }
        ?.takeIf { abs(it - candidateUs) <= thresholdUs }
        ?: candidateUs
}

/** Clip edges, the playhead and markers are snap targets; the dragged clip is excluded. */
internal fun snapTargetsUs(
    project: TimelineProjectUi,
    playheadUs: Long,
    markers: List<TimelineMarker>,
    excludeClipId: String?,
): List<Long> =
    buildList {
        project.tracks.forEach { track ->
            track.clips.forEach { clip ->
                if (clip.id != excludeClipId) {
                    add(clip.startUs)
                    add(clip.startUs + clip.durationUs)
                }
            }
        }
        add(playheadUs)
        markers.forEach { add(it.ptsUs) }
    }

internal fun trimRange(
    startUs: Long,
    endUs: Long,
    deltaUs: Long,
    edge: ClipEdge,
    minDurationUs: Long = MIN_CLIP_DURATION_US,
): Pair<Long, Long> =
    when (edge) {
        ClipEdge.START -> {
            val maxStart = (endUs - minDurationUs).coerceAtLeast(0L)
            val newStart = (startUs + deltaUs).coerceIn(0L, maxStart)
            newStart to endUs
        }

        ClipEdge.END -> {
            val newEnd = (endUs + deltaUs).coerceAtLeast(startUs + minDurationUs)
            startUs to newEnd
        }
    }
