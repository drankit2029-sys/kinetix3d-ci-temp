package com.kinetix3d.model

import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NodeGraphTest {
    private fun node(
        id: String,
        schema: String,
        vararg inputs: Pair<String, String>,
    ): GraphNode =
        GraphNode(
            nodeId = id,
            schemaId = schema,
            inputs =
                persistentMapOf(
                    *inputs.map { (socket, upstream) -> socket to NodeInput.FromNode(upstream) }.toTypedArray(),
                ),
        )

    @Test
    fun `passthrough graph sorts input before output`() {
        val result = NodeGraph.passthrough().topoSort()
        assertIs<TopoSortResult.Success>(result)
        assertEquals(NodeSchemaIds.GROUP_OUTPUT, result.order.last())
        assertTrue(result.order.indexOf(NodeSchemaIds.GROUP_INPUT) < result.order.indexOf(NodeSchemaIds.GROUP_OUTPUT))
    }

    @Test
    fun `linear chain sorts every dependency before its dependents`() {
        val graph =
            NodeGraph(
                nodes =
                    persistentMapOf(
                        NodeSchemaIds.GROUP_INPUT to node(NodeSchemaIds.GROUP_INPUT, NodeSchemaIds.GROUP_INPUT),
                        "blur" to node("blur", "kinetix.blur", "clipSource" to NodeSchemaIds.GROUP_INPUT),
                        "glow" to node("glow", "kinetix.glow", "clipSource" to "blur"),
                        NodeSchemaIds.GROUP_OUTPUT to
                            node(
                                NodeSchemaIds.GROUP_OUTPUT,
                                NodeSchemaIds.GROUP_OUTPUT,
                                "clipSource" to "glow",
                            ),
                    ),
            )

        val result = graph.topoSort()
        assertIs<TopoSortResult.Success>(result)
        assertEquals(4, result.order.size)
        val positions = result.order.withIndex().associate { it.value to it.index }
        assertTrue(positions.getValue(NodeSchemaIds.GROUP_INPUT) < positions.getValue("blur"))
        assertTrue(positions.getValue("blur") < positions.getValue("glow"))
        assertTrue(positions.getValue("glow") < positions.getValue(NodeSchemaIds.GROUP_OUTPUT))
        assertEquals(NodeSchemaIds.GROUP_OUTPUT, result.order.last())
    }

    @Test
    fun `feedback loop is reported with the nodes in the cycle`() {
        val graph =
            NodeGraph(
                nodes =
                    persistentMapOf(
                        "a" to node("a", "kinetix.blur", "clipSource" to "b"),
                        "b" to node("b", "kinetix.glow", "clipSource" to "a"),
                        NodeSchemaIds.GROUP_OUTPUT to
                            node(
                                NodeSchemaIds.GROUP_OUTPUT,
                                NodeSchemaIds.GROUP_OUTPUT,
                                "clipSource" to "a",
                            ),
                    ),
            )

        val result = graph.topoSort()
        assertIs<TopoSortResult.Cycle>(result)
        assertEquals(listOf("a", "b"), result.cycleNodeIds)
        assertTrue(result.message.contains("Feedback loop"))
    }

    @Test
    fun `missing upstream node is rejected instead of silently dropped`() {
        val graph =
            NodeGraph(
                nodes =
                    persistentMapOf(
                        NodeSchemaIds.GROUP_OUTPUT to
                            node(
                                NodeSchemaIds.GROUP_OUTPUT,
                                NodeSchemaIds.GROUP_OUTPUT,
                                "clipSource" to "ghost_node",
                            ),
                    ),
            )

        val result = graph.topoSort()
        assertIs<TopoSortResult.InvalidGraph>(result)
        assertEquals(listOf("ghost_node"), result.missingNodeIds)
    }

    @Test
    fun `disconnected cycle does not fail a compilable graph`() {
        val graph =
            NodeGraph(
                nodes =
                    persistentMapOf(
                        NodeSchemaIds.GROUP_INPUT to node(NodeSchemaIds.GROUP_INPUT, NodeSchemaIds.GROUP_INPUT),
                        NodeSchemaIds.GROUP_OUTPUT to
                            node(
                                NodeSchemaIds.GROUP_OUTPUT,
                                NodeSchemaIds.GROUP_OUTPUT,
                                "clipSource" to NodeSchemaIds.GROUP_INPUT,
                            ),
                        "orphan_a" to node("orphan_a", "kinetix.blur", "clipSource" to "orphan_b"),
                        "orphan_b" to node("orphan_b", "kinetix.glow", "clipSource" to "orphan_a"),
                    ),
            )

        val result = graph.topoSort()
        assertIs<TopoSortResult.Success>(result)
        assertEquals(2, result.order.size)
    }

    @Test
    fun `missing output node is an invalid graph`() {
        val result = NodeGraph().topoSort()
        assertIs<TopoSortResult.InvalidGraph>(result)
        assertTrue(result.message.contains("Output node"))
    }
}
