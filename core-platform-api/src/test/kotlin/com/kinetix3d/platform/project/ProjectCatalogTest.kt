package com.kinetix3d.platform.project

import com.kinetix3d.model.ClipNode
import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.CompositionGraph
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.TrackNode
import com.kinetix3d.model.TrackType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectCatalogTest {
    private val settings = CompSettings(width = 1920, height = 1080, fps = 30.0)

    private fun state(projectId: String): ProjectState {
        val clip = ClipNode("clip-1", "V1", 0L, 2_000_000L)
        val composition =
            CompositionGraph(
                compositionId = "main",
                name = "Main",
                settings = settings,
                clips = persistentMapOf(clip.clipId to clip),
                tracks = persistentListOf(TrackNode("V1", TrackType.VIDEO)),
            )
        return ProjectState(
            projectId = projectId,
            settings = settings,
            compositions = persistentMapOf("main" to composition),
            activeCompId = "main",
        )
    }

    @Test
    fun createThenListRoundTrips() {
        val directory = Files.createTempDirectory("kproj-catalog").toFile()
        try {
            val catalog = ProjectCatalog(directory)
            catalog.create("alpha", state("alpha"))
            val projects = catalog.list()
            assertEquals(1, projects.size)
            assertEquals("alpha", projects.first().projectId)
            assertEquals("Main", projects.first().displayName)
            assertTrue(projects.first().file.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun listIgnoresCorruptFiles() {
        val directory = Files.createTempDirectory("kproj-catalog").toFile()
        try {
            val catalog = ProjectCatalog(directory)
            catalog.create("alpha", state("alpha"))
            File(directory, "broken.kproj").writeText("not a project")
            assertEquals(listOf("alpha"), catalog.list().map { it.projectId })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun listIsEmptyForAMissingDirectory() {
        val directory = File("/tmp/kinetix-missing-${System.nanoTime()}")
        assertTrue(ProjectCatalog(directory).list().isEmpty())
    }
}
