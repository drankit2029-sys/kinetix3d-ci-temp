/*
 * Kinetix3D Studio — core-model
 * Keyframe curves: the unit of animation shared by the timeline, the evaluator and
 * the render graph. Easing is data, never code, so curves serialize with the project.
 */

package com.kinetix3d.model

import kotlinx.serialization.Serializable

/** Per-segment interpolation between this keyframe and the next one. */
@Serializable
sealed interface Easing {
    @Serializable
    data object Linear : Easing

    @Serializable
    data class CubicBezier(
        val x1: Float = 0.25f,
        val y1: Float = 0.1f,
        val x2: Float = 0.25f,
        val y2: Float = 1f,
    ) : Easing

    /** stiffness = k (m = 1), damping = c; solved by AnalyticalSpringSolver. */
    @Serializable
    data class Spring(
        val stiffness: Float,
        val damping: Float,
    ) : Easing

    /** Step/Hold: keep this keyframe's value until the next keyframe's time. */
    @Serializable
    data object Hold : Easing
}

@Serializable
enum class Extrapolation { HOLD, LINEAR, LOOP, PING_PONG }

@Serializable
data class Keyframe<T>(
    val timeUs: Long,
    val value: T,
    val easing: Easing = Easing.Linear,
)

/**
 * Time-ordered keyframes. Values are generic so the same container drives scalar,
 * vector, color and quaternion channels.
 *
 * Easing resolution: a segment normally uses the outgoing keyframe's easing. When
 * that keyframe carries the default [Easing.Linear], the incoming (target)
 * keyframe's easing is used instead — so curves authored either way (easing on
 * the source or on the target keyframe) evaluate identically.
 */
@Serializable
data class KeyframeCurve<T>(
    val keyframes: List<Keyframe<T>> = emptyList(),
    val extrapolation: Extrapolation = Extrapolation.HOLD,
) {
    init {
        for (index in 1 until keyframes.size) {
            require(keyframes[index].timeUs > keyframes[index - 1].timeUs) {
                "Keyframe times must be strictly increasing"
            }
        }
    }

    val isEmpty: Boolean get() = keyframes.isEmpty()

    val startTimeUs: Long get() = keyframes.firstOrNull()?.timeUs ?: 0L

    val endTimeUs: Long get() = keyframes.lastOrNull()?.timeUs ?: 0L

    /** Easing that shapes segment [segmentIndex] (`from` = index, `to` = index + 1). */
    fun segmentEasing(segmentIndex: Int): Easing {
        val from = keyframes[segmentIndex]
        val to = keyframes[segmentIndex + 1]
        return if (from.easing != Easing.Linear) from.easing else to.easing
    }

    companion object {
        fun <T> linear(keyframes: List<Keyframe<T>> = emptyList()): KeyframeCurve<T> =
            KeyframeCurve(keyframes, Extrapolation.LINEAR)
    }
}
