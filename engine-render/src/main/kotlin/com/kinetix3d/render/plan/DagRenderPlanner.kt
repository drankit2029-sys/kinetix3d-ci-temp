/*
 * Kinetix3D Studio — engine-render
 * Pure-Kotlin DAG/DoD planner. It never touches EGL/GLES: the GL executor and
 * test kernels consume this plan on the RenderThread.
 */

package com.kinetix3d.render.plan

import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.KinetixOperatorCatalog
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.NodeInput
import com.kinetix3d.model.TilingCapability
import com.kinetix3d.model.TopoSortResult
import kotlin.math.max
import kotlin.math.roundToInt

class DagRenderPlanner(
    private val catalog: KinetixOperatorCatalog = KinetixOperatorCatalog,
) {
    fun plan(
        graph: NodeGraph,
        comp: CompSettings,
        previewScale: Float,
        profile: DeviceCapabilityProfile,
        export: Boolean = false,
    ): RenderPlan {
        require(previewScale > 0f) { "previewScale must be positive" }
        val budget = FboBudget.forProfile(profile)
        val width = if (export) comp.width else max(1, (comp.width * previewScale).roundToInt())
        val height = if (export) comp.height else max(1, (comp.height * previewScale).roundToInt())
        val viewport = DoDRect.full(width, height)
        val sort = graph.topoSort()
        if (sort !is TopoSortResult.Success) {
            val message =
                when (sort) {
                    is TopoSortResult.Cycle -> sort.message
                    is TopoSortResult.InvalidGraph -> sort.message
                    is TopoSortResult.Success -> error("unreachable")
                }
            throw RenderPlanException(message)
        }

        val outputDoDByNode = mutableMapOf<String, DoDRect>()
        val passes = ArrayList<RenderPassPlan>(sort.order.size)
        for (nodeId in sort.order) {
            val node = graph.nodes[nodeId] ?: throw RenderPlanException("Node '$nodeId' is missing")
            val schema = catalog.requireById(node.schemaId)
            val tiling = schema.tilingFor(node.modeId)
            val upstream =
                node.inputs.values
                    .filterIsInstance<NodeInput.FromNode>()
                    .mapNotNull { outputDoDByNode[it.nodeId] }
            val upstreamDoD = upstream.reduceOrNull { a, b -> a.union(b) } ?: viewport
            val (outDoD, requiredRoI, barrier) =
                when (tiling) {
                    TilingCapability.PointExact -> {
                        Triple(upstreamDoD, upstreamDoD, false)
                    }

                    is TilingCapability.LocalNeighborhood -> {
                        val actualRadius =
                            node.params["radius"]
                                ?.value
                                ?.let { minOf(it, tiling.radius) }
                                ?: tiling.radius
                        val radiusPx = actualRadius * height
                        val expanded = upstreamDoD.expand(radiusPx)
                        Triple(expanded, expanded, false)
                    }

                    TilingCapability.CoordinateRemap -> {
                        Triple(upstreamDoD, upstreamDoD, true)
                    }

                    TilingCapability.GlobalSpatialBarrier -> {
                        Triple(upstreamDoD, upstreamDoD, true)
                    }
                }
            outputDoDByNode[nodeId] = outDoD
            passes +=
                RenderPassPlan(
                    nodeId = nodeId,
                    schemaId = node.schemaId,
                    modeId = node.modeId,
                    tiling = tiling,
                    outputDoD = outDoD,
                    requiredRoI = requiredRoI,
                    isBarrier = barrier,
                    usesUnderlyingComposite = schema.readsUnderlyingComposite,
                )
        }

        val finalPass =
            passes.lastOrNull { it.nodeId == graph.outputNodeId }
                ?: passes.lastOrNull()
                ?: throw RenderPlanException("Graph '${graph.outputNodeId}' has no executable nodes")
        val apronPx =
            max(
                0,
                ((finalPass.requiredRoI.width - viewport.width) / 2f).roundToInt(),
            )
        val barrier = passes.any { it.isBarrier } || apronPx > budget.maxApronPx(export)
        val tiles = planTiles(viewport, finalPass.requiredRoI, barrier, apronPx, budget, export)
        val budgetBytes = budget.budgetBytes(export)
        val reducedResolution =
            tiles.any { tile ->
                tile.requiredRoI.width * tile.requiredRoI.height * budget.format.bytesPerPixel > budgetBytes
            }
        return RenderPlan(
            order = sort.order,
            passes = passes,
            outputDoD = finalPass.outputDoD,
            tiles = tiles,
            format = budget.format,
            budgetBytes = budgetBytes,
            safeSinglePassBytes = budget.safeSinglePassBytes(export),
            maxApronPx = budget.maxApronPx(export),
            reducedResolution = reducedResolution,
        )
    }

    private fun planTiles(
        viewport: DoDRect,
        finalDoD: DoDRect,
        barrier: Boolean,
        apronPx: Int,
        budget: FboBudget,
        export: Boolean,
    ): List<TilePlan> {
        if (barrier) {
            return listOf(TilePlan(output = viewport, requiredRoI = finalDoD, apronPx = apronPx))
        }
        val outputBytes = viewport.width * viewport.height * budget.format.bytesPerPixel
        if (outputBytes <= budget.safeSinglePassBytes(export)) {
            val roi = viewport.expand(apronPx.toFloat()).intersect(finalDoD) ?: viewport
            return listOf(TilePlan(output = viewport, requiredRoI = roi, apronPx = apronPx))
        }
        val tiles = mutableListOf<TilePlan>()
        var y = viewport.top
        while (y < viewport.bottom) {
            var x = viewport.left
            while (x < viewport.right) {
                val tile =
                    DoDRect(
                        left = x,
                        top = y,
                        right = minOf(x + budget.tileSize, viewport.right),
                        bottom = minOf(y + budget.tileSize, viewport.bottom),
                    )
                val roi = tile.expand(apronPx.toFloat()).intersect(finalDoD) ?: tile
                tiles += TilePlan(output = tile, requiredRoI = roi, apronPx = apronPx)
                x += budget.tileSize
            }
            y += budget.tileSize
        }
        return tiles
    }
}
