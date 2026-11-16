/*
 * Kinetix3D Studio — core-evaluator
 * CSS-style cubic-bezier easing solved with Newton-Raphson and a bisection fallback.
 */

package com.kinetix3d.evaluator

import com.kinetix3d.model.Easing
import kotlin.math.abs

/**
 * Evaluates `cubic-bezier(x1, y1, x2, y2)` timing functions.
 *
 * The x control points (and the requested progress) are clamped to `[0, 1]` so that
 * `x(u)` is monotonic and the inverse solve is well defined. The y control points are
 * intentionally **not** clamped: values outside `[0, 1]` are what produce overshoot /
 * anticipatory easing (and velocity multipliers above 1x).
 */
object CubicBezierSolver {
    private const val NEWTON_ITERATIONS = 4
    private const val BISECTION_ITERATIONS = 24
    private const val EPSILON = 1e-6f

    /**
     * Solves `x(u) = progress` and returns `y(u)` for the CSS cubic bezier with
     * control points `(0, 0)`, `(x1, y1)`, `(x2, y2)`, `(1, 1)`.
     */
    fun evaluate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        progress: Float,
    ): Float {
        val p = sanitizeProgress(progress)
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f

        val cx1 = clampUnit(x1)
        val cx2 = clampUnit(x2)
        val cy1 = sanitizeFinite(y1)
        val cy2 = sanitizeFinite(y2)
        val u = solveParameter(cx1, cx2, p)
        return cubic(cy1, cy2, u)
    }

    /** Convenience overload accepting the model's easing value object. */
    fun evaluate(
        controlPoints: Easing.CubicBezier,
        progress: Float,
    ): Float = evaluate(controlPoints.x1, controlPoints.y1, controlPoints.x2, controlPoints.y2, progress)

    /**
     * Returns the curve parameter `u` whose x coordinate equals [progress].
     * Kept public because time-remap integrators need the parameter, not the y output.
     */
    fun solveParameter(
        x1: Float,
        x2: Float,
        progress: Float,
    ): Float {
        val p = sanitizeProgress(progress)
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f

        val cx1 = clampUnit(x1)
        val cx2 = clampUnit(x2)

        var low = 0f
        var high = 1f
        var u = p

        repeat(NEWTON_ITERATIONS) {
            val x = sampleX(cx1, cx2, u)
            val dx = sampleDerivativeX(cx1, cx2, u)
            if (x < p) low = u else high = u
            if (abs(x - p) <= EPSILON) return u
            if (dx <= EPSILON || !dx.isFinite()) return bisect(cx1, cx2, p, low, high)
            val next = u - (x - p) / dx
            if (next !in low..high || !next.isFinite()) {
                return bisect(cx1, cx2, p, low, high)
            }
            u = next
        }
        return u.coerceIn(0f, 1f)
    }

    /** Evaluates the x component at curve parameter [u] (not at progress). */
    fun evaluateX(
        x1: Float,
        x2: Float,
        u: Float,
    ): Float = sampleX(clampUnit(x1), clampUnit(x2), u.coerceIn(0f, 1f))

    private fun bisect(
        x1: Float,
        x2: Float,
        target: Float,
        initialLow: Float,
        initialHigh: Float,
    ): Float {
        var low = initialLow.coerceIn(0f, 1f)
        var high = initialHigh.coerceIn(0f, 1f)
        if (high - low <= EPSILON) return (low + high) * 0.5f
        repeat(BISECTION_ITERATIONS) {
            val mid = (low + high) * 0.5f
            if (sampleX(x1, x2, mid) < target) {
                low = mid
            } else {
                high = mid
            }
        }
        return (low + high) * 0.5f
    }

    private fun sampleX(
        x1: Float,
        x2: Float,
        u: Float,
    ): Float = cubic(x1, x2, u)

    private fun sampleDerivativeX(
        x1: Float,
        x2: Float,
        u: Float,
    ): Float {
        val oneMinusU = 1f - u
        return 3f * oneMinusU * oneMinusU * x1 +
            6f * oneMinusU * u * (x2 - x1) +
            3f * u * u * (1f - x2)
    }

    private fun cubic(
        control1: Float,
        control2: Float,
        u: Float,
    ): Float {
        val oneMinusU = 1f - u
        return 3f * oneMinusU * oneMinusU * u * control1 +
            3f * oneMinusU * u * u * control2 +
            u * u * u
    }

    private fun sanitizeProgress(progress: Float): Float =
        when {
            progress.isNaN() -> 0f
            else -> progress.coerceIn(0f, 1f)
        }

    private fun clampUnit(value: Float): Float =
        when {
            value.isNaN() -> 0f
            else -> value.coerceIn(0f, 1f)
        }

    private fun sanitizeFinite(value: Float): Float = if (value.isFinite()) value else 0f
}
