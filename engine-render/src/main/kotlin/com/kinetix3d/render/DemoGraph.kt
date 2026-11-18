/*
 * Kinetix3D Studio — engine-render
 * Shared demo clip graph used by the standalone render view and the studio UI.
 */

package com.kinetix3d.render

import com.kinetix3d.model.GraphNode
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.NodeInput
import com.kinetix3d.model.NodeSchemaIds
import com.kinetix3d.model.ParamChannel
import kotlinx.collections.immutable.persistentMapOf

fun demoClipGraph(): NodeGraph = NodeGraph.passthrough()

fun demoEffectsGraph(): NodeGraph {
    val base = NodeGraph.passthrough()
    val input = base.nodes.getValue(NodeSchemaIds.GROUP_INPUT)
    val output = base.nodes.getValue(NodeSchemaIds.GROUP_OUTPUT)
    val color =
        GraphNode(
            nodeId = "color",
            schemaId = "kinetix.color-suite",
            inputs = persistentMapOf("clipSource" to NodeInput.FromNode(input.nodeId)),
            params =
                persistentMapOf(
                    "contrast" to ParamChannel.constant(1.15f),
                    "saturation" to ParamChannel.constant(1.3f),
                    "temperature" to ParamChannel.constant(0.1f),
                ),
        )
    val blur =
        GraphNode(
            nodeId = "blur",
            schemaId = "kinetix.blur",
            inputs = persistentMapOf("clipSource" to NodeInput.FromNode(color.nodeId)),
            params = persistentMapOf("radius" to ParamChannel.constant(0.02f)),
        )
    val glow =
        GraphNode(
            nodeId = "glow",
            schemaId = "kinetix.glow",
            inputs = persistentMapOf("clipSource" to NodeInput.FromNode(blur.nodeId)),
            params =
                persistentMapOf(
                    "threshold" to ParamChannel.constant(0.8f),
                    "intensity" to ParamChannel.constant(0.4f),
                    "radius" to ParamChannel.constant(0.03f),
                ),
        )
    return base.copy(
        nodes =
            base.nodes
                .putting(color.nodeId, color)
                .putting(blur.nodeId, blur)
                .putting(glow.nodeId, glow)
                .putting(
                    output.nodeId,
                    output.copy(inputs = persistentMapOf("clipSource" to NodeInput.FromNode(glow.nodeId))),
                ),
    )
}
