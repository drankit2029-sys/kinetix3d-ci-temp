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

    private fun uniqueClipId(
        composition: CompositionGraph,
        baseId: String,
    ): String {
        var index = 2
        while (true) {
            val candidate = "$baseId-$index"
            if (candidate !in composition.clips) return candidate
            index++
        }
    }
}
