/*
 * Kinetix3D Studio — core-evaluator
 * Universal keyframe evaluation across every animatable value type.
 */

package com.kinetix3d.evaluator

import com.kinetix3d.model.Color4f
import com.kinetix3d.model.Easing
import com.kinetix3d.model.Extrapolation
import com.kinetix3d.model.KeyframeCurve
import com.kinetix3d.model.Quaternion
import com.kinetix3d.model.Vec2
import com.kinetix3d.model.Vec3
import kotlin.math.roundToInt

/**
 * Type-specific interpolation used by [KeyframeEvaluator].
 *
 * [spring] receives a *normalized* incoming velocity (segment progress per second),
 * so the same spring law works for scalars, vectors, colours and quaternions.
 */
interface KeyframeValueLerp<T> {
    fun lerp(
        from: T,
        to: T,
        t: Float,
    ): T

    fun spring(
        from: T,
        to: T,
        config: SpringConfig,
        normalizedInitialVelocity: Float,
        timeSeconds: Float,
    ): T = lerp(from, to, KeyframeEvaluator.springProgress(config, normalizedInitialVelocity, timeSeconds))
}

object FloatKeyframeLerp : KeyframeValueLerp<Float> {
    override fun lerp(
        from: Float,
        to: Float,
        t: Float,
    ): Float = from + (to - from) * t

    override fun spring(
        from: Float,
        to: Float,
        config: SpringConfig,
        normalizedInitialVelocity: Float,
        timeSeconds: Float,
    ): Float =
        AnalyticalSpringSolver.solve(
            config = config,
            fromValue = from,
            toValue = to,
            initialVelocity = normalizedInitialVelocity * (to - from),
            timeSeconds = timeSeconds,
        )
}

object IntKeyframeLerp : KeyframeValueLerp<Int> {
    override fun lerp(
        from: Int,
        to: Int,
        t: Float,
    ): Int = (from + (to - from) * t).roundToInt()
}

object BoolKeyframeLerp : KeyframeValueLerp<Boolean> {
    override fun lerp(
        from: Boolean,
        to: Boolean,
        t: Float,
    ): Boolean = if (t < 0.5f) from else to
}

object Vec2KeyframeLerp : KeyframeValueLerp<Vec2> {
    override fun lerp(
        from: Vec2,
        to: Vec2,
        t: Float,
    ): Vec2 = from.lerp(to, t)
}

object Vec3KeyframeLerp : KeyframeValueLerp<Vec3> {
    override fun lerp(
        from: Vec3,
        to: Vec3,
        t: Float,
    ): Vec3 = from.lerp(to, t)
}

object QuaternionKeyframeLerp : KeyframeValueLerp<Quaternion> {
    override fun lerp(
        from: Quaternion,
        to: Quaternion,
        t: Float,
    ): Quaternion = Quaternion.slerp(from, to, t)
}

object Color4fKeyframeLerp : KeyframeValueLerp<Color4f> {
    override fun lerp(
        from: Color4f,
        to: Color4f,
        t: Float,
    ): Color4f = from.lerp(to, t)
}

/** Best-effort spline morph: vertex counts must match, otherwise a step is used. */
object SplineVerticesKeyframeLerp : KeyframeValueLerp<List<BezierVertex>> {
    override fun lerp(
        from: List<BezierVertex>,
        to: List<BezierVertex>,
        t: Float,
    ): List<BezierVertex> {
        if (from.size != to.size) return if (t < 0.5f) from else to
        return List(from.size) { index ->
            val a = from[index]
            val b = to[index]
            BezierVertex(
                position = a.position.lerp(b.position, t),
                inTangent = a.inTangent.lerp(b.inTangent, t),
                outTangent = a.outTangent.lerp(b.outTangent, t),
                featherRadius = a.featherRadius + (b.featherRadius - a.featherRadius) * t,
            )
        }
    }
}

