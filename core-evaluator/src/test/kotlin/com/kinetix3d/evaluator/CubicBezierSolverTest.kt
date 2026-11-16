package com.kinetix3d.evaluator

import com.kinetix3d.model.Easing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubicBezierSolverTest {
    @Test
    fun `endpoints are exact`() {
        assertEquals(0f, CubicBezierSolver.evaluate(0.42f, 0f, 0.58f, 1f, 0f), 0f)
        assertEquals(1f, CubicBezierSolver.evaluate(0.42f, 0f, 0.58f, 1f, 1f), 0f)
    }

    @Test
    fun `linear control points behave as identity`() {
        for (step in 0..100) {
            val progress = step / 100f
            val result = CubicBezierSolver.evaluate(0f, 0f, 1f, 1f, progress)
            assertEquals(progress, result, 1e-4f)
        }
    }

    @Test
    fun `ease in out curve is monotonic`() {
        var previous = -1f
        for (step in 0..1000) {
            val progress = step / 1000f
            val result = CubicBezierSolver.evaluate(0.42f, 0f, 0.58f, 1f, progress)
            assertTrue(result >= previous - 1e-5f, "result regressed at progress $progress")
            previous = result
        }
    }

    @Test
    fun `degenerate x controls stay finite`() {
        for (step in 0..100) {
            val progress = step / 100f
            val result = CubicBezierSolver.evaluate(0f, 0f, 0f, 1f, progress)
            assertTrue(result.isFinite(), "result must be finite at progress $progress")
            assertTrue(result in 0f..1f, "result must stay in range, was $result")
        }
    }

    @Test
    fun `y overshoot is preserved and finite`() {
        var maximum = Float.NEGATIVE_INFINITY
        for (step in 0..100) {
            val progress = step / 100f
            val result = CubicBezierSolver.evaluate(0.25f, 5f, 0.75f, -2f, progress)
            assertTrue(result.isFinite(), "result must be finite at progress $progress")
            maximum = maxOf(maximum, result)
        }
        assertTrue(maximum > 1f, "overshoot should be allowed, max was $maximum")
    }

    @Test
    fun `progress and x controls are clamped`() {
        assertEquals(0f, CubicBezierSolver.evaluate(-2f, 1f, 4f, 1f, -1f), 0f)
        assertEquals(1f, CubicBezierSolver.evaluate(-2f, 1f, 4f, 1f, 2f), 0f)
        val result = CubicBezierSolver.evaluate(-2f, 1f, 4f, 1f, 0.5f)
        assertTrue(result.isFinite())
    }

    @Test
    fun `model easing overload matches primitive overload`() {
        val easing = Easing.CubicBezier(0.25f, 0.1f, 0.25f, 1f)
        assertEquals(
            CubicBezierSolver.evaluate(0.25f, 0.1f, 0.25f, 1f, 0.37f),
            CubicBezierSolver.evaluate(easing, 0.37f),
            0f,
        )
    }

    @Test
    fun `solved parameter maps back to requested progress`() {
        for (step in 1..99) {
            val progress = step / 100f
            val u = CubicBezierSolver.solveParameter(0.2f, 0.8f, progress)
            assertEquals(progress, CubicBezierSolver.evaluateX(0.2f, 0.8f, u), 1e-4f)
        }
    }
}
