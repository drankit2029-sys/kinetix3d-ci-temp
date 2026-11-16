package com.kinetix3d.evaluator

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticalSpringSolverTest {
    @Test
    fun `time zero returns the from value for every regime`() {
        val configs =
            listOf(
                SpringConfig(100f, 10f),
                SpringConfig(100f, 20f),
                SpringConfig(100f, 40f),
            )
        for (config in configs) {
            val result = AnalyticalSpringSolver.solve(config, 3f, 8f, 2f, 0f)
            assertEquals(3f, result, 1e-5f)
        }
    }

    @Test
    fun `springs settle on the target`() {
        val configs =
            listOf(
                SpringConfig(100f, 10f),
                SpringConfig(100f, 20f),
                SpringConfig(100f, 40f),
            )
        for (config in configs) {
            val result = AnalyticalSpringSolver.solve(config, -4f, 12f, 0f, 20f)
            assertEquals(12f, result, 1e-4f)
        }
    }

    @Test
    fun `critically damped value matches the closed form`() {
        val stiffness = 144f
        val omega = sqrt(stiffness)
        val time = 0.23f
        val expected = 7f + exp(-omega * time) * (-5f + (omega * -5f + 3f) * time)
        val actual = AnalyticalSpringSolver.solve(SpringConfig(stiffness, 2f * omega), 2f, 7f, 3f, time)
        assertEquals(expected, actual, 1e-5f)
    }

    @Test
    fun `zeta near one follows the critical branch`() {
        val stiffness = 100f
        val criticalDamping = 2f * sqrt(stiffness)
        val time = 0.31f
        val critical =
            AnalyticalSpringSolver.solve(
                SpringConfig(stiffness, criticalDamping),
                5f,
                2f,
                1f,
                time,
            )
        for (offset in listOf(-1e-5f, 1e-5f)) {
            val near =
                AnalyticalSpringSolver.solve(
                    SpringConfig(stiffness, criticalDamping * (1f + offset)),
                    5f,
                    2f,
                    1f,
                    time,
                )
            assertEquals(critical, near, 1e-4f)
        }
    }

    @Test
    fun `equal from and to values never divide by zero`() {
        val result = AnalyticalSpringSolver.solve(SpringConfig(80f, 4f), 6f, 6f, 9f, 0.5f)
        assertTrue(result.isFinite())
        val settled = AnalyticalSpringSolver.solve(SpringConfig(80f, 4f), 6f, 6f, 9f, 10f)
        assertEquals(6f, settled, 1e-4f)
    }

    @Test
    fun `negative and zero spring parameters are sanitized`() {
        val configs =
            listOf(
                SpringConfig(-10f, -5f),
                SpringConfig(0f, 0f),
                SpringConfig(Float.NaN, Float.POSITIVE_INFINITY),
            )
        for (config in configs) {
            for (step in 0..20) {
                val result = AnalyticalSpringSolver.solve(config, 1f, 5f, 0f, step * 0.1f)
                assertTrue(result.isFinite(), "spring result must be finite for $config")
            }
        }
    }

    @Test
    fun `overdamped solution is stable at large times`() {
        val result = AnalyticalSpringSolver.solve(SpringConfig(100f, 60f), 0f, 1f, 0f, 1000f)
        assertTrue(result.isFinite())
        assertEquals(1f, result, 1e-5f)
    }

    @Test
    fun `initial velocity moves the spring away from rest`() {
        val withoutVelocity = AnalyticalSpringSolver.solve(SpringConfig(100f, 10f), 0f, 1f, 0f, 0.05f)
        val withVelocity = AnalyticalSpringSolver.solve(SpringConfig(100f, 10f), 0f, 1f, 20f, 0.05f)
        assertTrue(withVelocity > withoutVelocity)
    }
}
