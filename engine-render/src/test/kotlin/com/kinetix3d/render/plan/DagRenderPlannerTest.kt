package com.kinetix3d.render.plan

import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.GraphNode
import com.kinetix3d.model.HardwareTier
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.NodeInput
import com.kinetix3d.model.NodeSchemaIds
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DagRenderPlannerTest {
    private val comp = CompSettings(width = 1920, height = 1080, fps = 60.0)
    private val comp4k = CompSettings(width = 3840, height = 2160, fps = 60.0)
    private val tier2 =
        DeviceCapabilityProfile.tier2Default().copy(
            supportsRgba16f = true,
            glesMajor = 3,
            glesMinor = 2,
            largeMemoryClassMb = 512,
        )
    private val tier3 =
        DeviceCapabilityProfile.tier2Default().copy(
            tier = HardwareTier.TIER_3,
            supportsRgba16f = true,
            glesMajor = 3,
            glesMinor = 2,
            safeVramBudgetMb = DeviceCapabilityProfile.TIER3_VRAM_BUDGET_MB,
            largeMemoryClassMb = 0,
        )
    private val planner = DagRenderPlanner()

    private fun graphWith(
        schemaId: String,
        nodeId: String = "fx",
    ): NodeGraph {
        val base = NodeGraph.passthrough()
        val input = base.nodes.getValue(NodeSchemaIds.GROUP_INPUT)
        val output = base.nodes.getValue(NodeSchemaIds.GROUP_OUTPUT)
        val node =
            GraphNode(
                nodeId = nodeId,
                schemaId = schemaId,
                inputs = persistentMapOf("clipSource" to NodeInput.FromNode(input.nodeId)),
            )
        return base.copy(
            nodes =
                base.nodes
                    .putting(nodeId, node)
                    .putting(
                        output.nodeId,
                        output.copy(inputs = persistentMapOf("clipSource" to NodeInput.FromNode(nodeId))),
                    ),
        )
    }

    @Test
    fun `passthrough graph plans one full-frame tile`() {
        val plan = planner.plan(NodeGraph.passthrough(), comp, previewScale = 0.5f, profile = tier2)
        val tile = plan.tiles.single()
        assertEquals(960f, tile.output.width)
        assertEquals(540f, tile.output.height)
        assertEquals(0, tile.apronPx)
        assertEquals(RenderFormat.RGBA16F, plan.format)
        assertTrue(!plan.reducedResolution)
    }

    @Test
    fun `local blur expands DoD and records an apron`() {
        val plan = planner.plan(graphWith("kinetix.blur"), comp, previewScale = 0.5f, profile = tier2)
        val blur = plan.passes.first { it.schemaId == "kinetix.blur" }
        assertTrue(blur.outputDoD.width > 960f)
        assertTrue(plan.tiles.single().apronPx > 0)
        assertTrue(
            plan.tiles
                .single()
                .requiredRoI.width >
                plan.tiles
                    .single()
                    .output.width,
        )
    }

    @Test
    fun `coordinate remap and global barrier force a full-frame tile`() {
        val remap = planner.plan(graphWith("kinetix.tiles"), comp, previewScale = 0.5f, profile = tier2)
        assertTrue(remap.passes.first { it.schemaId == "kinetix.tiles" }.isBarrier)
        assertEquals(1, remap.tiles.size)

        val optics = planner.plan(graphWith("kinetix.optics"), comp, previewScale = 0.5f, profile = tier2)
        assertTrue(optics.passes.first { it.schemaId == "kinetix.optics" }.isBarrier)
        assertEquals(1, optics.tiles.size)
    }

    @Test
    fun `4K tier3 blur falls back to reduced resolution`() {
        val plan = planner.plan(graphWith("kinetix.blur"), comp4k, previewScale = 1f, profile = tier3, export = true)
        assertTrue(plan.reducedResolution)
        assertEquals(1, plan.tiles.size)
    }

    @Test
    fun `4K tier3 preview splits into tiles`() {
        val plan = planner.plan(NodeGraph.passthrough(), comp4k, previewScale = 1f, profile = tier3)
        assertTrue(plan.tiles.size > 1)
        assertTrue(plan.tiles.all { it.output.width <= 512f && it.output.height <= 512f })
    }

    @Test
    fun `cycle graph is rejected`() {
        val base = NodeGraph.passthrough()
        val a = GraphNode("a", "kinetix.blur", inputs = persistentMapOf("clipSource" to NodeInput.FromNode("b")))
        val b = GraphNode("b", "kinetix.blur", inputs = persistentMapOf("clipSource" to NodeInput.FromNode("a")))
        val output =
            base.nodes.getValue(NodeSchemaIds.GROUP_OUTPUT).copy(
                inputs = persistentMapOf("clipSource" to NodeInput.FromNode("a")),
            )
        val graph =
            base.copy(
                nodes =
                    base.nodes
                        .putting("a", a)
                        .putting("b", b)
                        .putting(output.nodeId, output),
            )
        assertFailsWith<RenderPlanException> { planner.plan(graph, comp, 0.5f, tier2) }
    }

    @Test
    fun `budget selects format and apron from the profile`() {
        val rgba16 = FboBudget.forProfile(tier2)
        assertEquals(RenderFormat.RGBA16F, rgba16.format)
        assertTrue(rgba16.maxApronPx(export = false) > 0)

        val rgba8 = FboBudget.forProfile(tier2.copy(supportsRgba16f = false, glesMajor = 2, glesMinor = 0))
        assertEquals(RenderFormat.RGBA8, rgba8.format)

        val r11 = FboBudget.forProfile(tier2.copy(supportsRgba16f = false, glesMajor = 3, glesMinor = 0))
        assertEquals(RenderFormat.R11F_G11F_B10F, r11.format)
    }
}
