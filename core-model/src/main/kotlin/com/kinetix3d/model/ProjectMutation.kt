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

    fun decodeClipUpdate(mutation: Mutation): ClipUpdate? {
        if (mutation.op != MutationOp.UPDATE_CLIP) return null
        return runCatching {
            ProtoBuf.decodeFromByteArray(ClipUpdate.serializer(), mutation.payload)
        }.getOrNull()
    }
}

object ProjectReducer {
    fun apply(
        state: ProjectState,
        mutation: Mutation,
    ): ProjectState {
        val update = ProjectMutationCodec.decodeClipUpdate(mutation) ?: return state
        val composition = state.compositions[update.compositionId] ?: return state
        if (update.clip.clipId !in composition.clips) return state
        val updatedComposition =
            composition.copy(clips = composition.clips.putting(update.clip.clipId, update.clip))
        return state.copy(
            compositions = state.compositions.putting(update.compositionId, updatedComposition),
        )
    }

    fun applyAll(
        state: ProjectState,
        mutations: List<Mutation>,
    ): ProjectState = mutations.fold(state) { current, mutation -> apply(current, mutation) }
}
