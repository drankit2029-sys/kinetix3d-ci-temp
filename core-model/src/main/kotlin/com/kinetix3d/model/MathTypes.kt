/*
 * Kinetix3D Studio — core-model
 * Pure-Kotlin math primitives shared by the project model and :core-evaluator.
 *
 * Deliberately JVM-clean: no android.* dependency may ever appear in this module.
 */

package com.kinetix3d.model

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 2-component float vector (skew, UV coordinates, …). */
data class Vec2(
    val x: Float = 0f,
    val y: Float = 0f,
) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    operator fun times(scalar: Float): Vec2 = Vec2(x * scalar, y * scalar)

    fun length(): Float = sqrt(x * x + y * y)

    fun normalized(): Vec2 {
        val len = length()
        return if (len > 1e-6f) Vec2(x / len, y / len) else ZERO
    }

    companion object {
        val ZERO = Vec2(0f, 0f)
        val ONE = Vec2(1f, 1f)
    }
}

/** 3-component float vector (position, tangent, normal, scale, …). */
data class Vec3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Float): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 =
        Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun length(): Float = sqrt(dot(this))

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else ZERO
    }

    fun lerp(
        other: Vec3,
        t: Float,
    ): Vec3 =
        Vec3(
            x + (other.x - x) * t,
            y + (other.y - y) * t,
            z + (other.z - z) * t,
        )

    /** Any unit vector perpendicular to this one; deterministic (Bishop-frame seed). */
    fun anyPerpendicular(): Vec3 {
        val n = normalized()
        val axis = if (abs(n.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
        return n.cross(axis).normalized()
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** Unit-quaternion rotation (x, y, z, w). */
data class Quaternion(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val w: Float = 1f,
) {
    fun length(): Float = sqrt(x * x + y * y + z * z + w * w)

    fun normalized(): Quaternion {
        val len = length()
        return if (len > 1e-6f) Quaternion(x / len, y / len, z / len, w / len) else IDENTITY
    }

    fun conjugate(): Quaternion = Quaternion(-x, -y, -z, w)

    operator fun times(other: Quaternion): Quaternion =
        Quaternion(
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w,
            w * other.w - x * other.x - y * other.y - z * other.z,
        )

    /** Rotates [vector] by this unit quaternion. */
    fun rotate(vector: Vec3): Vec3 {
        val q = normalized()
        val u = Vec3(q.x, q.y, q.z)
        val t = u.cross(vector) * 2f
        return vector + t * q.w + u.cross(t)
    }

    companion object {
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        fun fromAxisAngle(
            axis: Vec3,
            angleRadians: Float,
        ): Quaternion {
            val n = axis.normalized()
            val half = angleRadians * 0.5f
            val s = sin(half)
            return Quaternion(n.x * s, n.y * s, n.z * s, cos(half))
        }

        /** Shortest-path spherical interpolation; falls back to linear when nearly parallel. */
        fun slerp(
            from: Quaternion,
            to: Quaternion,
            t: Float,
        ): Quaternion {
            var target = to.normalized()
            var dot = from.x * target.x + from.y * target.y + from.z * target.z + from.w * target.w
            if (dot < 0f) {
                target = Quaternion(-target.x, -target.y, -target.z, -target.w)
                dot = -dot
            }
            if (dot > 0.9995f) {
                return Quaternion(
                    from.x + (target.x - from.x) * t,
                    from.y + (target.y - from.y) * t,
                    from.z + (target.z - from.z) * t,
                    from.w + (target.w - from.w) * t,
                ).normalized()
            }
            val theta = acos(dot.coerceIn(-1f, 1f))
            val sinTheta = sin(theta)
            val a = sin((1f - t) * theta) / sinTheta
            val b = sin(t * theta) / sinTheta
            return Quaternion(
                from.x * a + target.x * b,
                from.y * a + target.y * b,
                from.z * a + target.z * b,
                from.w * a + target.w * b,
            ).normalized()
        }
    }
}
