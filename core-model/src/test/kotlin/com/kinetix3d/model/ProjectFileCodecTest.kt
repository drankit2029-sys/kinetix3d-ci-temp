package com.kinetix3d.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectFileCodecTest {
    private fun sampleState(): ProjectState {
        val settings = CompSettings(width = 1920, height = 1080, fps = 30.0)
        val clip =
            ClipNode(
                clipId = "clip-1",
                trackId = "V1",
                timelineInUs = 1_000_000L,
                timelineOutUs = 4_000_000L,
                payload = TextClipPayload(TextSource("Hello")),
            )
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

    @Test
    fun roundTripsProjectState() {
        val state = sampleState()
        val decoded = ProjectFileCodec.decode(ProjectFileCodec.encode(state))
        assertEquals(state.projectId, decoded.projectId)
        assertEquals(state.activeCompId, decoded.activeCompId)
        assertEquals(state.settings, decoded.settings)
        assertEquals(state.activeComposition().clips, decoded.activeComposition().clips)
        assertEquals(state.activeComposition().tracks, decoded.activeComposition().tracks)
    }

    @Test
    fun roundTripsTimelineMarkers() {
        val state =
            sampleState().copy(
                markers =
                    persistentListOf(
                        TimelineMarker("m1", 1_000_000L, "Beat"),
                        TimelineMarker("m2", 2_500_000L),
                    ),
            )
        val decoded = ProjectFileCodec.decode(ProjectFileCodec.encode(state))
        assertEquals(state.markers, decoded.markers)
    }

    @Test
    fun rejectsBadMagic() {
        val bytes = ProjectFileCodec.encode(sampleState())
        bytes[0] = 0
        val error = assertFailsWith<IllegalArgumentException> { ProjectFileCodec.decode(bytes) }
        assertTrue(error.message.orEmpty().contains("Not a Kinetix3D project"))
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val bytes = ProjectFileCodec.encode(sampleState())
        bytes[4] = 99
        val error = assertFailsWith<IllegalArgumentException> { ProjectFileCodec.decode(bytes) }
        assertTrue(error.message.orEmpty().contains("Unsupported project file version"))
    }

    @Test
    fun rejectsChecksumMismatch() {
        val bytes = ProjectFileCodec.encode(sampleState())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x5A).toByte()
        val error = assertFailsWith<IllegalArgumentException> { ProjectFileCodec.decode(bytes) }
        assertTrue(error.message.orEmpty().contains("checksum"))
    }

    @Test
    fun rejectsTruncatedFile() {
        val bytes = ProjectFileCodec.encode(sampleState())
        val error =
            assertFailsWith<IllegalArgumentException> {
                ProjectFileCodec.decode(bytes.copyOf(ProjectFileCodec.HEADER_BYTES - 1))
            }
        assertTrue(error.message.orEmpty().contains("truncated"))
    }

    @Test
    fun rejectsLengthMismatch() {
        val bytes = ProjectFileCodec.encode(sampleState())
        val error =
            assertFailsWith<IllegalArgumentException> {
                ProjectFileCodec.decode(bytes.copyOf(bytes.size - 1))
            }
        assertTrue(error.message.orEmpty().contains("length mismatch"))
    }
}
