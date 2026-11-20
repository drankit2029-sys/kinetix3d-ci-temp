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
        val moved =
            clip.copy(
                timelineInUs = start,
                timelineOutUs = clip.timelineOutUs + delta,
            )
        val updatedComposition = composition.copy(clips = composition.clips.putting(clipId, moved))
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