/**
 * Evaluates a [KeyframeCurve] at an arbitrary timestamp.
 *
 * * binary-searches the active segment,
 * * honours [Extrapolation.HOLD], `LINEAR`, `LOOP` and `PING_PONG`,
 * * uses the outgoing keyframe's easing, falling back to the target keyframe's
 *   easing when the outgoing one is the default [Easing.Linear],
 * * feeds the incoming segment derivative to [AnalyticalSpringSolver] so a spring
 *   segment does not restart from zero velocity.
 */
object KeyframeEvaluator {
    private const val MAX_NORMALIZED_VELOCITY = 1_000f
    private const val BEZIER_SLOPE_EPSILON = 1e-4f

    fun <T> evaluate(
        curve: KeyframeCurve<T>,
        timeUs: Long,
        lerp: KeyframeValueLerp<T>,
    ): T {
        val keys = curve.keyframes
        require(keys.isNotEmpty()) { "Cannot evaluate an empty keyframe curve" }
        if (keys.size == 1) return keys.first().value

        val startUs = keys.first().timeUs
        val endUs = keys.last().timeUs
        if (timeUs in startUs..endUs) {
            return evaluateInRange(curve, timeUs, lerp)
        }

        return when (curve.extrapolation) {
            Extrapolation.HOLD -> if (timeUs < startUs) keys.first().value else keys.last().value
            Extrapolation.LINEAR -> extrapolateLinear(curve, timeUs, lerp)
            Extrapolation.LOOP -> evaluateInRange(curve, wrapLoop(timeUs, startUs, endUs), lerp)
            Extrapolation.PING_PONG -> evaluateInRange(curve, wrapPingPong(timeUs, startUs, endUs), lerp)
        }
    }

    fun evaluateFloat(
        curve: KeyframeCurve<Float>,
        timeUs: Long,
    ): Float = evaluate(curve, timeUs, FloatKeyframeLerp)

    fun evaluateInt(
        curve: KeyframeCurve<Int>,
        timeUs: Long,
    ): Int = evaluate(curve, timeUs, IntKeyframeLerp)

    fun evaluateBool(
        curve: KeyframeCurve<Boolean>,
        timeUs: Long,
    ): Boolean = evaluate(curve, timeUs, BoolKeyframeLerp)

    fun evaluateVec2(
        curve: KeyframeCurve<Vec2>,
        timeUs: Long,
    ): Vec2 = evaluate(curve, timeUs, Vec2KeyframeLerp)

    fun evaluateVec3(
        curve: KeyframeCurve<Vec3>,
        timeUs: Long,
    ): Vec3 = evaluate(curve, timeUs, Vec3KeyframeLerp)

    fun evaluateQuaternion(
        curve: KeyframeCurve<Quaternion>,
        timeUs: Long,
    ): Quaternion = evaluate(curve, timeUs, QuaternionKeyframeLerp)

    fun evaluateColor(
        curve: KeyframeCurve<Color4f>,
        timeUs: Long,
    ): Color4f = evaluate(curve, timeUs, Color4fKeyframeLerp)

    fun evaluateSplineVertices(
        curve: KeyframeCurve<List<BezierVertex>>,
        timeUs: Long,
    ): List<BezierVertex> = evaluate(curve, timeUs, SplineVerticesKeyframeLerp)

    /** Normalized `0 → 1` spring progress for callers that interpolate generically. */
    fun springProgress(
        config: SpringConfig,
        normalizedInitialVelocity: Float,
        timeSeconds: Float,
    ): Float =
        AnalyticalSpringSolver.solve(
            config = config,
            fromValue = 0f,
            toValue = 1f,
            initialVelocity = normalizedInitialVelocity.coerceIn(-MAX_NORMALIZED_VELOCITY, MAX_NORMALIZED_VELOCITY),
            timeSeconds = timeSeconds,
        )

