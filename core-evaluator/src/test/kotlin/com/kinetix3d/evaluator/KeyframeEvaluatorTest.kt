package com.kinetix3d.evaluator

import com.kinetix3d.model.Color4f
import com.kinetix3d.model.Easing
import com.kinetix3d.model.Extrapolation
import com.kinetix3d.model.Keyframe
import com.kinetix3d.model.KeyframeCurve
import com.kinetix3d.model.Quaternion
import com.kinetix3d.model.Vec2
import com.kinetix3d.model.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyframeEvaluatorTest {
    private fun floatCurve(
        extrapolation: Extrapolation = Extrapolation.HOLD,
        vararg keys: Keyframe<Float>,
    ): KeyframeCurve<Float> = KeyframeCurve(keys.toList(), extrapolation)

    @Test
    fun `linear segments interpolate exactly between keyframes`() {
        val curve = floatCurve(keys = arrayOf(Keyframe(0L, 0f), Keyframe(1_000_000L, 10f)))

        assertEquals(0f, KeyframeEvaluator.evaluateFloat(curve, 0L), 0f)
        assertEquals(2.5f, KeyframeEvaluator.evaluateFloat(curve, 250_000L), 1e-5f)
        assertEquals(5f, KeyframeEvaluator.evaluateFloat(curve, 500_000L), 1e-5f)
        assertEquals(10f, KeyframeEvaluator.evaluateFloat(curve, 1_000_000L), 0f)
    }

    @Test
    fun `binary search selects the right segment in long curves`() {
        val keys = List(100) { index -> Keyframe(index * 10_000L, index.toFloat()) }
        val curve = KeyframeCurve(keys)

        assertEquals(0f, KeyframeEvaluator.evaluateFloat(curve, 0L), 1e-6f)
        assertEquals(42f, KeyframeEvaluator.evaluateFloat(curve, 420_000L), 1e-6f)
        assertEquals(42.5f, KeyframeEvaluator.evaluateFloat(curve, 425_000L), 1e-6f)
        assertEquals(99f, KeyframeEvaluator.evaluateFloat(curve, 990_000L), 1e-6f)
    }

    @Test
    fun `hold extrapolation clamps outside the keyframe range`() {
        val curve = floatCurve(keys = arrayOf(Keyframe(0L, 0f), Keyframe(1_000_000L, 10f)))

        assertEquals(0f, KeyframeEvaluator.evaluateFloat(curve, -5_000_000L), 0f)
        assertEquals(10f, KeyframeEvaluator.evaluateFloat(curve, 5_000_000L), 0f)
    }

    @Test
    fun `linear extrapolation extends the edge segments`() {
        val curve =
            floatCurve(
                extrapolation = Extrapolation.LINEAR,
                keys =
                    arrayOf(
                        Keyframe(0L, 0f),
                        Keyframe(1_000_000L, 10f),
                        Keyframe(2_000_000L, 30f),
                    ),
            )

        assertEquals(-5f, KeyframeEvaluator.evaluateFloat(curve, -500_000L), 1e-5f)
        assertEquals(40f, KeyframeEvaluator.evaluateFloat(curve, 2_500_000L), 1e-5f)
    }

    @Test
    fun `loop and ping pong wrap timestamps outside the range`() {
        val looped =
            floatCurve(
                extrapolation = Extrapolation.LOOP,
                keys = arrayOf(Keyframe(0L, 0f), Keyframe(1_000_000L, 10f)),
            )
        assertEquals(5f, KeyframeEvaluator.evaluateFloat(looped, 1_500_000L), 1e-5f)
        assertEquals(7.5f, KeyframeEvaluator.evaluateFloat(looped, -250_000L), 1e-5f)

        val pingPong =
            floatCurve(
                extrapolation = Extrapolation.PING_PONG,
                keys = arrayOf(Keyframe(0L, 0f), Keyframe(1_000_000L, 10f)),
            )
        assertEquals(5f, KeyframeEvaluator.evaluateFloat(pingPong, 1_500_000L), 1e-5f)
        assertEquals(5f, KeyframeEvaluator.evaluateFloat(pingPong, 2_500_000L), 1e-5f)
        assertEquals(5f, KeyframeEvaluator.evaluateFloat(pingPong, -500_000L), 1e-5f)
    }

    @Test
    fun `single keyframe curves are constant everywhere`() {
        val curve = floatCurve(keys = arrayOf(Keyframe(1_000L, 7f)))

        assertEquals(7f, KeyframeEvaluator.evaluateFloat(curve, Long.MIN_VALUE), 0f)
        assertEquals(7f, KeyframeEvaluator.evaluateFloat(curve, 1_000L), 0f)
        assertEquals(7f, KeyframeEvaluator.evaluateFloat(curve, Long.MAX_VALUE), 0f)
    }

    @Test
    fun `int bool vec2 and vec3 types dispatch correctly`() {
        val ints = KeyframeCurve(listOf(Keyframe(0L, 0), Keyframe(1_000_000L, 11)))
        assertEquals(6, KeyframeEvaluator.evaluateInt(ints, 550_000L))

        val bools = KeyframeCurve(listOf(Keyframe(0L, false), Keyframe(1_000_000L, true)))
        assertEquals(false, KeyframeEvaluator.evaluateBool(bools, 499_999L))
        assertEquals(true, KeyframeEvaluator.evaluateBool(bools, 500_000L))

        val vec2s = KeyframeCurve(listOf(Keyframe(0L, Vec2(0f, 10f)), Keyframe(1_000_000L, Vec2(20f, 0f))))
        assertEquals(Vec2(5f, 7.5f), KeyframeEvaluator.evaluateVec2(vec2s, 250_000L))

        val vec3s = KeyframeCurve(listOf(Keyframe(0L, Vec3(0f, 0f, 0f)), Keyframe(1_000_000L, Vec3(10f, 20f, 30f))))
        assertEquals(Vec3(5f, 10f, 15f), KeyframeEvaluator.evaluateVec3(vec3s, 500_000L))
    }

    @Test
    fun `quaternion keyframes use shortest path slerp`() {
        val start = Quaternion.IDENTITY
        val end = Quaternion.fromAxisAngle(Vec3.UP, (Math.PI / 2).toFloat())
        val curve = KeyframeCurve(listOf(Keyframe(0L, start), Keyframe(1_000_000L, end)))

        val mid = KeyframeEvaluator.evaluateQuaternion(curve, 500_000L)
        assertEquals(1f, mid.length(), 1e-5f)
        val dot = abs(mid.x * start.x + mid.y * start.y + mid.z * start.z + mid.w * start.w)
        assertEquals(kotlin.math.cos(Math.PI / 8).toFloat(), dot, 1e-4f)

        // Negating the target must not flip the interpolation direction.
        val opposite =
            KeyframeCurve(listOf(Keyframe(0L, start), Keyframe(1_000_000L, Quaternion(-end.x, -end.y, -end.z, -end.w))))
        val oppositeMid = KeyframeEvaluator.evaluateQuaternion(opposite, 500_000L)
        assertEquals(1f, oppositeMid.length(), 1e-5f)
        assertTrue(abs(oppositeMid.w - mid.w) < 1e-5f)
    }

    @Test
    fun `colour and spline vertices interpolate componentwise`() {
        val colors =
            KeyframeCurve(
                listOf(
                    Keyframe(0L, Color4f(0f, 0f, 0f, 0f)),
                    Keyframe(1_000_000L, Color4f(1f, 0.5f, 0.25f, 1f)),
                ),
            )
        assertEquals(Color4f(0.5f, 0.25f, 0.125f, 0.5f), KeyframeEvaluator.evaluateColor(colors, 500_000L))

        val spline =
            KeyframeCurve(
                listOf(
                    Keyframe(0L, listOf(BezierVertex(Vec3.ZERO), BezierVertex(Vec3(10f, 0f, 0f)))),
                    Keyframe(
                        1_000_000L,
                        listOf(
                            BezierVertex(Vec3(0f, 10f, 0f), featherRadius = 2f),
                            BezierVertex(Vec3(10f, 10f, 0f), inTangent = Vec3(1f, 1f, 0f)),
                        ),
                    ),
                ),
            )
        val mid = KeyframeEvaluator.evaluateSplineVertices(spline, 500_000L)
        assertEquals(2, mid.size)
        assertEquals(Vec3(0f, 5f, 0f), mid[0].position)
        assertEquals(1f, mid[0].featherRadius, 1e-5f)
        assertEquals(Vec3(10f, 5f, 0f), mid[1].position)
        assertEquals(Vec3(0.5f, 0.5f, 0f), mid[1].inTangent)
    }

    @Test
    fun `cubic bezier easing shapes the segment and target keyframe easing is honoured`() {
        val outgoing =
            KeyframeCurve(
                listOf(
                    Keyframe(0L, 0f, Easing.CubicBezier(0.42f, 0f, 0.58f, 1f)),
                    Keyframe(1_000_000L, 100f),
                ),
            )
        val incoming =
            KeyframeCurve(
                listOf(
                    Keyframe(0L, 0f),
                    Keyframe(1_000_000L, 100f, Easing.CubicBezier(0.42f, 0f, 0.58f, 1f)),
                ),
            )
        assertEquals(50f, KeyframeEvaluator.evaluateFloat(outgoing, 500_000L), 1e-3f)
        assertEquals(50f, KeyframeEvaluator.evaluateFloat(incoming, 500_000L), 1e-3f)
        assertTrue(KeyframeEvaluator.evaluateFloat(outgoing, 250_000L) < 25f)
        assertTrue(KeyframeEvaluator.evaluateFloat(outgoing, 750_000L) > 75f)
    }

    @Test
    fun `hold easing keeps the outgoing value for the whole segment`() {
        val curve =
            floatCurve(
                keys =
                    arrayOf(
                        Keyframe(0L, 3f, Easing.Hold),
                        Keyframe(1_000_000L, 9f),
                    ),
            )
        assertEquals(3f, KeyframeEvaluator.evaluateFloat(curve, 0L), 0f)
        assertEquals(3f, KeyframeEvaluator.evaluateFloat(curve, 500_000L), 0f)
        assertEquals(3f, KeyframeEvaluator.evaluateFloat(curve, 999_999L), 0f)
        assertEquals(9f, KeyframeEvaluator.evaluateFloat(curve, 1_000_000L), 0f)
    }

    @Test
    fun `spring easing starts at the source value and lands on the target`() {
        val curve =
            floatCurve(
                keys =
                    arrayOf(
                        Keyframe(0L, 0f, Easing.Spring(stiffness = 170f, damping = 26f)),
                        Keyframe(1_000_000L, 100f),
                    ),
            )
        assertEquals(0f, KeyframeEvaluator.evaluateFloat(curve, 0L), 1e-5f)
        assertEquals(100f, KeyframeEvaluator.evaluateFloat(curve, 1_000_000L), 1e-5f)
        val mid = KeyframeEvaluator.evaluateFloat(curve, 500_000L)
        assertTrue(mid.isFinite() && mid > 0f && mid < 130f, "unexpected spring value $mid")
        // Continuity into the next segment's keyframe value.
        assertEquals(100f, KeyframeEvaluator.evaluateFloat(curve, 999_999L), 0.1f)
    }

    @Test
    fun `empty curves fail loudly`() {
        val failure = runCatching { KeyframeEvaluator.evaluateFloat(KeyframeCurve(), 0L) }
        assertTrue(failure.isFailure)
    }
}
