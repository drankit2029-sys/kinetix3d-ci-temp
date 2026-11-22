package com.kinetix3d.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectEditorTest {
    private val settings = CompSettings(width = 1920, height = 1080, fps = 30.0)

    private fun state(): ProjectState {
        val clip =
            ClipNode(
                clipId = "clip-1",
                trackId = "V1",
                timelineInUs = 0L,
                timelineOutUs = 2_000_000L,
                sourceInUs = 500_000L,
                sourceOutUs = 2_500_000L,
            )
        val composition =
            CompositionGraph(
                compositionId = "main",
                name = "Main",
                settings = settings,
                clips = persistentMapOf(clip.clipId to clip),
                tracks =
                    persistentListOf(
                        TrackNode("V1", TrackType.VIDEO),
                        TrackNode("A1", TrackType.AUDIO),
                    ),
            )
        return ProjectState(
            projectId = "demo",
            settings = settings,
            compositions = persistentMapOf("main" to composition),
            activeCompId = "main",
        )
    }

    @Test
    fun splitAtMiddleCreatesTwoContiguousClips() {
        val result = ProjectEditor.splitClip(state(), "clip-1", 1_000_000L)
        val clips = result.activeComposition().clips
        val left = clips.getValue("clip-1")
        val right = clips.getValue("clip-1-2")
        assertEquals(0L, left.timelineInUs)
        assertEquals(1_000_000L, left.timelineOutUs)
        assertEquals(500_000L, left.sourceInUs)
        assertEquals(1_500_000L, left.sourceOutUs)
        assertEquals(1_000_000L, right.timelineInUs)
        assertEquals(2_000_000L, right.timelineOutUs)
        assertEquals(1_500_000L, right.sourceInUs)
        assertEquals(2_500_000L, right.sourceOutUs)
    }

    @Test
    fun splitAtEdgeIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.splitClip(state, "clip-1", 0L))
        assertSame(state, ProjectEditor.splitClip(state, "clip-1", 2_000_000L))
        assertSame(state, ProjectEditor.splitClip(state, "missing", 1_000_000L))
    }

    @Test
    fun repeatedSplitsUseUniqueIds() {
        val once = ProjectEditor.splitClip(state(), "clip-1", 1_000_000L)
        val twice = ProjectEditor.splitClip(once, "clip-1-2", 1_500_000L)
        val ids = twice.activeComposition().clips.keys
        assertTrue("clip-1" in ids)
        assertTrue("clip-1-2" in ids)
        assertTrue("clip-1-2-2" in ids)
    }

    @Test
    fun splitAllSplitsEverySpanningClip() {
        val state = state()
        val composition = state.activeComposition()
        val second =
            ClipNode(
                clipId = "clip-2",
                trackId = "V2",
                timelineInUs = 500_000L,
                timelineOutUs = 2_500_000L,
            )
        val withSecond =
            state.copy(
                compositions =
                    state.compositions.putting(
                        composition.compositionId,
                        composition.copy(clips = composition.clips.putting(second.clipId, second)),
                    ),
            )
        val result = ProjectEditor.splitAll(withSecond, 1_000_000L)
        val clips = result.activeComposition().clips
        assertTrue("clip-1-2" in clips)
        assertTrue("clip-2-2" in clips)
        assertEquals(1_000_000L, clips.getValue("clip-1").timelineOutUs)
        assertEquals(1_000_000L, clips.getValue("clip-2").timelineOutUs)
    }

    @Test
    fun splitAllWithoutSpanningClipsIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.splitAll(state, 0L))
        assertSame(state, ProjectEditor.splitAll(state, 5_000_000L))
    }

    @Test
    fun rippleTrimOutShiftsLaterClipsOnTheSameTrack() {
        val base = state()
        val composition = base.activeComposition()
        val later =
            ClipNode(
                clipId = "clip-2",
                trackId = "V1",
                timelineInUs = 2_000_000L,
                timelineOutUs = 4_000_000L,
            )
        val otherTrack =
            ClipNode(
                clipId = "clip-3",
                trackId = "V2",
                timelineInUs = 2_000_000L,
                timelineOutUs = 4_000_000L,
            )
        val withClips =
            base.copy(
                compositions =
                    base.compositions.putting(
                        composition.compositionId,
                        composition.copy(
                            clips =
                                composition.clips
                                    .putting(later.clipId, later)
                                    .putting(otherTrack.clipId, otherTrack),
                        ),
                    ),
            )
        val result = ProjectEditor.rippleTrimOut(withClips, "clip-1", 1_500_000L)
        val clips = result.activeComposition().clips
        assertEquals(1_500_000L, clips.getValue("clip-1").timelineOutUs)
        assertEquals(1_500_000L, clips.getValue("clip-2").timelineInUs)
        assertEquals(3_500_000L, clips.getValue("clip-2").timelineOutUs)
        assertEquals(2_000_000L, clips.getValue("clip-3").timelineInUs)
    }

    @Test
    fun rippleTrimOutExtendsAndShiftsLaterClips() {
        val base = state()
        val composition = base.activeComposition()
        val later =
            ClipNode(
                clipId = "clip-2",
                trackId = "V1",
                timelineInUs = 2_000_000L,
                timelineOutUs = 4_000_000L,
            )
        val withClips =
            base.copy(
                compositions =
                    base.compositions.putting(
                        composition.compositionId,
                        composition.copy(clips = composition.clips.putting(later.clipId, later)),
                    ),
            )
        val result = ProjectEditor.rippleTrimOut(withClips, "clip-1", 2_500_000L)
        val clips = result.activeComposition().clips
        assertEquals(2_500_000L, clips.getValue("clip-2").timelineInUs)
        assertEquals(4_500_000L, clips.getValue("clip-2").timelineOutUs)
    }

    @Test
    fun rippleTrimOutClampsToTheMinimumDuration() {
        val result = ProjectEditor.rippleTrimOut(state(), "clip-1", 0L)
        assertEquals(
            MIN_CLIP_DURATION_US,
            result
                .activeComposition()
                .clips
                .getValue("clip-1")
                .timelineOutUs,
        )
    }

    @Test
    fun rippleTrimOutMissingClipIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.rippleTrimOut(state, "missing", 1_000_000L))
    }

    @Test
    fun detachAudioCreatesALinkedAudioClip() {
        val result = ProjectEditor.detachAudio(state(), "clip-1")
        val clips = result.activeComposition().clips
        val audio = clips.getValue("clip-1-audio")
        assertEquals("A2", audio.trackId)
        assertEquals(AudioClipPayload, audio.payload)
        assertEquals("link-clip-1", audio.linkGroupId)
        assertEquals("link-clip-1", clips.getValue("clip-1").linkGroupId)
        assertEquals(0L, audio.timelineInUs)
        assertEquals(2_000_000L, audio.timelineOutUs)
    }

    @Test
    fun detachAudioTwiceIsANoOp() {
        val once = ProjectEditor.detachAudio(state(), "clip-1")
        assertSame(once, ProjectEditor.detachAudio(once, "clip-1"))
    }

    @Test
    fun detachAudioIsANoOpForAudioClips() {
        val base = state()
        val composition = base.activeComposition()
        val audio =
            ClipNode(
                clipId = "audio-1",
                trackId = "A1",
                timelineInUs = 0L,
                timelineOutUs = 1_000_000L,
                payload = AudioClipPayload,
            )
        val withAudio =
            base.copy(
                compositions =
                    base.compositions.putting(
                        composition.compositionId,
                        composition.copy(clips = composition.clips.putting(audio.clipId, audio)),
                    ),
            )
        assertSame(withAudio, ProjectEditor.detachAudio(withAudio, "audio-1"))
    }

    @Test
    fun detachAudioCreatesAnAudioTrackWhenMissing() {
        val base = state()
        val composition = base.activeComposition()
        val videoOnly =
            base.copy(
                compositions =
                    base.compositions.putting(
                        composition.compositionId,
                        composition.copy(tracks = persistentListOf(TrackNode("V1", TrackType.VIDEO))),
                    ),
            )
        val result = ProjectEditor.detachAudio(videoOnly, "clip-1")
        assertEquals(
            "A1",
            result
                .activeComposition()
                .clips
                .getValue("clip-1-audio")
                .trackId,
        )
        assertTrue(result.activeComposition().tracks.any { it.trackId == "A1" })
    }

    @Test
    fun addMarkerAppends() {
        val result = ProjectEditor.addMarker(state(), 1_500_000L, "Beat")
        assertEquals(1, result.markers.size)
        assertEquals(1_500_000L, result.markers.first().ptsUs)
        assertEquals("Beat", result.markers.first().label)
    }

    @Test
    fun removeMarkerDeletesIt() {
        val withMarker = ProjectEditor.addMarker(state(), 1_000_000L)
        val result = ProjectEditor.removeMarker(withMarker, withMarker.markers.first().markerId)
        assertTrue(result.markers.isEmpty())
    }

    @Test
    fun removeMissingMarkerIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.removeMarker(state, "missing"))
    }

    @Test
    fun addClipCreatesAVideoClipOnTheTrack() {
        val result =
            ProjectEditor.addClip(
                state = state(),
                trackId = "V1",
                startUs = 3_000_000L,
                durationUs = 2_000_000L,
                sourceInUs = 500_000L,
                assetId = "/tmp/overlay.mp4",
            )
        val added =
            result
                .activeComposition()
                .clips.values
                .first { it.clipId != "clip-1" }
        assertEquals("V1", added.trackId)
        assertEquals(3_000_000L, added.timelineInUs)
        assertEquals(5_000_000L, added.timelineOutUs)
        assertEquals(500_000L, added.sourceInUs)
        assertEquals(2_500_000L, added.sourceOutUs)
        assertEquals("/tmp/overlay.mp4", added.assetId)
    }

    @Test
    fun addClipRejectsAudioAndMissingTracks() {
        val state = state()
        assertSame(state, ProjectEditor.addClip(state, "A1", 0L, 1_000_000L))
        assertSame(state, ProjectEditor.addClip(state, "missing", 0L, 1_000_000L))
    }

    @Test
    fun moveClipShiftsTheTimelineRange() {
        val result = ProjectEditor.moveClip(state(), "clip-1", 3_000_000L)
        val clip = result.activeComposition().clips.getValue("clip-1")
        assertEquals(3_000_000L, clip.timelineInUs)
        assertEquals(5_000_000L, clip.timelineOutUs)
        assertEquals(500_000L, clip.sourceInUs)
        assertEquals(2_500_000L, clip.sourceOutUs)
    }

    private fun threeAdjacentClips(): ProjectState {
        val base = state()
        val composition = base.activeComposition()
        val second = ClipNode("clip-2", "V1", 2_000_000L, 4_000_000L)
        val third = ClipNode("clip-3", "V1", 4_000_000L, 6_000_000L)
        return base.copy(
            compositions =
                base.compositions.putting(
                    composition.compositionId,
                    composition.copy(
                        clips =
                            composition.clips
                                .putting(second.clipId, second)
                                .putting(third.clipId, third),
                    ),
                ),
        )
    }

    @Test
    fun slideClipRightExtendsPreviousAndShortensNext() {
        val result = ProjectEditor.slideClip(threeAdjacentClips(), "clip-2", 500_000L)
        val clips = result.activeComposition().clips
        assertEquals(2_500_000L, clips.getValue("clip-2").timelineInUs)
        assertEquals(4_500_000L, clips.getValue("clip-2").timelineOutUs)
        assertEquals(2_500_000L, clips.getValue("clip-1").timelineOutUs)
        assertEquals(4_500_000L, clips.getValue("clip-3").timelineInUs)
    }

    @Test
    fun slideClipLeftShortensPreviousAndExtendsNext() {
        val result = ProjectEditor.slideClip(threeAdjacentClips(), "clip-2", -500_000L)
        val clips = result.activeComposition().clips
        assertEquals(1_500_000L, clips.getValue("clip-2").timelineInUs)
        assertEquals(3_500_000L, clips.getValue("clip-2").timelineOutUs)
        assertEquals(1_500_000L, clips.getValue("clip-1").timelineOutUs)
        assertEquals(3_500_000L, clips.getValue("clip-3").timelineInUs)
    }

    @Test
    fun slideMissingClipIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.slideClip(state, "missing", 500_000L))
    }

    @Test
    fun slipClipShiftsTheSourceRange() {
        val result = ProjectEditor.slipClip(state(), "clip-1", 500_000L)
        val clip = result.activeComposition().clips.getValue("clip-1")
        assertEquals(0L, clip.timelineInUs)
        assertEquals(2_000_000L, clip.timelineOutUs)
        assertEquals(1_000_000L, clip.sourceInUs)
        assertEquals(3_000_000L, clip.sourceOutUs)
    }

    @Test
    fun slipClipClampsAtZero() {
        val result = ProjectEditor.slipClip(state(), "clip-1", -5_000_000L)
        val clip = result.activeComposition().clips.getValue("clip-1")
        assertEquals(0L, clip.sourceInUs)
        assertEquals(2_000_000L, clip.sourceOutUs)
    }

    @Test
    fun slipMissingClipIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.slipClip(state, "missing", 500_000L))
    }

    @Test
    fun moveLinkedClipsTogether() {
        val detached = ProjectEditor.detachAudio(state(), "clip-1")
        val result = ProjectEditor.moveClip(detached, "clip-1", 1_000_000L)
        val clips = result.activeComposition().clips
        assertEquals(1_000_000L, clips.getValue("clip-1").timelineInUs)
        assertEquals(1_000_000L, clips.getValue("clip-1-audio").timelineInUs)
        assertEquals(3_000_000L, clips.getValue("clip-1-audio").timelineOutUs)
    }

    @Test
    fun moveAudioMovesLinkedVideo() {
        val detached = ProjectEditor.detachAudio(state(), "clip-1")
        val result = ProjectEditor.moveClip(detached, "clip-1-audio", 2_000_000L)
        val clips = result.activeComposition().clips
        assertEquals(2_000_000L, clips.getValue("clip-1").timelineInUs)
        assertEquals(2_000_000L, clips.getValue("clip-1-audio").timelineInUs)
    }

    @Test
    fun moveClipClampsAtZero() {
        val result = ProjectEditor.moveClip(state(), "clip-1", -5_000_000L)
        val clip = result.activeComposition().clips.getValue("clip-1")
        assertEquals(0L, clip.timelineInUs)
        assertEquals(2_000_000L, clip.timelineOutUs)
    }

    @Test
    fun moveMissingClipIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.moveClip(state, "missing", 1_000_000L))
    }

    @Test
    fun removeClipDeletesIt() {
        val result = ProjectEditor.removeClip(state(), "clip-1")
        assertTrue(result.activeComposition().clips.isEmpty())
    }

    @Test
    fun removeMissingClipIsANoOp() {
        val state = state()
        assertSame(state, ProjectEditor.removeClip(state, "missing"))
    }
}
