package com.kinetix3d.evaluator

import com.kinetix3d.model.Easing
import com.kinetix3d.model.Keyframe
import com.kinetix3d.model.KeyframeCurve
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedRampIntegratorTest {
    private fun curve(vararg keys: Keyframe<Float>): KeyframeCurve<Float> = KeyframeCurve(keys.toList())

    @Test
    fun `identity curve maps progress to itself`() {
        val integrator = SpeedRampIntegrator.IDENTITY
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(progress, integrator.mapProgressToSource(progress), 1e-4f)
            assertEquals(progress, integrator.mapSourceToProgress(progress), 1e-4f)
        }
        assertEquals(1f, integrator.createDurationScale(), 1e-6f)
    }

    @Test
    fun `constant velocity scales duration without warping progress`() {
        val integrator = SpeedRampIntegrator.constant(2f)
        assertEquals(0.5f, integrator.mapProgressToSource(0.5f), 1e-4f)
        assertEquals(0.5f, integrator.mapSourceToProgress(0.5f), 1e-4f)
        assertEquals(2f, integrator.createDurationScale(), 1e-4f)
    }

    @Test
    fun `mapping is monotonic and endpoints are exact`() {
        val integrator =
            SpeedRampIntegrator.fromCurve(
                curve(
                    Keyframe(0L, 1f),
                    Keyframe(1_000_000L, 4f, Easing.CubicBezier(0.2f, 0.05f, 0.65f, 2.5f)),
                ),
            )
        var previousSource = -1f
        var previousProgress = -1f
        for (step in 0..200) {
            val progress = step / 200f
            val source = integrator.mapProgressToSource(progress)
            assertTrue(source >= previousSource, "source mapping regressed at $progress")
            previousSource = source

            val inverse = integrator.mapSourceToProgress(progress)
            assertTrue(inverse >= previousProgress, "progress mapping regressed at $progress")
            previousProgress = inverse
        }
        assertEquals(0f, integrator.mapProgressToSource(0f), 1e-6f)
        assertEquals(1f, integrator.mapProgressToSource(1f), 1e-6f)
        assertEquals(0f, integrator.mapSourceToProgress(0f), 1e-6f)
        assertEquals(1f, integrator.mapSourceToProgress(1f), 1e-6f)
    }

    @Test
    fun `linear speed ramp integrates its real endpoint velocities`() {
        // 1x -> 4x over one second: v(t) = 1 + 3t, average 2.5x.
        val integrator =
            SpeedRampIntegrator.fromCurve(
                curve(
                    Keyframe(0L, 1f),
                    Keyframe(1_000_000L, 4f),
                ),
            )
        assertEquals(2.5f, integrator.createDurationScale(), 1e-3f)
        // S(0.5) = ∫0^0.5 (1 + 3x) dx / 2.5 = 0.875 / 2.5.
        assertEquals(0.35f, integrator.mapProgressToSource(0.5f), 2e-3f)
        assertEquals(0f, integrator.mapProgressToSource(0f), 1e-6f)
        assertEquals(1f, integrator.mapProgressToSource(1f), 1e-6f)
    }

    @Test
    fun `nudging an endpoint velocity is continuous`() {
        val uniform =
            SpeedRampIntegrator.fromCurve(
                curve(Keyframe(0L, 2f), Keyframe(1_000_000L, 2f)),
            )
        val nudged =
            SpeedRampIntegrator.fromCurve(
                curve(Keyframe(0L, 2f), Keyframe(1_000_000L, 2.0001f)),
            )

        assertEquals(2f, uniform.createDurationScale(), 1e-4f)
        assertEquals(2.00005f, nudged.createDurationScale(), 1e-3f)
        assertTrue(abs(uniform.createDurationScale() - nudged.createDurationScale()) < 1e-3f)
        assertEquals(0.5f, nudged.mapProgressToSource(0.5f), 1e-3f)
    }

    @Test
    fun `multi segment speed ramps integrate every keyframe`() {
        // 1x -> 4x -> 0.2x -> 1x with equal segment durations.
        // Segment averages: 2.5, 2.1, 0.6 => overall average 5.2 / 3.
        val integrator =
            SpeedRampIntegrator.fromCurve(
                curve(
                    Keyframe(0L, 1f),
                    Keyframe(1_000_000L, 4f),
                    Keyframe(2_000_000L, 0.2f),
                    Keyframe(3_000_000L, 1f),
                ),
            )
        assertEquals(5.2f / 3f, integrator.createDurationScale(), 1e-2f)
        // The source is consumed fastest in the middle of the first segment and
        // slowest in the middle of the third, so the half-way source point lies
        // well before the mid-point of the timeline.
        val half = integrator.mapSourceToProgress(0.5f)
        assertTrue(half in 0.25f..0.45f, "expected early source consumption, got $half")
        assertEquals(0.5f, integrator.mapProgressToSource(half), 5e-3f)
    }

    @Test
    fun `target keyframe easing shapes the segment when the source is linear`() {
        val outgoing =
            curve(
                Keyframe(0L, 1f, Easing.CubicBezier(0.42f, 0f, 0.58f, 1f)),
                Keyframe(1_000_000L, 4f),
            )
        val incoming =
            curve(
                Keyframe(0L, 1f),
                Keyframe(1_000_000L, 4f, Easing.CubicBezier(0.42f, 0f, 0.58f, 1f)),
            )

        val a = SpeedRampIntegrator.fromCurve(outgoing)
        val b = SpeedRampIntegrator.fromCurve(incoming)
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(a.mapProgressToSource(progress), b.mapProgressToSource(progress), 1e-5f)
        }
        assertEquals(a.createDurationScale(), b.createDurationScale(), 1e-5f)
    }

    @Test
    fun `hold easing keeps the outgoing velocity until the next keyframe`() {
        val integrator =
            SpeedRampIntegrator.fromCurve(
                curve(
                    Keyframe(0L, 1f, Easing.Hold),
                    Keyframe(1_000_000L, 3f),
                ),
            )
        // A step holds 1x for the whole segment; 3x applies only at the next keyframe.
        assertEquals(1f, integrator.createDurationScale(), 1e-3f)
        assertEquals(0.5f, integrator.mapProgressToSource(0.5f), 2e-3f)
        assertEquals(0.75f, integrator.mapProgressToSource(0.75f), 2e-3f)
    }

    @Test
    fun `forward and inverse mappings round trip`() {
        val integrator =
            SpeedRampIntegrator.fromCurve(
                curve(
                    Keyframe(0L, 1f),
                    Keyframe(400_000L, 0.3f),
                    Keyframe(1_000_000L, 2.2f, Easing.CubicBezier(0.15f, 0.2f, 0.7f, 1.8f)),
                ),
            )
        for (step in 1..99) {
            val progress = step / 100f
            val source = integrator.mapProgressToSource(progress)
            assertEquals(progress, integrator.mapSourceToProgress(source), 5e-3f)
        }
    }

    @Test
    fun `source timestamps respect duration and normalization`() {
        val identity = SpeedRampIntegrator.IDENTITY
        assertEquals(500_000L, identity.sourceTimeUs(0.5f, 1_000_000L))
        assertEquals(0L, identity.sourceTimeUs(0f, 1_000_000L))
        assertEquals(1_000_000L, identity.sourceTimeUs(1f, 1_000_000L))
        assertEquals(0L, identity.sourceTimeUs(0.5f, -10L))

        val doubleSpeed = SpeedRampIntegrator.constant(2f)
        assertEquals(500_000L, doubleSpeed.sourceTimeUs(0.5f, 1_000_000L))
    }

    @Test
    fun `degenerate curves never produce NaN`() {
        val curves =
            listOf(
                SpeedRampIntegrator.constant(0f),
                SpeedRampIntegrator.constant(Float.NaN),
                SpeedRampIntegrator.fromCurve(curve(Keyframe(0L, -5f), Keyframe(1_000_000L, -1f))),
                SpeedRampIntegrator.fromCurve(
                    curve(Keyframe(0L, Float.NaN), Keyframe(1_000_000L, Float.POSITIVE_INFINITY)),
                ),
                SpeedRampIntegrator.fromCurve(
                    curve(
                        Keyframe(0L, 1f, Easing.CubicBezier(-5f, 5f, 5f, -5f)),
                        Keyframe(1_000_000L, 2f),
                    ),
                ),
                SpeedRampIntegrator.fromCurve(
                    curve(
                        Keyframe(0L, 1f, Easing.Spring(stiffness = 170f, damping = 26f)),
                        Keyframe(1_000_000L, 3f),
                    ),
                ),
            )
        for (integrator in curves) {
            for (step in 0..20) {
                val progress = step / 20f
                val source = integrator.mapProgressToSource(progress)
                val inverse = integrator.mapSourceToProgress(progress)
                val time = integrator.sourceTimeUs(progress, 1_000_000L)
                assertTrue(source.isFinite() && source in 0f..1f)
                assertTrue(inverse.isFinite() && inverse in 0f..1f)
                assertTrue(time in 0L..1_000_000L)
                assertTrue(integrator.createDurationScale().isFinite())
                assertTrue(integrator.createDurationScale() > 0f)
            }
        }
    }

    @Test
    fun `overshooting bezier easing keeps the remap monotonic`() {
        val integrator =
            SpeedRampIntegrator.fromCurve(
                curve(
                    Keyframe(0L, 1f, Easing.CubicBezier(0.68f, -0.55f, 0.27f, 1.55f)),
                    Keyframe(1_000_000L, 3f),
                ),
            )
        var previous = -1f
        for (step in 0..200) {
            val source = integrator.mapProgressToSource(step / 200f)
            assertTrue(source >= previous)
            previous = source
        }
    }

    @Test
    fun `empty curve falls back to identity`() {
        val integrator = SpeedRampIntegrator.fromCurve(KeyframeCurve())
        assertEquals(1f, integrator.createDurationScale(), 1e-6f)
        assertEquals(0.25f, integrator.mapProgressToSource(0.25f), 1e-4f)
    }
}
