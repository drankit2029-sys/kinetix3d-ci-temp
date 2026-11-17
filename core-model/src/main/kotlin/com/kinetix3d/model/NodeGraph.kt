/*
 * Kinetix3D Studio — core-model
 * Everything is a NodeGraph: a clip's Effects Rack is an ordered sub-graph, the
 * Node Studio is the same graph exposed free-form, and transitions are dual-input
 * graphs owned by the CompositionGraph.
 *
 * topoSort() lives here (not in :engine-render) so the Phase-2 DagRenderCompiler
 * consumes this exact, unit-tested implementation.
 */

package com.kinetix3d.model

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable
import java.util.TreeSet

object NodeSchemaIds {
    const val GROUP_INPUT = "kinetix.group.input"
    const val GROUP_OUTPUT = "kinetix.group.output"
}

/** A connection into one input socket of a graph node. */
@Serializable
sealed interface NodeInput {
    @Serializable
    data class FromNode(
        val nodeId: String,
        val outputSocket: String = "out",
    ) : NodeInput

    /** Serialized literal (float/color/curve id …) for unconnected sockets. */
    @Serializable
    data class Literal(
        val value: String,
    ) : NodeInput
}

/** Which authoring source currently drives a [ParamChannel]. */
enum class ParamChannelSource {
    /** Static slider value. */
    STATIC,

    /** Keyframed track. */
    CURVE,

    /** Bytecode expression evaluated by :core-evaluator. */
    EXPRESSION,
}

/**
 * Authored value for one numeric operator parameter.
 *
 * Vector/colour parameters are stored as one channel per component (for example
 * `position.x`, `position.y`, `position.z`), so every channel is uniformly
 * keyframable and expression-driven. Precedence is expression > curve > static
 * value; a blank expression and an empty curve are treated as absent.
 */
@Serializable
data class ParamChannel(
    val value: Float = 0f,
    val curve: KeyframeCurve<Float>? = null,
    val expression: String? = null,
) {
    fun source(): ParamChannelSource =
        when {
            !expression.isNullOrBlank() -> ParamChannelSource.EXPRESSION
            curve != null && !curve.isEmpty -> ParamChannelSource.CURVE
            else -> ParamChannelSource.STATIC
        }

    companion object {
        fun constant(value: Float): ParamChannel = ParamChannel(value = value)

        fun animated(curve: KeyframeCurve<Float>): ParamChannel = ParamChannel(curve = curve)

        fun driven(expression: String): ParamChannel = ParamChannel(expression = expression)
    }
}

@Serializable
data class GraphNode(
    val nodeId: String,
    val schemaId: String,
    @Serializable(with = PersistentMapSerializer::class)
    val inputs: PersistentMap<String, NodeInput> = persistentMapOf(),
    /** Active [KinetixOperatorSchema.modes] entry; `null` selects the schema's first mode. */
    val modeId: String? = null,
    /** Per-parameter authored values, curves and expressions, keyed by [ParamSpec.key]. */
    @Serializable(with = PersistentMapSerializer::class)
    val params: PersistentMap<String, ParamChannel> = persistentMapOf(),
    /** Bypass switch (the UI's `fx` toggle); disabled nodes pass their input through. */
    val enabled: Boolean = true,
    /** Free-form Node Studio canvas position in comp-height units. */
    val canvasPosition: Vec2 = Vec2.ZERO,
) {
    init {
        require(nodeId.isNotBlank()) { "Node id must not be blank" }
        require(schemaId.isNotBlank()) { "Node schema id must not be blank" }
    }
}

@Serializable
sealed interface TopoSortResult {
    /** Execution order: every dependency appears before its dependents; output is last. */
    @Serializable
    data class Success(
        val order: List<String>,
    ) : TopoSortResult

    @Serializable
    data class Cycle(
        val cycleNodeIds: List<String>,
        val message: String,
    ) : TopoSortResult

    @Serializable
    data class InvalidGraph(
        val missingNodeIds: List<String>,
        val message: String,
    ) : TopoSortResult
}

