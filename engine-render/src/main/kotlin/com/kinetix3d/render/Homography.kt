/*
 * Kinetix3D Studio — engine-render
 * Unit-square → quad homography used by the Corner Pin kernel.
 */

package com.kinetix3d.render

import kotlin.math.abs

object Homography {
    /**
     * Returns the inverse homography as a column-major 3×3 matrix. [corners]
     * holds the destination quad in the order TL, TR, BR, BL (x, y pairs).
     */
    fun inverseFromUnitSquare(corners: FloatArray): FloatArray? {
        if (corners.size != 8) return null
        val x0 = corners[0]
        val y0 = corners[1]
        val x1 = corners[2]
        val y1 = corners[3]
        val x2 = corners[4]
        val y2 = corners[5]
        val x3 = corners[6]
        val y3 = corners[7]
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val dy3 = y0 - y1 + y2 - y3
        val a: Float
        val b: Float
        val c: Float
        val d: Float
        val e: Float
        val f: Float
        val g: Float
        val h: Float
        if (abs(dx3) < EPSILON && abs(dy3) < EPSILON) {
            a = x1 - x0
            b = x2 - x1
            c = x0
            d = y1 - y0
            e = y2 - y1
            f = y0
            g = 0f
            h = 0f
        } else {
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) < EPSILON) return null
            g = (dx3 * dy2 - dx2 * dy3) / den
            h = (dx1 * dy3 - dx3 * dy1) / den
            a = x1 - x0 + g * x1
            b = x3 - x0 + h * x3
            c = x0
            d = y1 - y0 + g * y1
            e = y3 - y0 + h * y3
            f = y0
        }
        val det = a * (e - f * h) - b * (d - f * g) + c * (d * h - e * g)
        if (abs(det) < EPSILON) return null
        val m00 = (e - f * h) / det
        val m01 = (c * h - b) / det
        val m02 = (b * f - c * e) / det
        val m10 = (f * g - d) / det
        val m11 = (a - c * g) / det
        val m12 = (c * d - a * f) / det
        val m20 = (d * h - e * g) / det
        val m21 = (b * g - a * h) / det
        val m22 = (a * e - b * d) / det
        return floatArrayOf(
            m00,
            m10,
            m20,
            m01,
            m11,
            m21,
            m02,
            m12,
            m22,
        )
    }

    private const val EPSILON = 1e-6f
}
