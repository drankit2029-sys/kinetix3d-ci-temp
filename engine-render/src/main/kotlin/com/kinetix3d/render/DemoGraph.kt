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

/** Builds a linear chain of the selected demo kernels in list order. */
fun demoKernelGraph(effects: List<String>): NodeGraph {
    if (effects.isEmpty()) return NodeGraph.passthrough()
    val base = NodeGraph.passthrough()
    val input = base.nodes.getValue(NodeSchemaIds.GROUP_INPUT)
    val output = base.nodes.getValue(NodeSchemaIds.GROUP_OUTPUT)
    var previous = input.nodeId
    var nodes = base.nodes
    effects.forEach { effect ->
        val schemaId =
            when (effect) {
                "warp" -> "kinetix.warp"
                "glitch" -> "kinetix.glitch"
                "vectorify" -> "kinetix.vectorify"
                "keyer" -> "kinetix.keyer"
                "oscillate" -> "kinetix.oscillate"
                "optics" -> "kinetix.optics"
                "style" -> "kinetix.layer-style"
                else -> return@forEach
            }
        val params =
            when (effect) {
                "warp" -> {
                    persistentMapOf(
                        "amplitude" to ParamChannel.constant(0.03f),
                        "frequency" to ParamChannel.constant(6f),
                    )
                }

                "glitch" -> {
                    persistentMapOf(
                        "intensity" to ParamChannel.constant(0.6f),
                        "blockSize" to ParamChannel.constant(0.02f),
                    )
                }

                "vectorify" -> {
                    persistentMapOf("levels" to ParamChannel.constant(8f))
                }

                "oscillate" -> {
                    persistentMapOf(
                        "amplitude" to ParamChannel.constant(0.01f),
                        "frequency" to ParamChannel.constant(2f),
                    )
                }

                "optics" -> {
                    persistentMapOf("length" to ParamChannel.constant(0.06f))
                }

                "style" -> {
                    persistentMapOf(
                        "radius" to ParamChannel.constant(0.06f),
                        "opacity" to ParamChannel.constant(0.6f),
                    )
                }

                else -> {
                    persistentMapOf(
                        "tolerance" to ParamChannel.constant(0.2f),
                        "softness" to ParamChannel.constant(0.1f),
                    )
                }
            }
        val node =
            GraphNode(
                nodeId = effect,
                schemaId = schemaId,
                inputs = persistentMapOf("clipSource" to NodeInput.FromNode(previous)),
                params = params,
            )
        nodes = nodes.putting(node.nodeId, node)
        previous = node.nodeId
    }
    return base.copy(
        nodes =
            nodes.putting(
                output.nodeId,
                output.copy(inputs = persistentMapOf("clipSource" to NodeInput.FromNode(previous))),
            ),
    )
}

/** Base + overlay composite; the overlay source is supplied by RenderThread. */
fun demoCompositeGraph(): NodeGraph {
    val base = NodeGraph.passthrough()
    val input = base.nodes.getValue(NodeSchemaIds.GROUP_INPUT)
    val output = base.nodes.getValue(NodeSchemaIds.GROUP_OUTPUT)
    val overlayInput =
        GraphNode(
            nodeId = "overlay-input",
            schemaId = NodeSchemaIds.GROUP_INPUT,
        )
    val composite =
        GraphNode(
            nodeId = "composite",
            schemaId = "kinetix.composite",
            inputs =
                persistentMapOf(
                    "base" to NodeInput.FromNode(input.nodeId),
                    "overlay" to NodeInput.FromNode(overlayInput.nodeId),
                ),
            params = persistentMapOf("opacity" to ParamChannel.constant(0.5f)),
        )
    return base.copy(
        nodes =
            base.nodes
                .putting(overlayInput.nodeId, overlayInput)
                .putting(composite.nodeId, composite)
                .putting(
                    output.nodeId,
                    output.copy(inputs = persistentMapOf("clipSource" to NodeInput.FromNode(composite.nodeId))),
                ),
    )
}

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
