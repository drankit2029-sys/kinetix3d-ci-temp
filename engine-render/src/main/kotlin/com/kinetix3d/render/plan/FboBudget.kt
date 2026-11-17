/*
 * Kinetix3D Studio — engine-render
 * Pure-Kotlin FBO budget policy: format selection, preview/export headroom and
 * the tile/apron limits consumed by the DAG planner.
 */

package com.kinetix3d.render.plan

import com.kinetix3d.model.DeviceCapabilityProfile
import kotlin.math.sqrt

enum class RenderFormat(
    val bytesPerPixel: Int,
) {
    RGBA16F(8),
    R11F_G11F_B10F(4),
    RGBA8(4),
}

data class FboBudget(
    val previewBudgetBytes: Long,
    val exportBudgetBytes: Long,
    val format: RenderFormat,
    val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    init {
        require(previewBudgetBytes > 0L) { "previewBudgetBytes must be positive" }
        require(exportBudgetBytes >= previewBudgetBytes) { "export budget must cover the preview budget" }
        require(tileSize > 0) { "tileSize must be positive" }
    }

    fun budgetBytes(export: Boolean): Long = if (export) exportBudgetBytes else previewBudgetBytes

    fun safeSinglePassBytes(export: Boolean): Long = budgetBytes(export) / 2L

    fun maxApronPx(export: Boolean): Int {
        val maxSide = sqrt(safeSinglePassBytes(export).toDouble() / format.bytesPerPixel).toInt()
        return ((maxSide - tileSize) / 2).coerceAtLeast(0)
    }

    companion object {
        const val DEFAULT_TILE_SIZE = 512
        private const val MIB = 1024L * 1024L
        private const val MAX_EXPORT_BUDGET_BYTES = 512L * MIB

        fun forProfile(profile: DeviceCapabilityProfile): FboBudget {
            val preview = profile.safeVramBudgetMb.coerceAtLeast(1).toLong() * MIB
            val export =
                if (profile.largeMemoryClassMb > 0) {
                    (profile.largeMemoryClassMb.toLong() * MIB / 4L)
                        .coerceIn(preview, MAX_EXPORT_BUDGET_BYTES)
                } else {
                    (preview * 4L).coerceAtMost(MAX_EXPORT_BUDGET_BYTES)
                }
            val format =
                when {
                    profile.supportsRgba16f -> RenderFormat.RGBA16F
                    profile.glesMajor >= 3 -> RenderFormat.R11F_G11F_B10F
                    else -> RenderFormat.RGBA8
                }
            return FboBudget(
                previewBudgetBytes = preview,
                exportBudgetBytes = export,
                format = format,
            )
        }
    }
}
