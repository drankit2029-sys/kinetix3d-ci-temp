package com.kinetix3d.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectReducerTest {
    private val settings = CompSettings(width = 1920, height = 1080, fps = 30.0)

    private fun stateWithClip(clip: ClipNode): ProjectState {
        val composition =
            CompositionGraph(
                compositionId = "main",
                name = "Main",
                settings = settings,
                clips = persistentMapOf(clip.clipId to clip),
                tracks = persistentListOf(TrackNode("V1", TrackType.VIDEO)),
            )
        return ProjectState(
            projectId = "sample",
            settings = settings,
            compositions = persistentMapOf("main" to composition),
            activeCompId = "main",
        )
    }

    private fun clip(
        id: String = "clip-1",
        inUs: Long = 0L,
        outUs: Long = 2_000_000L,
    ): ClipNode =
        ClipNode(
            clipId = id,
            trackId = "V1",
            timelineInUs = inUs,
            timelineOutUs = outUs,
        )

    @Test
    fun updateClipReplacesTheClip() {
        val state = stateWithClip(clip())
        val updated = clip(inUs = 500_000L, outUs = 1_500_000L)
        val result = ProjectReducer.apply(state, ProjectMutationCodec.updateClip("main", updated))
        assertEquals(updated, result.activeComposition().clips.getValue("clip-1"))
    }

    @Test
    fun unknownClipIsANoOp() {
        val state = stateWithClip(clip())
        val mutation = ProjectMutationCodec.updateClip("main", clip(id = "missing"))
        assertSame(state, ProjectReducer.apply(state, mutation))
    }

    @Test
    fun unknownCompositionIsANoOp() {
        val state = stateWithClip(clip())
        val mutation = ProjectMutationCodec.updateClip("other", clip())
        assertSame(state, ProjectReducer.apply(state, mutation))
    }

    @Test
    fun nonClipMutationIsANoOp() {
        val state = stateWithClip(clip())
        val mutation = Mutation(op = MutationOp.GENERIC, targetId = "clip-1")
        assertSame(state, ProjectReducer.apply(state, mutation))
    }

    @Test
    fun addClipInsertsANewClip() {
        val state = stateWithClip(clip())
        val added = clip(id = "clip-2", inUs = 2_000_000L, outUs = 3_000_000L)
        val result = ProjectReducer.apply(state, ProjectMutationCodec.addClip("main", added))
        assertEquals(added, result.activeComposition().clips.getValue("clip-2"))
    }

    @Test
    fun addExistingClipIsANoOp() {
        val state = stateWithClip(clip())
        val mutation = ProjectMutationCodec.addClip("main", clip(inUs = 500_000L, outUs = 1_500_000L))
        assertSame(state, ProjectReducer.apply(state, mutation))
    }

    @Test
    fun removeClipDeletesIt() {
        val state = stateWithClip(clip())
        val result = ProjectReducer.apply(state, ProjectMutationCodec.removeClip("main", "clip-1"))
        assertTrue(result.activeComposition().clips.isEmpty())
    }

    @Test
    fun removeMissingClipIsANoOp() {
        val state = stateWithClip(clip())
        val mutation = ProjectMutationCodec.removeClip("main", "missing")
        assertSame(state, ProjectReducer.apply(state, mutation))
    }

    @Test
    fun applyAllFoldsInOrder() {
        val state = stateWithClip(clip())
        val first = ProjectMutationCodec.updateClip("main", clip(inUs = 100_000L, outUs = 1_900_000L))
        val second = ProjectMutationCodec.updateClip("main", clip(inUs = 200_000L, outUs = 1_800_000L))
        val result = ProjectReducer.applyAll(state, listOf(first, second))
        val clip = result.activeComposition().clips.getValue("clip-1")
        assertEquals(200_000L, clip.timelineInUs)
        assertEquals(1_800_000L, clip.timelineOutUs)
    }
}
