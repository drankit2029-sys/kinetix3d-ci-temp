/*
 * Kinetix3D Studio — core-evaluator
 * Integrates a multi-keyframe speed curve into a timeline/source progress remap.
 */

package com.kinetix3d.evaluator

import com.kinetix3d.model.Easing
import com.kinetix3d.model.Keyframe
import com.kinetix3d.model.KeyframeCurve
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Maps normalized timeline progress to normalized source progress through a
 * `KeyframeCurve<Float>` whose keyframe values are velocity multipliers.
 *
 * Each segment evaluates the full four-point vertical cubic:
 *
 * ```
 * B_y(u) = (1-u)^3 * v_i + 3*(1-u)^2*u * c_y1 + 3*(1-u)*u^2 * c_y2 + u^3 * v_{i+1}
 * c_y1 = v_i + (v_{i+1} - v_i) * y1
 * c_y2 = v_i + (v_{i+1} - v_i) * y2
 * ```
 *
 * so the endpoints are the real keyframe velocities — never hardcoded `0`/`1`.
 * The integral is accumulated over *all* segments into a monotonic LUT and
 * normalized to `[0, 1]`; [createDurationScale] returns the average velocity
 * (how many source seconds one timeline second consumes).
 *
 * A curve whose keyframes all carry the same velocity is a uniform velocity
 * multiplier: it maps `progress → progress` and scales duration by that value.
 */
