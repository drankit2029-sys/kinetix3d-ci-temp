/*
 * Kinetix3D Studio — core-evaluator
 * Closed-form damped harmonic oscillator used by spring keyframes.
 */

package com.kinetix3d.evaluator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Spring parameters: `stiffness` is k (mass = 1), `damping` is c. */
data class SpringConfig(
    val stiffness: Float = 170f,
    val damping: Float = 26f,
)

/**
 * Evaluates a damped spring in O(1) at any timestamp using the closed-form solution
 * of the harmonic oscillator. It never divides by `toValue - fromValue`.
 *
 * The displacement is `x0 = fromValue - toValue`; the result is expressed as an
 * absolute value around [toValue], so equal from/to values are well defined.
 */
object AnalyticalSpringSolver {
    private const val MIN_STIFFNESS = 1e-4f
    private const val MIN_DAMPING_RATIO = 1e-4f
    private const val CRITICAL_EPSILON = 1e-4f

    fun solve(
        config: SpringConfig,
        fromValue: Float,
        toValue: Float,
        initialVelocity: Float,
        timeSeconds: Float,
    ): Float {
        val target = if (toValue.isFinite()) toValue else 0f
        val from = if (fromValue.isFinite()) fromValue else target
        val velocity = if (initialVelocity.isFinite()) initialVelocity else 0f
        val t = if (timeSeconds.isNaN()) 0f else timeSeconds.coerceAtLeast(0f)

        val stiffness =
            if (config.stiffness.isFinite()) {
                config.stiffness.coerceAtLeast(MIN_STIFFNESS)
            } else {
                MIN_STIFFNESS
            }
        val damping = if (config.damping.isFinite()) config.damping else 0f

        val x0 = from - target
        val omega = sqrt(stiffness)
        val zeta = (damping / (2f * omega)).coerceAtLeast(MIN_DAMPING_RATIO)
        val result =
            when {
                zeta < 1f - CRITICAL_EPSILON -> underdamped(x0, velocity, target, omega, zeta, t)
                zeta > 1f + CRITICAL_EPSILON -> overdamped(x0, velocity, target, omega, zeta, t)
                else -> critical(x0, velocity, target, omega, t)
            }
        return if (result.isFinite()) result else target
    }

    /**
     * Seconds for the spring envelope to decay to 0.01% of the initial displacement.
     * Keyframe easing uses this to fit a visible spring inside one segment while
     * still landing on the target keyframe value.
     */
    fun settleSeconds(config: SpringConfig): Float {
        val stiffness =
            if (config.stiffness.isFinite()) {
                config.stiffness.coerceAtLeast(
                    MIN_STIFFNESS,
                )
            } else {
                MIN_STIFFNESS
            }
        val damping = if (config.damping.isFinite()) config.damping else 26f
        val omega = sqrt(stiffness)
        val zeta = (damping / (2f * omega)).coerceAtLeast(MIN_DAMPING_RATIO)
        val decay = zeta * omega
        if (decay <= MIN_DAMPING_RATIO) return 1f
        // Near-critical springs have a slowly decaying (1 + ωt) envelope; account for
        // the coefficient amplification so 0.01% settle really means 0.01%.
        val oscillation = omega * sqrt(abs(1f - zeta * zeta))
        val amplification = 1f + decay / maxOf(oscillation, decay * 0.01f)
        val seconds = (ln(10_000.0) + ln(amplification)) / decay
        return seconds.toFloat().coerceIn(0.05f, 8f)
    }

    private fun underdamped(
        x0: Float,
        v0: Float,
        target: Float,
        omega: Float,
        zeta: Float,
        t: Float,
    ): Float {
        val decay = zeta * omega
        val dampedOmega = omega * sqrt(1f - zeta * zeta)
        val envelope = exp(-decay * t)
        val cosine = kotlin.math.cos(dampedOmega * t)
        val sine = kotlin.math.sin(dampedOmega * t)
        val coefficient = (decay * x0 + v0) / dampedOmega
        return target + envelope * (x0 * cosine + coefficient * sine)
    }

    private fun critical(
        x0: Float,
        v0: Float,
        target: Float,
        omega: Float,
        t: Float,
    ): Float {
        val envelope = exp(-omega * t)
        return target + envelope * (x0 + (omega * x0 + v0) * t)
    }

    /**
     * Overdamped form rewritten with the two decaying exponentials, so large `t`
     * cannot overflow `sinh`/`cosh` (the naive form can produce `0 * Infinity = NaN`).
     */
    private fun overdamped(
        x0: Float,
        v0: Float,
        target: Float,
        omega: Float,
        zeta: Float,
        t: Float,
    ): Float {
        val decay = zeta * omega
        val dampedOmega = omega * sqrt(zeta * zeta - 1f)
        val slow = exp(-(decay - dampedOmega) * t)
        val fast = exp(-(decay + dampedOmega) * t)
        val coefficient = (decay * x0 + v0) / dampedOmega
        return target + 0.5f * (x0 * (slow + fast) + coefficient * (slow - fast))
    }
}
