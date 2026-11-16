package com.kinetix3d.evaluator

import com.kinetix3d.model.Easing
import com.kinetix3d.model.Keyframe
import com.kinetix3d.model.KeyframeCurve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedRampIntegratorTest {
    @Test
    fun `identity curve maps progress to itself`() {
        val integrator = SpeedRampIntegrator(0f, 0f, 1f, 1f)
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(progress, integrator.mapProgressToSource(progress), 1e-4f)
            assertEquals(progress, integrator.mapSourceToProgress(progress), 1e-4f)
        }
        assertEquals(1f, integrator.createDurationScale(), 1e-6f)
    }

    @Test
    fun `mapping is monotonic and endpoints are exact`() {
        val integrator = SpeedRampIntegrator(0.2f, 0.05f, 0.65f, 2.5f)
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
    fun `constant velocity curve keeps a linear normalized shape`() {
        val integrator = SpeedRampIntegrator(0f, 2f, 1f, 2f)
        assertEquals(0.5f, integrator.mapProgressToSource(0.5f), 1e-4f)
        assertEquals(0.5f, integrator.mapSourceToProgress(0.5f), 1e-4f)
        assertEquals(2f, integrator.createDurationScale(), 1e-4f)
    }

    @Test
    fun `forward and inverse mappings round trip`() {
        val integrator = SpeedRampIntegrator(0.15f, 0.2f, 0.7f, 1.8f)
        for (step in 1..99) {
            val progress = step / 100f
            val source = integrator.mapProgressToSource(progress)
            assertEquals(progress, integrator.mapSourceToProgress(source), 5e-3f)
        }
    }

    @Test
    fun `source timestamps respect duration and normalization`() {
        val identity = SpeedRampIntegrator(0f, 0f, 1f, 1f)
        assertEquals(500_000L, identity.sourceTimeUs(0.5f, 1_000_000L))
        assertEquals(0L, identity.sourceTimeUs(0f, 1_000_000L))
        assertEquals(1_000_000L, identity.sourceTimeUs(1f, 1_000_000L))
        assertEquals(0L, identity.sourceTimeUs(0.5f, -10L))

        val doubleSpeed = SpeedRampIntegrator(0f, 2f, 1f, 2f)
        assertEquals(500_000L, doubleSpeed.sourceTimeUs(0.5f, 1_000_000L))
    }

    @Test
    fun `degenerate curves never produce NaN`() {
        val curves =
            listOf(
                SpeedRampIntegrator(0f, 0f, 0f, 0f),
                SpeedRampIntegrator(Float.NaN, Float.NaN, Float.NaN, Float.NaN),
                SpeedRampIntegrator(-5f, 5f, 5f, -5f),
                SpeedRampIntegrator(0f, -1f, 1f, -1f),
                SpeedRampIntegrator(0.5f, 0f, 0.5f, 0f),
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
    fun `fromCurve uses the first cubic bezier easing`() {
        val curve =
            KeyframeCurve(
                keyframes =
                    listOf(
                        Keyframe(0L, 0f, Easing.CubicBezier(0f, 2f, 1f, 2f)),
                        Keyframe(1_000_000L, 1f, Easing.Linear),
                    ),
            )
        val integrator = SpeedRampIntegrator.fromCurve(curve)
        assertEquals(2f, integrator.createDurationScale(), 1e-4f)
        assertEquals(0.5f, integrator.mapProgressToSource(0.5f), 1e-4f)
    }

    @Test
    fun `fromCurve falls back to identity without a cubic segment`() {
        val integrator =
            SpeedRampIntegrator.fromCurve(
                KeyframeCurve.linear(listOf(Keyframe(0L, 0f), Keyframe(1_000_000L, 1f))),
            )
        assertEquals(1f, integrator.createDurationScale(), 1e-6f)
        assertEquals(0.25f, integrator.mapProgressToSource(0.25f), 1e-4f)
    }
}
