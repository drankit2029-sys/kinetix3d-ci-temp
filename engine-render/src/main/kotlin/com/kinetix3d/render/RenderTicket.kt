/*
 * Kinetix3D Studio — engine-render
 * Immutable snapshot posted to the RenderThread by the UI thread.
 */

package com.kinetix3d.render

import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.TransientOverrideMap

data class RenderTicket(
    val state: ProjectState,
    val overrides: TransientOverrideMap,
    val ptsUs: Long,
    val previewScale: Float,
    val generation: Long,
    val graph: NodeGraph = NodeGraph.passthrough(),
    val playing: Boolean = false,
)
