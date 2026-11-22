/*
 * Kinetix3D Studio — ui-studio
 * Export request/state contract shared by the studio UI and the app's
 * ExportForegroundService.
 */

package com.kinetix3d.ui

data class ExportRequest(
    val sourcePath: String,
    val startUs: Long,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val effects: List<String>,
)

sealed interface ExportState {
    data object Idle : ExportState

    data class Running(
        val progress: Float,
    ) : ExportState

    data class Success(
        val uri: String,
        val frames: Int,
    ) : ExportState

    data class Failure(
        val message: String,
    ) : ExportState
}
