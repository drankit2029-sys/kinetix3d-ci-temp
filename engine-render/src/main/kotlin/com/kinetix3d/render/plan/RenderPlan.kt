/*
 * Kinetix3D Studio — engine-render
 * Immutable render-plan output consumed by the GL executor and the tile composer.
 */

package com.kinetix3d.render.plan

import com.kinetix3d.model.TilingCapability

data class TilePlan(
    val output: DoDRect,
    val requiredRoI: DoDRect,
    val apronPx: Int,
)

data class RenderPassPlan(
    val nodeId: String,
    val schemaId: String,
    val modeId: String?,
    val tiling: TilingCapability,
    val outputDoD: DoDRect,
    val requiredRoI: DoDRect,
    val isBarrier: Boolean,
    val usesUnderlyingComposite: Boolean,
)

data class RenderPlan(
    val order: List<String>,
    val passes: List<RenderPassPlan>,
    val outputDoD: DoDRect,
    val tiles: List<TilePlan>,
    val format: RenderFormat,
    val budgetBytes: Long,
    val safeSinglePassBytes: Long,
    val maxApronPx: Int,
    val reducedResolution: Boolean,
)

class RenderPlanException(
    message: String,
) : IllegalArgumentException(message)
