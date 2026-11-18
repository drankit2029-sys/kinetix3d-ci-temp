package com.kinetix3d.platform.project

import com.kinetix3d.model.ClipNode
import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.CompositionGraph
import com.kinetix3d.model.InMemoryWalJournal
import com.kinetix3d.model.ProjectMutationCodec
import com.kinetix3d.model.ProjectReducer
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.TrackNode
import com.kinetix3d.model.TrackType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectStoreTest {
    private val settings = CompSettings(width = 1920, height = 1080, fps = 30.0)

    private fun state(outUs: Long = 2_000_000L): ProjectState {
        val clip =
            ClipNode(
                clipId = "clip-1",
                trackId = "V1",
                timelineInUs = 0L,
                timelineOutUs = outUs,
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
            projectId = "demo",
            settings = settings,
            compositions = persistentMapOf("main" to composition),
            activeCompId = "main",
        )
    }

    private fun withTempDir(block: suspend (File) -> Unit) =
        runBlocking {
            val directory = Files.createTempDirectory("kproj-test").toFile()
            try {
                block(directory)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun saveThenLoadRoundTrips() =
        withTempDir { directory ->
            val store = ProjectStore(directory, InMemoryWalJournal(), "demo")
            val saved = state()
            store.save(saved)
            assertEquals(saved, store.load())
            assertTrue(File(directory, "demo.kproj").isFile)
        }

    @Test
    fun loadReturnsNullWithoutSnapshot() =
        withTempDir { directory ->
            val store = ProjectStore(directory, InMemoryWalJournal(), "demo")
            assertNull(store.load())
        }

    @Test
    fun saveReplacesAnExistingSnapshot() =
        withTempDir { directory ->
            val store = ProjectStore(directory, InMemoryWalJournal(), "demo")
            store.save(state(outUs = 2_000_000L))
            store.save(state(outUs = 1_500_000L))
            assertEquals(
                1_500_000L,
                store
                    .load()
                    ?.activeComposition()
                    ?.clips
                    ?.getValue("clip-1")
                    ?.timelineOutUs,
            )
        }

    @Test
    fun recordClipUpdateAppendsWalAndSaves() =
        withTempDir { directory ->
            val wal = InMemoryWalJournal()
            val store = ProjectStore(directory, wal, "demo")
            val base = state()
            store.save(base)
            val updatedClip =
                base
                    .activeComposition()
                    .clips
                    .getValue("clip-1")
                    .copy(timelineOutUs = 1_500_000L)
            val updatedState = ProjectReducer.apply(base, ProjectMutationCodec.updateClip("main", updatedClip))
            val seq = store.recordClipUpdate(updatedState, updatedClip)
            assertEquals(1L, seq)
            assertEquals(1, wal.readRecordsFrom(0).size)
            assertEquals(updatedState, store.load())
        }

    @Test
    fun recoverReplaysWalOnTopOfSnapshot() =
        withTempDir { directory ->
            val wal = InMemoryWalJournal()
            val store = ProjectStore(directory, wal, "demo")
            val base = state()
            store.save(base)
            val updatedClip =
                base
                    .activeComposition()
                    .clips
                    .getValue("clip-1")
                    .copy(timelineOutUs = 1_500_000L)
            val updatedState = ProjectReducer.apply(base, ProjectMutationCodec.updateClip("main", updatedClip))
            wal.append(ProjectMutationCodec.updateClip("main", updatedClip))

            val recovered = store.recover(base)
            assertEquals(updatedState, recovered)
            assertEquals(updatedState, store.load())
            assertEquals(1L, wal.lastAppliedSeq())
            assertTrue(wal.readRecordsFrom(wal.lastAppliedSeq()).isEmpty())
        }

    @Test
    fun recoverWithoutWalReturnsTheBaseState() =
        withTempDir { directory ->
            val store = ProjectStore(directory, InMemoryWalJournal(), "demo")
            val base = state()
            assertEquals(base, store.recover(base))
        }
}
