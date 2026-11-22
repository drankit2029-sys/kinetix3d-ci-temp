package com.kinetix3d.render

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomographyTest {
    private fun apply(
        matrix: FloatArray,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val px = matrix[0] * x + matrix[3] * y + matrix[6]
        val py = matrix[1] * x + matrix[4] * y + matrix[7]
        val pw = matrix[2] * x + matrix[5] * y + matrix[8]
        return (px / pw) to (py / pw)
    }

    private fun assertNear(
        expected: Pair<Float, Float>,
        actual: Pair<Float, Float>,
    ) {
        assertTrue(abs(expected.first - actual.first) < 1e-4f, "x ${expected.first} vs ${actual.first}")
        assertTrue(abs(expected.second - actual.second) < 1e-4f, "y ${expected.second} vs ${actual.second}")
    }

    @Test
    fun identityQuadMapsBackToTheUnitSquare() {
        val inverse =
            assertNotNull(
                Homography.inverseFromUnitSquare(
                    floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                ),
            )
        assertNear(0f to 0f, apply(inverse, 0f, 0f))
        assertNear(1f to 0f, apply(inverse, 1f, 0f))
        assertNear(1f to 1f, apply(inverse, 1f, 1f))
        assertNear(0f to 1f, apply(inverse, 0f, 1f))
    }

    @Test
    fun affineQuadMapsBackToTheUnitSquare() {
        val inverse =
            assertNotNull(
                Homography.inverseFromUnitSquare(
                    floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f),
                ),
            )
        assertNear(0f to 0f, apply(inverse, 0.1f, 0.1f))
        assertNear(1f to 0f, apply(inverse, 0.9f, 0.1f))
        assertNear(1f to 1f, apply(inverse, 0.9f, 0.9f))
        assertNear(0f to 1f, apply(inverse, 0.1f, 0.9f))
    }

    @Test
    fun perspectiveQuadMapsBackToTheUnitSquare() {
        val inverse =
            assertNotNull(
                Homography.inverseFromUnitSquare(
                    floatArrayOf(0.2f, 0.1f, 0.8f, 0.1f, 1f, 0.9f, 0f, 0.9f),
                ),
            )
        assertNear(0f to 0f, apply(inverse, 0.2f, 0.1f))
        assertNear(1f to 0f, apply(inverse, 0.8f, 0.1f))
        assertNear(1f to 1f, apply(inverse, 1f, 0.9f))
        assertNear(0f to 1f, apply(inverse, 0f, 0.9f))
    }

    @Test
    fun degenerateQuadReturnsNull() {
        assertTrue(
            Homography.inverseFromUnitSquare(
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            ) == null,
        )
    }
}