class SpeedRampIntegrator private constructor(
    private val curve: KeyframeCurve<Float>,
    samples: Int,
) {
    private val keyframes = curve.keyframes
    private val sampleCount = samples.coerceAtLeast(2)
    private val integral = DoubleArray(sampleCount)

    private val startUs = curve.startTimeUs
    private val spanUs = (curve.endTimeUs - curve.startTimeUs).coerceAtLeast(1L)
    private val spanSeconds = spanUs / 1_000_000.0

    private val constantVelocity: Float? = detectConstantVelocity()
    private val uniformMapping = constantVelocity != null
    private var rawIntegral = 1f

    init {
        buildIntegral()
    }

    /** Source fraction in `[0, 1]` reached at the given normalized timeline [progress]. */
    fun mapProgressToSource(progress: Float): Float {
        if (!progress.isFinite()) return 0f
        val clamped = progress.coerceIn(0f, 1f)
        if (uniformMapping) return clamped
        return integralAt(clamped.toDouble()).toFloat()
    }

    /** Normalized timeline progress at which the given source fraction is consumed. */
    fun mapSourceToProgress(source: Float): Float {
        if (!source.isFinite()) return 0f
        val clamped = source.coerceIn(0f, 1f)
        if (uniformMapping) return clamped
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
                ((clamped - integral[lower]) / span).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        val u = (lower + fraction) / (sampleCount - 1)
        return u.toFloat().coerceIn(0f, 1f)
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
     * Average velocity over the whole curve. `1.0` is real time, `2.0` is a 2x
     * ramp; a curve that starts at 1x and ends at 4x reports `2.5`.
     */
    fun createDurationScale(): Float = if (rawIntegral.isFinite() && rawIntegral > MIN_VELOCITY) rawIntegral else 1f

    private fun detectConstantVelocity(): Float? {
        if (keyframes.isEmpty()) return 1f
        val first = sanitizeVelocity(keyframes.first().value)
        for (keyframe in keyframes) {
            if (abs(sanitizeVelocity(keyframe.value) - first) > EPSILON) return null
        }
        return if (first > MIN_VELOCITY) first else 1f
    }

    private fun buildIntegral() {
        if (uniformMapping) {
            fillLinear()
            rawIntegral = constantVelocity ?: 1f
            return
        }

        var cumulative = 0.0
        integral[0] = 0.0
        for (index in 1 until sampleCount) {
            val from = (index - 1).toDouble() / (sampleCount - 1)
            val to = index.toDouble() / (sampleCount - 1)
            cumulative += integrateSpeed(from, to)
            integral[index] = maxOf(cumulative, integral[index - 1])
        }
        val total = integral[sampleCount - 1]
        if (!total.isFinite() || total <= EPSILON) {
            fillLinear()
            rawIntegral = 1f
            return
        }
        for (index in 0 until sampleCount) {
            integral[index] = (integral[index] / total).coerceIn(0.0, 1.0)
        }
        integral[0] = 0.0
        integral[sampleCount - 1] = 1.0
        // The LUT integrates velocity over normalized progress, so the total is
        // already the average velocity (seconds of source per timeline second).
        rawIntegral = total.toFloat()
    }

    private fun fillLinear() {
        for (index in 0 until sampleCount) {
            integral[index] = index.toDouble() / (sampleCount - 1)
        }
    }

    private fun integralAt(progress: Double): Double {
        if (progress <= 0.0) return 0.0
        if (progress >= 1.0) return 1.0
        val scaled = progress * (sampleCount - 1)
        val index = scaled.toInt().coerceIn(0, sampleCount - 2)
        val fraction = scaled - index
        val from = integral[index]
        val to = integral[index + 1]
        return (from + (to - from) * fraction).coerceIn(0.0, 1.0)
    }

    /** Gauss-Legendre integration of velocity over the normalized-progress interval. */
    private fun integrateSpeed(
        fromProgress: Double,
        toProgress: Double,
    ): Double {
        if (toProgress <= fromProgress) return 0.0
        val half = (toProgress - fromProgress) * 0.5
        val middle = (toProgress + fromProgress) * 0.5
        var sum = 0.0
        for (index in 0 until GAUSS_POINT_COUNT) {
            val progress = middle + half * GAUSS_NODES[index]
            sum += GAUSS_WEIGHTS[index] * velocityAtProgress(progress)
        }
        return (sum * half).coerceAtLeast(0.0)
    }

    private fun velocityAtProgress(progress: Double): Double {
        if (keyframes.isEmpty()) return 1.0
        if (keyframes.size == 1) return sanitizeVelocity(keyframes.first().value).toDouble()

        val offsetUs = (progress.coerceIn(0.0, 1.0) * spanUs)
        val timeUs = startUs + offsetUs

        val segment = segmentIndexAt(timeUs)
        val from = keyframes[segment]
        val to = keyframes[segment + 1]
        val segmentSpanUs = (to.timeUs - from.timeUs).coerceAtLeast(1L)
        val x = ((timeUs - from.timeUs) / segmentSpanUs).coerceIn(0.0, 1.0)
        val v0 = sanitizeVelocity(from.value)
        val v1 = sanitizeVelocity(to.value)

        return when (val easing = curve.segmentEasing(segment)) {
            Easing.Linear -> {
                v0 + (v1 - v0) * x
            }

            Easing.Hold -> {
                v0.toDouble()
            }

            is Easing.CubicBezier -> {
                val u = CubicBezierSolver.solveParameter(easing.x1, easing.x2, x.toFloat())
                val shaped = cubicEasing(easing.y1, easing.y2, u)
                (v0 + (v1 - v0) * shaped).coerceAtLeast(0f).toDouble()
            }

            is Easing.Spring -> {
                val seconds =
                    x *
                        AnalyticalSpringSolver
                            .settleSeconds(
                                SpringConfig(easing.stiffness, easing.damping),
                            ).toDouble()
                AnalyticalSpringSolver
                    .solve(
                        SpringConfig(easing.stiffness, easing.damping),
                        v0,
                        v1,
                        initialVelocity = 0f,
                        timeSeconds = seconds.toFloat(),
                    ).toDouble()
            }
        }.coerceAtLeast(0.0)
    }

    private fun segmentIndexAt(timeUs: Double): Int {
        var low = 0
        var high = keyframes.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (keyframes[mid].timeUs <= timeUs) low = mid else high = mid - 1
        }
        return low.coerceIn(0, keyframes.size - 2)
    }

    private fun cubicEasing(
        y1: Float,
        y2: Float,
        u: Float,
    ): Float {
        val oneMinusU = 1f - u
        return 3f * oneMinusU * oneMinusU * u * y1 +
            3f * oneMinusU * u * u * y2 +
            u * u * u
    }

    private fun sanitizeVelocity(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(0f) else 0f

    companion object {
        const val DEFAULT_SAMPLES: Int = 512

        private const val EPSILON = 1e-6
        private const val MIN_VELOCITY = 1e-6f
        private const val GAUSS_POINT_COUNT = 8

        private val GAUSS_NODES =
            doubleArrayOf(
                -0.9602898564975363,
                -0.7966664774136267,
                -0.5255324099163290,
                -0.1834346424956498,
                0.1834346424956498,
                0.5255324099163290,
                0.7966664774136267,
                0.9602898564975363,
            )

        private val GAUSS_WEIGHTS =
            doubleArrayOf(
                0.1012285362903763,
                0.2223810344533745,
                0.3137066458778873,
                0.3626837833783620,
                0.3626837833783620,
                0.3137066458778873,
                0.2223810344533745,
                0.1012285362903763,
            )

        /** Builds the remap for every segment of [curve]; empty curves are identity. */
        fun fromCurve(
            curve: KeyframeCurve<Float>,
            samples: Int = DEFAULT_SAMPLES,
        ): SpeedRampIntegrator = SpeedRampIntegrator(curve, samples)

        /** Uniform velocity multiplier: maps progress to itself, scales duration by [speed]. */
        fun constant(
            speed: Float,
            samples: Int = DEFAULT_SAMPLES,
        ): SpeedRampIntegrator {
            val sanitized = if (speed.isFinite()) speed else 1f
            return SpeedRampIntegrator(
                KeyframeCurve(
                    keyframes = listOf(Keyframe(0L, sanitized), Keyframe(1L, sanitized)),
                ),
                samples,
            )
        }

        /** Constant-speed identity (1x). */
        val IDENTITY: SpeedRampIntegrator get() = constant(1f)
    }
}
