/*
 * Kinetix3D Studio — core-evaluator
 * Cubic spline arc-length LUT with Gauss-Legendre quadrature and Bishop frames.
 */

package com.kinetix3d.evaluator

import com.kinetix3d.model.Quaternion
import com.kinetix3d.model.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One vertex of a cubic Bezier spline; tangents are offsets relative to [position]. */
data class BezierVertex(
    val position: Vec3,
    val inTangent: Vec3 = Vec3.ZERO,
    val outTangent: Vec3 = Vec3.ZERO,
    val featherRadius: Float = 0f,
)

/** How normalized path progress is mapped outside the `[0, 1]` range. */
enum class LoopMode { ONCE, LOOP, PING_PONG, HOLD }

/** A sampled path point with a unit tangent and a Bishop-frame unit normal. */
data class PathSample(
    val position: Vec3,
    val tangent: Vec3,
    val normal: Vec3,
) {
    companion object {
        val EMPTY = PathSample(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)
    }
}

/**
 * Arc-length parameterization of a cubic Bezier poly-spline.
 *
 * One cubic segment is created for each adjacent vertex pair (`closed = true` adds the
 * wrap segment). Segment lengths are integrated with 8-point Gauss-Legendre quadrature
 * and accumulated into a 512-entry, non-decreasing [cumLength] table.
 */
