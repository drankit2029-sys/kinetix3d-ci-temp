/*
 * Kinetix3D Studio — core-model
 * Pure timeline edit operations shared by the UI, WAL replay and export.
 */

package com.kinetix3d.model

const val MIN_CLIP_DURATION_US: Long = 100_000L

object ProjectEditor {
    /**
     * Splits [clipId] at [atUs]. The left half keeps the original id; the right
     * half gets a unique `<id>-b` (then `-c`, …) id. Returns the original state
     * when the split point is outside the clip or too close to an edge.
     */
    fun splitClip(
        state: ProjectState,
        clipId: String,
        atUs: Long,
    ): ProjectState {
        val composition = state.activeComposition()
        val clip = composition.clips[clipId] ?: return state
        if (atUs <= clip.timelineInUs + MIN_CLIP_DURATION_US) return state
        if (atUs >= clip.timelineOutUs - MIN_CLIP_DURATION_US) return state
        val leftSourceOut = clip.sourceInUs + (atUs - clip.timelineInUs)
        val left =
            clip.copy(
                timelineOutUs = atUs,
                sourceOutUs = leftSourceOut.coerceAtMost(clip.sourceOutUs),
            )
        val right =
            clip.copy(
                clipId = uniqueClipId(composition, clipId),
                timelineInUs = atUs,
                sourceInUs = left.sourceOutUs,
            )
        val updatedComposition =
            composition.copy(
                clips =
                    composition.clips
                        .putting(left.clipId, left)
                        .putting(right.clipId, right),
            )
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    /** Splits every clip that spans [atUs] across all tracks. */
    fun splitAll(
        state: ProjectState,
        atUs: Long,
    ): ProjectState {
        val composition = state.activeComposition()
        val ids =
            composition.clips.values
                .filter { atUs > it.timelineInUs + MIN_CLIP_DURATION_US }
                .filter { atUs < it.timelineOutUs - MIN_CLIP_DURATION_US }
                .map { it.clipId }
        return ids.fold(state) { current, id -> splitClip(current, id, atUs) }
    }

    /**
     * Trims the clip's out point and shifts every later clip on the same track
     * by the same delta, closing or opening the gap.
     */
    fun rippleTrimOut(
        state: ProjectState,
        clipId: String,
        newOutUs: Long,
    ): ProjectState {
        val composition = state.activeComposition()
        val clip = composition.clips[clipId] ?: return state
        val clampedOut = newOutUs.coerceAtLeast(clip.timelineInUs + MIN_CLIP_DURATION_US)
        val delta = clampedOut - clip.timelineOutUs
        if (delta == 0L) return state
        val trimmed =
            clip.copy(
                timelineOutUs = clampedOut,
                sourceOutUs = (clip.sourceOutUs + delta).coerceAtLeast(clip.sourceInUs),
            )
        var updatedClips = composition.clips.putting(clipId, trimmed)
        composition.clips.forEach { (id, other) ->
            if (id != clipId && other.trackId == clip.trackId && other.timelineInUs >= clip.timelineOutUs) {
                updatedClips =
                    updatedClips.putting(
                        id,
                        other.copy(
                            timelineInUs = (other.timelineInUs + delta).coerceAtLeast(0L),
                            timelineOutUs = (other.timelineOutUs + delta).coerceAtLeast(MIN_CLIP_DURATION_US),
                        ),
                    )
            }
        }
        val updatedComposition = composition.copy(clips = updatedClips)
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    /**
     * Creates a linked audio clip on the first audio track for a video clip.
     * The video and audio clips share a [ClipNode.linkGroupId] so later tools
     * can move/trim them together.
     */
    fun detachAudio(
        state: ProjectState,
        clipId: String,
    ): ProjectState {
        val composition = state.activeComposition()
        val clip = composition.clips[clipId] ?: return state
        if (clip.payload !is VideoClipPayload) return state
        if (clip.linkGroupId != null) return state
        val audioTrackId = nextAudioTrackId(composition)
        val linkGroup = "link-$clipId"
        val audioClip =
            clip.copy(
                clipId = uniqueClipId(composition, "$clipId-audio"),
                trackId = audioTrackId,
                payload = AudioClipPayload,
                nodeGraph = NodeGraph(),
                linkGroupId = linkGroup,
            )
        val linkedVideo = clip.copy(linkGroupId = linkGroup)
        val updatedComposition =
            composition.copy(
                tracks = composition.tracks.add(TrackNode(audioTrackId, TrackType.AUDIO)),
                clips =
                    composition.clips
                        .putting(clipId, linkedVideo)
                        .putting(audioClip.clipId, audioClip),
            )
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    fun addMarker(
        state: ProjectState,
        ptsUs: Long,
        label: String = "",
    ): ProjectState {
        val marker =
            TimelineMarker(
                markerId = "marker-${state.markers.size + 1}-${ptsUs.coerceAtLeast(0L)}",
                ptsUs = ptsUs.coerceAtLeast(0L),
                label = label,
            )
        return state.copy(markers = state.markers.add(marker))
    }

    fun removeMarker(
        state: ProjectState,
        markerId: String,
    ): ProjectState {
        if (state.markers.none { it.markerId == markerId }) return state
        return state.copy(markers = state.markers.removeAll { it.markerId == markerId })
    }

    /** Adds a video clip to [trackId] at [startUs] with an optional source offset. */
    fun addClip(
        state: ProjectState,
        trackId: String,
        startUs: Long,
        durationUs: Long,
        sourceInUs: Long = 0L,
        assetId: String? = null,
    ): ProjectState {
        val composition = state.activeComposition()
        val track = composition.tracks.firstOrNull { it.trackId == trackId } ?: return state
        if (track.type != TrackType.VIDEO) return state
        val start = startUs.coerceAtLeast(0L)
        val duration = durationUs.coerceAtLeast(MIN_CLIP_DURATION_US)
        val sourceIn = sourceInUs.coerceAtLeast(0L)
        val clip =
            ClipNode(
                clipId = uniqueClipId(composition, "clip-${composition.clips.size + 1}"),
                trackId = trackId,
                timelineInUs = start,
                timelineOutUs = start + duration,
                sourceInUs = sourceIn,
                sourceOutUs = sourceIn + duration,
                assetId = assetId,
                payload = VideoClipPayload,
            )
        val updatedComposition = composition.copy(clips = composition.clips.putting(clip.clipId, clip))
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    /** Shifts the clip's source range without moving its timeline range (slip). */
    fun slipClip(
        state: ProjectState,
        clipId: String,
        deltaUs: Long,
    ): ProjectState {
        val composition = state.activeComposition()
        val clip = composition.clips[clipId] ?: return state
        if (deltaUs == 0L) return state
        val newIn = (clip.sourceInUs + deltaUs).coerceAtLeast(0L)
        val actualDelta = newIn - clip.sourceInUs
        if (actualDelta == 0L) return state
        val slipped =
            clip.copy(
                sourceInUs = newIn,
                sourceOutUs = clip.sourceOutUs + actualDelta,
            )
        val updatedComposition = composition.copy(clips = composition.clips.putting(clipId, slipped))
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    /**
     * Slides a clip by [deltaUs], extending the previous clip's out point and
     * shortening the next clip's in point so the track duration is unchanged.
     */
    fun slideClip(
        state: ProjectState,
        clipId: String,
        deltaUs: Long,
    ): ProjectState {
        val composition = state.activeComposition()
        val clip = composition.clips[clipId] ?: return state
        if (deltaUs == 0L) return state
        val trackClips =
            composition.clips.values
                .filter { it.trackId == clip.trackId }
                .sortedBy { it.timelineInUs }
        val index = trackClips.indexOfFirst { it.clipId == clipId }
        if (index < 0) return state
        val previous = trackClips.getOrNull(index - 1)
        val next = trackClips.getOrNull(index + 1)
        val newStart = (clip.timelineInUs + deltaUs).coerceAtLeast(0L)
        val actualDelta = newStart - clip.timelineInUs
        if (actualDelta == 0L) return state
        var updated = composition.clips
        updated =
            updated.putting(
                clipId,
                clip.copy(
                    timelineInUs = newStart,
                    timelineOutUs = newStart + clip.durationUs,
                ),
            )
        if (previous != null) {
            val previousOut =
                (previous.timelineOutUs + actualDelta)
                    .coerceAtLeast(previous.timelineInUs + MIN_CLIP_DURATION_US)
            updated = updated.putting(previous.clipId, previous.copy(timelineOutUs = previousOut))
        }
        if (next != null) {
            val nextIn =
                (next.timelineInUs + actualDelta)
                    .coerceAtMost(next.timelineOutUs - MIN_CLIP_DURATION_US)
                    .coerceAtLeast(0L)
            updated = updated.putting(next.clipId, next.copy(timelineInUs = nextIn))
        }
        val updatedComposition = composition.copy(clips = updated)
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    /** Moves a clip to [newStartUs], preserving its duration and source range. */
    fun moveClip(
        state: ProjectState,
        clipId: String,
        newStartUs: Long,
    ): ProjectState {
        val composition = state.activeComposition()
        val clip = composition.clips[clipId] ?: return state
        val start = newStartUs.coerceAtLeast(0L)
        if (start == clip.timelineInUs) return state
        val delta = start - clip.timelineInUs
        val linkGroup = clip.linkGroupId
        var updatedClips = composition.clips
        composition.clips.values.forEach { other ->
            if (other.clipId == clipId || (linkGroup != null && other.linkGroupId == linkGroup)) {
                val movedStart = (other.timelineInUs + delta).coerceAtLeast(0L)
                updatedClips =
                    updatedClips.putting(
                        other.clipId,
                        other.copy(
                            timelineInUs = movedStart,
                            timelineOutUs = movedStart + other.durationUs,
                        ),
                    )
            }
        }
        val updatedComposition = composition.copy(clips = updatedClips)
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    fun removeClip(
        state: ProjectState,
        clipId: String,
    ): ProjectState {
        val composition = state.activeComposition()
        if (clipId !in composition.clips) return state
        val updatedComposition = composition.copy(clips = composition.clips.remove(clipId))
        return state.copy(
            compositions = state.compositions.putting(composition.compositionId, updatedComposition),
        )
    }

    private fun nextAudioTrackId(composition: CompositionGraph): String {
        val highest =
            composition.tracks
                .mapNotNull { track -> track.trackId.removePrefix("A").toIntOrNull() }
                .maxOrNull()
                ?: 0
        return "A${highest + 1}"
    }

    private fun uniqueClipId(
        composition: CompositionGraph,
        baseId: String,
    ): String {
        if (baseId !in composition.clips) return baseId
        var index = 2
        while (true) {
            val candidate = "$baseId-$index"
            if (candidate !in composition.clips) return candidate
            index++
        }
    }
}
