/*
 * Kinetix3D Studio — core-model
 * Keyframe curves: the unit of animation shared by the timeline, the evaluator and
 * the render graph. Easing is data, never code, so curves serialize with the project.
 */

package com.kinetix3d.model

/** Per-segment interpolation between this keyframe and the next one. */
sealed interface Easing {
    data object Linear : Easing

    data class CubicBezier(
        val x1: Float = 0.25f,
        val y1: Float = 0.1f,
        val x2: Float = 0.25f,
        val y2: Float = 1f,
    ) : Easing

    /** stiffness = k (m = 1), damping = c; solved by AnalyticalSpringSolver. */
    data class Spring(
        val stiffness: Float,
        val damping: Float,
    ) : Easing
}

enum class Extrapolation { HOLD, LINEAR, LOOP, PING_PONG }

data class Keyframe<T>(
    val timeUs: Long,
    val value: T,
    val easing: Easing = Easing.Linear,
)

/**
 * Time-ordered keyframes. Values are generic so the same container drives scalar,
 * vector, color and quaternion channels.
 */
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

    companion object {
        fun <T> linear(keyframes: List<Keyframe<T>> = emptyList()): KeyframeCurve<T> =
            KeyframeCurve(keyframes, Extrapolation.LINEAR)
    }
}