class SplineArcLengthTable(
    vertices: List<BezierVertex>,
    private val closed: Boolean = false,
) {
    private data class Segment(
        val p0: Vec3,
        val c1: Vec3,
        val c2: Vec3,
        val p1: Vec3,
    )

    private val segments: List<Segment> = buildSegments(vertices)

    /** Non-decreasing cumulative arc length, one entry per parameter sample. */
    val cumLength: FloatArray = FloatArray(TABLE_SIZE)

    /** Total poly-spline length (0 for degenerate input). */
    var totalLength: Float = 0f
        private set

    private val frameTangents: Array<Vec3> = Array(TABLE_SIZE) { Vec3.ZERO }
    private val frameNormals: Array<Vec3> = Array(TABLE_SIZE) { Vec3.ZERO }

    init {
        buildLengthTable()
        buildFrames()
    }

    /**
     * Samples the path at [progress] of its total length.
     *
     * [LoopMode.ONCE] and [LoopMode.HOLD] clamp to the ends, [LoopMode.LOOP] wraps and
     * [LoopMode.PING_PONG] reflects at both ends.
     */
    fun sampleAtNormalizedDistance(
        progress: Float,
        loopMode: LoopMode = LoopMode.ONCE,
    ): PathSample {
        if (segments.isEmpty() || !totalLength.isFinite() || totalLength <= MIN_LENGTH) {
            return PathSample.EMPTY
        }
        val mapped = mapLoopProgress(progress, loopMode)
        val distance = mapped * totalLength
        val t = parameterAtDistance(distance)
        val tangent = tangentAt(t)
        if (tangent.length() <= MIN_LENGTH) return PathSample(positionAt(t), Vec3.ZERO, Vec3.ZERO)
        val normal = normalAt(t, tangent)
        return PathSample(positionAt(t), tangent, normal)
    }

    private fun buildSegments(vertices: List<BezierVertex>): List<Segment> {
        if (vertices.size < 2) return emptyList()
        val result = ArrayList<Segment>(vertices.size)
        for (index in 0 until vertices.size - 1) {
            result += segmentOf(vertices[index], vertices[index + 1])
        }
        if (closed) result += segmentOf(vertices.last(), vertices.first())
        return result
    }

    private fun segmentOf(
        start: BezierVertex,
        end: BezierVertex,
    ): Segment =
        Segment(
            p0 = start.position,
            c1 = start.position + start.outTangent,
            c2 = end.position + end.inTangent,
            p1 = end.position,
        )

    private fun buildLengthTable() {
        if (segments.isEmpty()) return
        val prefix = FloatArray(segments.size + 1)
        for (index in segments.indices) {
            prefix[index + 1] = prefix[index] + integrateSpeed(segments[index], 0f, 1f)
        }
        val total = prefix.last()
        if (!total.isFinite() || total <= MIN_LENGTH) return
        totalLength = total

        var previous = 0f
        for (index in 0 until TABLE_SIZE) {
            val t = index.toFloat() / (TABLE_SIZE - 1)
            val segmentIndex = segmentIndexAt(t)
            val u = localParameter(t, segmentIndex)
            val length = prefix[segmentIndex] + integrateSpeed(segments[segmentIndex], 0f, u)
            val monotonic = if (length.isFinite()) maxOf(length, previous) else previous
            cumLength[index] = monotonic.coerceAtMost(total)
            previous = cumLength[index]
        }
        cumLength[0] = 0f
        cumLength[TABLE_SIZE - 1] = total
    }

    private fun buildFrames() {
        if (segments.isEmpty() || totalLength <= MIN_LENGTH) return
        var previousTangent = Vec3.ZERO
        var previousNormal = Vec3.ZERO
        for (index in 0 until TABLE_SIZE) {
            val t = index.toFloat() / (TABLE_SIZE - 1)
            val tangent = tangentAt(t)
            if (tangent.length() <= MIN_LENGTH) {
                frameTangents[index] = previousTangent
                frameNormals[index] = previousNormal
                continue
            }
            val normal =
                if (previousNormal.length() <= MIN_LENGTH) {
                    tangent.anyPerpendicular()
                } else {
                    transport(previousTangent, previousNormal, tangent)
                }
            frameTangents[index] = tangent
            frameNormals[index] = normal
            previousTangent = tangent
            previousNormal = normal
        }
    }

    private fun parameterAtDistance(distance: Float): Float {
        val target = distance.coerceIn(0f, totalLength)
        var low = 0
        var high = TABLE_SIZE - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumLength[mid] < target) low = mid + 1 else high = mid
        }
        val upper = low
        val lower = (upper - 1).coerceAtLeast(0)
        val span = cumLength[upper] - cumLength[lower]
        val fraction =
            if (span > 1e-9f) {
                ((target - cumLength[lower]) / span).coerceIn(0f, 1f)
            } else {
                0f
            }
        return ((lower + fraction) / (TABLE_SIZE - 1)).coerceIn(0f, 1f)
    }

    private fun positionAt(t: Float): Vec3 {
        val segmentIndex = segmentIndexAt(t)
        val segment = segments[segmentIndex]
        val u = localParameter(t, segmentIndex)
        return cubicPosition(segment, u)
    }

    private fun tangentAt(t: Float): Vec3 {
        val segmentIndex = segmentIndexAt(t)
        val segment = segments[segmentIndex]
        val u = localParameter(t, segmentIndex)
        val derivative = cubicDerivative(segment, u)
        if (derivative.length() > MIN_LENGTH) return derivative.normalized()

        // Degenerate control point: the first derivative vanishes. L'Hôpital says
        // the tangent direction is the limit of B'(u)/|B'(u)|, so inspect the
        // collapsed handle instead of snapping to the chord (which would twist the
        // frame abruptly just after the endpoint).
        if (u <= 0.5f) {
            val collapseDirection = segment.c2 - segment.c1
            if (collapseDirection.length() > MIN_LENGTH) return collapseDirection.normalized()
            val leadingDirection = segment.c1 - segment.p0
            if (leadingDirection.length() > MIN_LENGTH) return leadingDirection.normalized()
        } else {
            val collapseDirection = segment.p1 - segment.c2
            if (collapseDirection.length() > MIN_LENGTH) return collapseDirection.normalized()
            val innerDirection = segment.c2 - segment.c1
            if (innerDirection.length() > MIN_LENGTH) return innerDirection.normalized()
            val leadingDirection = segment.c1 - segment.p0
            if (leadingDirection.length() > MIN_LENGTH) return leadingDirection.normalized()
        }

        val chord = segment.p1 - segment.p0
        if (chord.length() > MIN_LENGTH) return chord.normalized()
        return Vec3.ZERO
    }

    private fun normalAt(
        t: Float,
        tangent: Vec3,
    ): Vec3 {
        val framePosition = (t * (TABLE_SIZE - 1)).roundToInt().coerceIn(0, TABLE_SIZE - 1)
        val frameTangent = frameTangents[framePosition]
        val frameNormal = frameNormals[framePosition]
        val transported =
            if (frameNormal.length() <= MIN_LENGTH) {
                tangent.anyPerpendicular()
            } else {
                transport(frameTangent, frameNormal, tangent)
            }
        return if (transported.length() <= MIN_LENGTH) tangent.anyPerpendicular() else transported
    }

    private fun transport(
        previousTangent: Vec3,
        previousNormal: Vec3,
        tangent: Vec3,
    ): Vec3 {
        val from = previousTangent.normalized()
        val to = tangent.normalized()
        if (from.length() <= MIN_LENGTH || to.length() <= MIN_LENGTH) return Vec3.ZERO
        val cosine = from.dot(to).coerceIn(-1f, 1f)
        val rotated =
            when {
                cosine > 0.999999f -> {
                    previousNormal
                }

                cosine < -0.999999f -> {
                    val axis = from.anyPerpendicular()
                    Quaternion.fromAxisAngle(axis, PI.toFloat()).rotate(previousNormal)
                }

                else -> {
                    val axis = from.cross(to)
                    if (axis.length() <= MIN_LENGTH) {
                        previousNormal
                    } else {
                        Quaternion.fromAxisAngle(axis, acos(cosine)).rotate(previousNormal)
                    }
                }
            }
        val projected = rotated - to * rotated.dot(to)
        return if (projected.length() <= MIN_LENGTH) to.anyPerpendicular() else projected.normalized()
    }

    private fun segmentIndexAt(t: Float): Int =
        (t.coerceIn(0f, 1f) * segments.size).toInt().coerceIn(0, segments.size - 1)

    private fun localParameter(
        t: Float,
        segmentIndex: Int,
    ): Float = (t.coerceIn(0f, 1f) * segments.size - segmentIndex).coerceIn(0f, 1f)

    private fun integrateSpeed(
        segment: Segment,
        from: Float,
        to: Float,
    ): Float {
        if (to <= from) return 0f
        val half = (to - from) * 0.5f
        val middle = (to + from) * 0.5f
        var sum = 0f
        for (index in 0 until GAUSS_POINT_COUNT) {
            val u = middle + half * GAUSS_NODES[index]
            sum += GAUSS_WEIGHTS[index] * cubicDerivative(segment, u).length()
        }
        return sum * half
    }

    private fun cubicPosition(
        segment: Segment,
        u: Float,
    ): Vec3 {
        val oneMinusU = 1f - u
        val b0 = oneMinusU * oneMinusU * oneMinusU
        val b1 = 3f * oneMinusU * oneMinusU * u
        val b2 = 3f * oneMinusU * u * u
        val b3 = u * u * u
        return Vec3(
            b0 * segment.p0.x + b1 * segment.c1.x + b2 * segment.c2.x + b3 * segment.p1.x,
            b0 * segment.p0.y + b1 * segment.c1.y + b2 * segment.c2.y + b3 * segment.p1.y,
            b0 * segment.p0.z + b1 * segment.c1.z + b2 * segment.c2.z + b3 * segment.p1.z,
        )
    }

    private fun cubicDerivative(
        segment: Segment,
        u: Float,
    ): Vec3 {
        val oneMinusU = 1f - u
        val d0 = 3f * oneMinusU * oneMinusU
        val d1 = 6f * oneMinusU * u
        val d2 = 3f * u * u
        return Vec3(
            d0 * (segment.c1.x - segment.p0.x) +
                d1 * (segment.c2.x - segment.c1.x) +
                d2 * (segment.p1.x - segment.c2.x),
            d0 * (segment.c1.y - segment.p0.y) +
                d1 * (segment.c2.y - segment.c1.y) +
                d2 * (segment.p1.y - segment.c2.y),
            d0 * (segment.c1.z - segment.p0.z) +
                d1 * (segment.c2.z - segment.c1.z) +
                d2 * (segment.p1.z - segment.c2.z),
        )
    }

    private fun mapLoopProgress(
        progress: Float,
        loopMode: LoopMode,
    ): Float {
        if (!progress.isFinite()) return 0f
        return when (loopMode) {
            LoopMode.ONCE, LoopMode.HOLD -> {
                progress.coerceIn(0f, 1f)
            }

            LoopMode.LOOP -> {
                progress - floor(progress)
            }

            LoopMode.PING_PONG -> {
                val period = progress - floor(progress * 0.5f) * 2f
                if (period <= 1f) period else 2f - period
            }
        }
    }

    companion object {
        /** Fixed LUT resolution required by the evaluator contract. */
        const val TABLE_SIZE: Int = 512

        private const val MIN_LENGTH = 1e-6f
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
    }
}
