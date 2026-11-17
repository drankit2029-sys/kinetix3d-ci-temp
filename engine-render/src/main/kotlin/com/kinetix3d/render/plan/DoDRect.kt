/*
 * Kinetix3D Studio — engine-render
 * Pure-Kotlin Domain-of-Definition rectangle used by the DAG/tile planner.
 */

package com.kinetix3d.render.plan

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle in render pixels; `right`/`bottom` are exclusive. */
data class DoDRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right >= left) { "DoDRect right must be >= left" }
        require(bottom >= top) { "DoDRect bottom must be >= top" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun union(other: DoDRect): DoDRect =
        DoDRect(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom),
        )

    fun intersect(other: DoDRect): DoDRect? {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (r <= l || b <= t) null else DoDRect(l, t, r, b)
    }

    fun expand(px: Float): DoDRect = DoDRect(left - px, top - px, right + px, bottom + px)

    fun roundOut(): DoDRect = DoDRect(floor(left), floor(top), ceil(right), ceil(bottom))

    companion object {
        fun full(
            width: Int,
            height: Int,
        ): DoDRect {
            require(width > 0 && height > 0) { "DoD dimensions must be positive" }
            return DoDRect(0f, 0f, width.toFloat(), height.toFloat())
        }
    }
}
