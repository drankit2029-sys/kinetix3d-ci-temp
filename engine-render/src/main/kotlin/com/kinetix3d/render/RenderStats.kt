/*
 * Kinetix3D Studio — engine-render
 * Lightweight preview telemetry posted from the render thread to the UI.
 */

package com.kinetix3d.render

data class RenderStats(
    val fps: Float = 0f,
    val frameMillis: Float = 0f,
    val tiles: Int = 0,
    val format: String = "",
)