@Serializable
data class NodeGraph(
    @Serializable(with = PersistentMapSerializer::class)
    val nodes: PersistentMap<String, GraphNode> = persistentMapOf(),
    /** CopyBackground & Glass binds the read-only underlyingComposite snapshot. */
    val readsUnderlyingComposite: Boolean = false,
    val outputNodeId: String = NodeSchemaIds.GROUP_OUTPUT,
) {
    init {
        nodes.forEach { (key, node) ->
            require(key == node.nodeId) { "Node map key '$key' does not match node id '${node.nodeId}'" }
        }
    }

    /**
     * Kahn's algorithm walked backwards from [outputNodeId]. Only the sub-graph that
     * actually feeds the output is sorted; disconnected nodes are ignored exactly like
     * a DAG compiler would ignore them.
     */
    fun topoSort(): TopoSortResult {
        val outputNode =
            nodes[outputNodeId]
                ?: return TopoSortResult.InvalidGraph(
                    missingNodeIds = emptyList(),
                    message = "Output node '$outputNodeId' is missing from the graph",
                )

        val reachable = LinkedHashSet<String>()
        val missing = TreeSet<String>()
        val pending = ArrayDeque<String>()
        pending.addLast(outputNode.nodeId)
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            if (!reachable.add(id)) continue
            val node = nodes[id] ?: continue
            node.inputs.values.forEach { input ->
                if (input is NodeInput.FromNode) {
                    if (nodes.containsKey(input.nodeId)) {
                        pending.addLast(input.nodeId)
                    } else {
                        missing.add(input.nodeId)
                    }
                }
            }
        }

        if (missing.isNotEmpty()) {
            return TopoSortResult.InvalidGraph(
                missingNodeIds = missing.toList(),
                message = "Graph references missing node(s): ${missing.joinToString()}",
            )
        }

        val upstream: Map<String, Set<String>> =
            reachable.associateWith { id ->
                nodes
                    .getValue(id)
                    .inputs.values
                    .filterIsInstance<NodeInput.FromNode>()
                    .map { it.nodeId }
                    .toSet()
            }
        val downstream: MutableMap<String, MutableList<String>> = mutableMapOf()
        upstream.forEach { (id, deps) ->
            deps.forEach { dep ->
                downstream.getOrPut(dep) { mutableListOf() }.add(id)
            }
        }
        val inDegree = upstream.mapValues { it.value.size }.toMutableMap()

        val ready = TreeSet<String>()
        inDegree.forEach { (id, degree) -> if (degree == 0) ready.add(id) }

        val order = ArrayList<String>(reachable.size)
        while (ready.isNotEmpty()) {
            val id = ready.pollFirst()!!
            order.add(id)
            downstream[id]?.sorted()?.forEach { dependent ->
                val remaining = inDegree.getValue(dependent) - 1
                inDegree[dependent] = remaining
                if (remaining == 0) ready.add(dependent)
            }
        }

        if (order.size != reachable.size) {
            // Kahn leaves every node in or downstream of a feedback region blocked.
            // Trim nodes with no blocked downstream so cycleNodeIds reports the loop
            // itself (a → b → a), not the output node that merely consumes it.
            val blocked = inDegree.filterValues { it > 0 }.keys.toMutableSet()
            var trimmedAny = true
            while (trimmedAny) {
                trimmedAny = false
                val iterator = blocked.iterator()
                while (iterator.hasNext()) {
                    val id = iterator.next()
                    val hasBlockedDownstream = downstream[id]?.any { it in blocked } == true
                    if (!hasBlockedDownstream) {
                        iterator.remove()
                        trimmedAny = true
                    }
                }
            }
            val cycleNodes = blocked.ifEmpty { inDegree.filterValues { it > 0 }.keys }.sorted()
            return TopoSortResult.Cycle(
                cycleNodeIds = cycleNodes,
                message = "Feedback loop detected across: ${cycleNodes.joinToString()}",
            )
        }
        return TopoSortResult.Success(order)
    }

    /** Direct upstream node ids of [nodeId], deduplicated. */
    fun upstreamOf(nodeId: String): Set<String> =
        nodes[nodeId]
            ?.inputs
            ?.values
            ?.filterIsInstance<NodeInput.FromNode>()
            ?.map { it.nodeId }
            ?.toSet()
            ?: emptySet()

    companion object {
        /** An empty graph containing just the group-input/output pair wired together. */
        fun passthrough(): NodeGraph =
            NodeGraph(
                nodes =
                    persistentMapOf(
                        NodeSchemaIds.GROUP_INPUT to GraphNode(NodeSchemaIds.GROUP_INPUT, NodeSchemaIds.GROUP_INPUT),
                        NodeSchemaIds.GROUP_OUTPUT to
                            GraphNode(
                                nodeId = NodeSchemaIds.GROUP_OUTPUT,
                                schemaId = NodeSchemaIds.GROUP_OUTPUT,
                                inputs =
                                    persistentMapOf(
                                        "clipSource" to NodeInput.FromNode(NodeSchemaIds.GROUP_INPUT),
                                    ),
                            ),
                    ),
            )
    }
}
