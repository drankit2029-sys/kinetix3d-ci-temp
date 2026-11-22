/*
 * Kinetix3D Studio — core-platform-api
 * Project catalog: lists `.kproj` snapshots and creates new project files.
 */

package com.kinetix3d.platform.project

import com.kinetix3d.model.ProjectFileCodec
import com.kinetix3d.model.ProjectState
import java.io.File

data class ProjectSummary(
    val projectId: String,
    val displayName: String,
    val file: File,
    val modifiedMs: Long,
    val sizeBytes: Long,
)

class ProjectCatalog(
    private val directory: File,
) {
    fun list(): List<ProjectSummary> =
        directory
            .listFiles { file -> file.isFile && file.name.endsWith(".kproj") }
            ?.mapNotNull { file ->
                runCatching {
                    val state = ProjectFileCodec.decode(file.readBytes())
                    ProjectSummary(
                        projectId = state.projectId,
                        displayName = state.activeComposition().name,
                        file = file,
                        modifiedMs = file.lastModified(),
                        sizeBytes = file.length(),
                    )
                }.getOrNull()
            }?.sortedByDescending { it.modifiedMs }
            ?: emptyList()

    fun create(
        projectId: String,
        state: ProjectState,
    ): ProjectSummary {
        directory.mkdirs()
        val file = File(directory, "$projectId.kproj")
        file.writeBytes(ProjectFileCodec.encode(state))
        return ProjectSummary(
            projectId = projectId,
            displayName = state.activeComposition().name,
            file = file,
            modifiedMs = file.lastModified(),
            sizeBytes = file.length(),
        )
    }
}