    private fun <T> evaluateInRange(
        curve: KeyframeCurve<T>,
        timeUs: Long,
        lerp: KeyframeValueLerp<T>,
    ): T {
        val keys = curve.keyframes
        val endUs = keys.last().timeUs
        if (timeUs >= endUs) return keys.last().value
        if (timeUs <= keys.first().timeUs) return keys.first().value

        var low = 0
        var high = keys.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (keys[mid].timeUs <= timeUs) low = mid else high = mid - 1
        }
        val index = low.coerceAtMost(keys.size - 2)
        val from = keys[index]
        val to = keys[index + 1]
        val durationUs = (to.timeUs - from.timeUs).coerceAtLeast(1L)
        val x = ((timeUs - from.timeUs).toDouble() / durationUs).coerceIn(0.0, 1.0).toFloat()

        return when (val easing = curve.segmentEasing(index)) {
            Easing.Linear -> {
                lerp.lerp(from.value, to.value, x)
            }

            Easing.Hold -> {
                from.value
            }

            is Easing.CubicBezier -> {
                lerp.lerp(from.value, to.value, CubicBezierSolver.evaluate(easing, x))
            }

            is Easing.Spring -> {
                val config = SpringConfig(easing.stiffness, easing.damping)
                val seconds = x * AnalyticalSpringSolver.settleSeconds(config)
                lerp.spring(
                    from = from.value,
                    to = to.value,
                    config = config,
                    normalizedInitialVelocity = incomingVelocity(curve, index),
                    timeSeconds = seconds,
                )
            }
        }
    }

    private fun <T> extrapolateLinear(
        curve: KeyframeCurve<T>,
        timeUs: Long,
        lerp: KeyframeValueLerp<T>,
    ): T {
        val keys = curve.keyframes
        return if (timeUs < keys.first().timeUs) {
            val from = keys[0]
            val to = keys[1]
            val durationUs = (to.timeUs - from.timeUs).coerceAtLeast(1L)
            lerp.lerp(from.value, to.value, (timeUs - from.timeUs).toFloat() / durationUs)
        } else {
            val from = keys[keys.size - 2]
            val to = keys.last()
            val durationUs = (to.timeUs - from.timeUs).coerceAtLeast(1L)
            lerp.lerp(from.value, to.value, (timeUs - from.timeUs).toFloat() / durationUs)
        }
    }

    /**
     * Normalized derivative of the segment ending at keyframe [index], expressed in
     * segment-progress units per second. Zero for the first keyframe.
     */
    private fun <T> incomingVelocity(
        curve: KeyframeCurve<T>,
        index: Int,
    ): Float {
        if (index <= 0) return 0f
        val keys = curve.keyframes
        val previous = keys[index - 1]
        val current = keys[index]
        val seconds = (current.timeUs - previous.timeUs) / 1_000_000f
        if (seconds <= 0f) return 0f

        val slope =
            when (val easing = curve.segmentEasing(index - 1)) {
                Easing.Linear -> {
                    1f
                }

                Easing.Hold -> {
                    0f
                }

                is Easing.CubicBezier -> {
                    val before = CubicBezierSolver.evaluate(easing, 1f - BEZIER_SLOPE_EPSILON)
                    (1f - before) / BEZIER_SLOPE_EPSILON
                }

                is Easing.Spring -> {
                    0f
                }
            }
        return (slope / seconds).coerceIn(-MAX_NORMALIZED_VELOCITY, MAX_NORMALIZED_VELOCITY)
    }

    private fun wrapLoop(
        timeUs: Long,
        startUs: Long,
        endUs: Long,
    ): Long {
        val span = endUs - startUs
        if (span <= 0L) return startUs
        return startUs + Math.floorMod(timeUs - startUs, span)
    }

    private fun wrapPingPong(
        timeUs: Long,
        startUs: Long,
        endUs: Long,
    ): Long {
        val span = endUs - startUs
        if (span <= 0L) return startUs
        val period = span * 2
        val phase = Math.floorMod(timeUs - startUs, period)
        return if (phase <= span) startUs + phase else startUs + (period - phase)
    }
}
