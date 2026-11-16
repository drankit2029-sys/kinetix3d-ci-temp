/*
 * Kinetix3D Studio — core-evaluator
 * Integrates a cubic-bezier velocity curve into a timeline/source progress remap.
 */

package com.kinetix3d.evaluator

import com.kinetix3d.model.Easing
import com.kinetix3d.model.KeyframeCurve
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Maps timeline progress to source progress through a cubic-bezier velocity curve
 * (`x` = normalized timeline time, `y` = velocity multiplier).
 *
 * The general curve uses the exact integral `I(u) = ∫ B_y(v) · B′_x(v) dv`, stored in a
 * monotonic LUT and normalized so `I(1) = 1`. Two authoring-intent shortcuts are applied:
 * a curve lying on the diagonal is a no-op (linear) remap, and equal y handles describe a
 * uniform velocity multiplier. Both shortcuts make constant-speed curves map as
 * `progress → progress`; the velocity scale itself is exposed by [createDurationScale].
 */
class SpeedRampIntegrator(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    samples: Int = DEFAULT_SAMPLES,
) {
    private val controlX1 = unitControl(x1)
    private val controlX2 = unitControl(x2)
    private val controlY1 = finiteControl(y1)
    private val controlY2 = finiteControl(y2)
    private val sampleCount = samples.coerceAtLeast(2)
    private val integral = FloatArray(sampleCount)

    private val diagonalCurve =
        abs(controlX1 - controlY1) <= EPSILON &&
            abs(controlX2 - controlY2) <= EPSILON
    private val uniformVelocity = if (abs(controlY1 - controlY2) <= EPSILON) controlY1 else null
    private val linearMapping =
        diagonalCurve ||
            (uniformVelocity != null && uniformVelocity > MIN_DURATION_SCALE)
    private var rawIntegral = 1f

    init {
        buildIntegral()
    }

    /** Source fraction in `[0, 1]` reached at the given normalized timeline [progress]. */
    fun mapProgressToSource(progress: Float): Float {
        if (!progress.isFinite()) return 0f
        val clamped = progress.coerceIn(0f, 1f)
        if (linearMapping) return clamped
        val u = CubicBezierSolver.solveParameter(controlX1, controlX2, clamped)
        return integralAt(u)
    }

    /** Normalized timeline progress at which the given source fraction is consumed. */
    fun mapSourceToProgress(source: Float): Float {
        if (!source.isFinite()) return 0f
        val clamped = source.coerceIn(0f, 1f)
        if (linearMapping) return clamped
        if (clamped <= 0f) return 0f
        if (clamped >= 1f) return 1f

        var low = 0
        var high = sampleCount - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (integral[mid] < clamped) low = mid + 1 else high = mid
        }
        val upper = low
        val lower = (upper - 1).coerceAtLeast(0)
        val span = integral[upper] - integral[lower]
        val fraction =
            if (span > EPSILON) {
                ((clamped - integral[lower]) / span).coerceIn(0f, 1f)
            } else {
                0f
            }
        val u = (lower + fraction) / (sampleCount - 1)
        return bezierX(u).coerceIn(0f, 1f)
    }

    /** Source timestamp corresponding to timeline [progress] on a clip of [sourceDurationUs]. */
    fun sourceTimeUs(
        progress: Float,
        sourceDurationUs: Long,
    ): Long {
        if (sourceDurationUs <= 0L) return 0L
        val fraction = mapProgressToSource(progress)
        return (fraction.toDouble() * sourceDurationUs.toDouble())
            .roundToLong()
            .coerceIn(0L, sourceDurationUs)
    }

    /**
     * Un-normalized total velocity integral. `1.0` is real time, `2.0` is a 2x ramp
     * (the clip's timeline duration is half of its source duration).
     */
    fun createDurationScale(): Float = if (rawIntegral.isFinite() && rawIntegral > 0f) rawIntegral else 1f

    private fun buildIntegral() {
        if (linearMapping) {
            fillLinear()
            rawIntegral = if (diagonalCurve) 1f else uniformVelocity ?: 1f
            return
        }

        var cumulative = 0f
        integral[0] = 0f
        for (index in 1 until sampleCount) {
            val from = (index - 1).toFloat() / (sampleCount - 1)
            val to = index.toFloat() / (sampleCount - 1)
            cumulative += integrateVelocity(from, to)
            integral[index] = maxOf(cumulative, integral[index - 1])
        }
        val total = integral[sampleCount - 1]
        if (!total.isFinite() || total <= MIN_DURATION_SCALE) {
            fillLinear()
            rawIntegral = 1f
            return
        }
        for (index in 0 until sampleCount) {
            integral[index] = (integral[index] / total).coerceIn(0f, 1f)
        }
        integral[sampleCount - 1] = 1f
        rawIntegral = total
    }

    private fun fillLinear() {
        for (index in 0 until sampleCount) {
            integral[index] = index.toFloat() / (sampleCount - 1)
        }
    }

    private fun integralAt(u: Float): Float {
        val clamped = u.coerceIn(0f, 1f)
        if (clamped <= 0f) return 0f
        if (clamped >= 1f) return 1f
        val scaled = clamped * (sampleCount - 1)
        val index = scaled.toInt().coerceIn(0, sampleCount - 2)
        val fraction = scaled - index
        val from = integral[index]
        val to = integral[index + 1]
        return (from + (to - from) * fraction).coerceIn(0f, 1f)
    }

    private fun integrateVelocity(
        from: Float,
        to: Float,
    ): Float {
        if (to <= from) return 0f
        val half = (to - from) * 0.5f
        val middle = (to + from) * 0.5f
        var sum = 0f
        for (index in 0 until GAUSS_POINT_COUNT) {
            val u = middle + half * GAUSS_NODES[index]
            sum += GAUSS_WEIGHTS[index] * velocityAt(u)
        }
        return (sum * half).coerceAtLeast(0f)
    }

    private fun velocityAt(u: Float): Float = bezierY(u).coerceAtLeast(0f) * bezierDerivativeX(u).coerceAtLeast(0f)

    private fun bezierX(u: Float): Float {
        val oneMinusU = 1f - u
        return 3f * oneMinusU * oneMinusU * u * controlX1 +
            3f * oneMinusU * u * u * controlX2 +
            u * u * u
    }

    private fun bezierY(u: Float): Float {
        val oneMinusU = 1f - u
        return 3f * oneMinusU * oneMinusU * u * controlY1 +
            3f * oneMinusU * u * u * controlY2 +
            u * u * u
    }

    private fun bezierDerivativeX(u: Float): Float {
        val oneMinusU = 1f - u
        return 3f * oneMinusU * oneMinusU * controlX1 +
            6f * oneMinusU * u * (controlX2 - controlX1) +
            3f * u * u * (1f - controlX2)
    }

    private fun unitControl(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    private fun finiteControl(value: Float): Float = if (value.isFinite()) value else 0f

    companion object {
        const val DEFAULT_SAMPLES: Int = 512
        const val IDENTITY_X1: Float = 0f
        const val IDENTITY_Y1: Float = 0f
        const val IDENTITY_X2: Float = 1f
        const val IDENTITY_Y2: Float = 1f

        private const val EPSILON = 1e-6f
        private const val MIN_DURATION_SCALE = 1e-6f
        private const val GAUSS_POINT_COUNT = 8

        private val GAUSS_NODES =
            floatArrayOf(
                -0.9602898564975363f,
                -0.7966664774136267f,
                -0.5255324099163290f,
                -0.1834346424956498f,
                0.1834346424956498f,
                0.5255324099163290f,
                0.7966664774136267f,
                0.9602898564975363f,
            )

        private val GAUSS_WEIGHTS =
            floatArrayOf(
                0.1012285362903763f,
                0.2223810344533745f,
                0.3137066458778873f,
                0.3626837833783620f,
                0.3626837833783620f,
                0.3137066458778873f,
                0.2223810344533745f,
                0.1012285362903763f,
            )

        /** Uses the first [Easing.CubicBezier] segment of [curve]; identity otherwise. */
        fun fromCurve(curve: KeyframeCurve<Float>): SpeedRampIntegrator {
            val bezier =
                curve.keyframes
                    .dropLast(1)
                    .asSequence()
                    .map { it.easing }
                    .filterIsInstance<Easing.CubicBezier>()
                    .firstOrNull()
            return if (bezier == null) {
                SpeedRampIntegrator(IDENTITY_X1, IDENTITY_Y1, IDENTITY_X2, IDENTITY_Y2)
            } else {
                SpeedRampIntegrator(bezier.x1, bezier.y1, bezier.x2, bezier.y2)
            }
        }
    }
}
