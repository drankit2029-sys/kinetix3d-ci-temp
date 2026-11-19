/*
 * Kinetix3D Studio — core-model
 * Mutation payloads and the pure reducer used for WAL replay. The reducer is
 * intentionally small: it applies the mutations the editor currently emits and
 * leaves unknown ops untouched so older builds can open newer journals.
 */

package com.kinetix3d.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf

@Serializable
data class ClipUpdate(
    val compositionId: String,
    val clip: ClipNode,
)

@Serializable
data class ClipRef(
    val compositionId: String,
    val clipId: String,
)

/** Full-state checkpoint used by undo/redo and other non-incremental edits. */
@Serializable
data class ProjectSnapshot(
    val state: ProjectState,
)

object ProjectMutationCodec {
    fun updateClip(
        compositionId: String,
        clip: ClipNode,
    ): Mutation =
        Mutation(
            op = MutationOp.UPDATE_CLIP,
            targetId = clip.clipId,
            payload = ProtoBuf.encodeToByteArray(ClipUpdate.serializer(), ClipUpdate(compositionId, clip)),
        )

    fun addClip(
        compositionId: String,
        clip: ClipNode,
    ): Mutation =
        Mutation(
            op = MutationOp.ADD_CLIP,
            targetId = clip.clipId,
            payload = ProtoBuf.encodeToByteArray(ClipUpdate.serializer(), ClipUpdate(compositionId, clip)),
        )

    fun removeClip(
        compositionId: String,
        clipId: String,
    ): Mutation =
        Mutation(
            op = MutationOp.REMOVE_CLIP,
            targetId = clipId,
            payload = ProtoBuf.encodeToByteArray(ClipRef.serializer(), ClipRef(compositionId, clipId)),
        )

    fun decodeClipUpdate(mutation: Mutation): ClipUpdate? {
        if (mutation.op != MutationOp.UPDATE_CLIP && mutation.op != MutationOp.ADD_CLIP) return null
        return runCatching {
            ProtoBuf.decodeFromByteArray(ClipUpdate.serializer(), mutation.payload)
        }.getOrNull()
    }

    fun decodeClipRef(mutation: Mutation): ClipRef? {
        if (mutation.op != MutationOp.REMOVE_CLIP) return null
        return runCatching {
            ProtoBuf.decodeFromByteArray(ClipRef.serializer(), mutation.payload)
        }.getOrNull()
    }

    fun replaceState(state: ProjectState): Mutation =
        Mutation(
            op = MutationOp.GENERIC,
            targetId = state.projectId,
            payload = ProtoBuf.encodeToByteArray(ProjectSnapshot.serializer(), ProjectSnapshot(state)),
        )

    fun decodeSnapshot(mutation: Mutation): ProjectSnapshot? {
        if (mutation.op != MutationOp.GENERIC) return null
        return runCatching {
            ProtoBuf.decodeFromByteArray(ProjectSnapshot.serializer(), mutation.payload)
        }.getOrNull()
    }
}

object ProjectReducer {
    fun apply(
        state: ProjectState,
        mutation: Mutation,
    ): ProjectState =
        when (mutation.op) {
            MutationOp.UPDATE_CLIP -> applyClipUpdate(state, mutation, requireExisting = true)
            MutationOp.ADD_CLIP -> applyClipUpdate(state, mutation, requireExisting = false)
            MutationOp.REMOVE_CLIP -> applyClipRemoval(state, mutation)
            MutationOp.GENERIC -> ProjectMutationCodec.decodeSnapshot(mutation)?.state ?: state
            else -> state
        }

    private fun applyClipUpdate(
        state: ProjectState,
        mutation: Mutation,
        requireExisting: Boolean,
    ): ProjectState {
        val update = ProjectMutationCodec.decodeClipUpdate(mutation) ?: return state
        val composition = state.compositions[update.compositionId] ?: return state
        val exists = update.clip.clipId in composition.clips
        if (requireExisting != exists) return state
        val updatedComposition =
            composition.copy(clips = composition.clips.putting(update.clip.clipId, update.clip))
        return state.copy(
            compositions = state.compositions.putting(update.compositionId, updatedComposition),
        )
    }

    private fun applyClipRemoval(
        state: ProjectState,
        mutation: Mutation,
    ): ProjectState {
        val ref = ProjectMutationCodec.decodeClipRef(mutation) ?: return state
        val composition = state.compositions[ref.compositionId] ?: return state
        if (ref.clipId !in composition.clips) return state
        val updatedComposition = composition.copy(clips = composition.clips.remove(ref.clipId))
        return state.copy(
            compositions = state.compositions.putting(ref.compositionId, updatedComposition),
        )
    }

    fun applyAll(
        state: ProjectState,
        mutations: List<Mutation>,
    ): ProjectState = mutations.fold(state) { current, mutation -> apply(current, mutation) }
}
