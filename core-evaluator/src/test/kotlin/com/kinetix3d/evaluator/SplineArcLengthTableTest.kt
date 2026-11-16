package com.kinetix3d.evaluator

import com.kinetix3d.model.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplineArcLengthTableTest {
    private val epsilon = 1e-3f

    @Test
    fun `straight line arc length and positions are correct`() {
        val table = straightLine()
        assertEquals(10f, table.totalLength, epsilon)
        assertEquals(SplineArcLengthTable.TABLE_SIZE, table.cumLength.size)

        val start = table.sampleAtNormalizedDistance(0f)
        assertVec(start.position, Vec3.ZERO, epsilon)

        val middle = table.sampleAtNormalizedDistance(0.5f)
        assertEquals(5f, middle.position.x, 1e-2f)
        assertEquals(0f, middle.position.y, epsilon)
        assertEquals(0f, middle.position.z, epsilon)

        val end = table.sampleAtNormalizedDistance(1f)
        assertEquals(10f, end.position.x, 1e-2f)
    }

    @Test
    fun `straight line tangent and normal are orthonormal`() {
        val table = straightLine()
        for (step in 0..20) {
            val sample = table.sampleAtNormalizedDistance(step / 20f)
            assertEquals(1f, sample.tangent.length(), epsilon)
            assertEquals(1f, sample.normal.length(), epsilon)
            assertEquals(1f, sample.tangent.x, epsilon)
            assertEquals(0f, sample.tangent.dot(sample.normal), epsilon)
        }
    }

    @Test
    fun `cumulative length is monotonic`() {
        val table = straightLine()
        for (index in 1 until table.cumLength.size) {
            assertTrue(
                table.cumLength[index] >= table.cumLength[index - 1],
                "cumLength regressed at $index",
            )
        }
        assertEquals(10f, table.cumLength.last(), epsilon)
    }

    @Test
    fun `closed square wraps continuously at the seam`() {
        val table = closedSquare()
        assertEquals(40f, table.totalLength, 1e-2f)
        assertVec(table.sampleAtNormalizedDistance(0f).position, Vec3(0f, 0f, 0f), 1e-2f)
        val end = table.sampleAtNormalizedDistance(1f).position
        assertVec(end, Vec3.ZERO, 1e-2f)
        val loopSeam = table.sampleAtNormalizedDistance(1f, LoopMode.LOOP).position
        assertVec(loopSeam, table.sampleAtNormalizedDistance(0f, LoopMode.LOOP).position, 1e-4f)
    }

    @Test
    fun `loop modes map progress deterministically`() {
        val table = straightLine()
        val quarter = table.sampleAtNormalizedDistance(0.25f).position.x
        val threeQuarters = table.sampleAtNormalizedDistance(0.75f).position.x

        assertEquals(quarter, table.sampleAtNormalizedDistance(1.25f, LoopMode.LOOP).position.x, 1e-2f)
        assertEquals(threeQuarters, table.sampleAtNormalizedDistance(1.25f, LoopMode.PING_PONG).position.x, 1e-2f)
        assertEquals(5f, table.sampleAtNormalizedDistance(-0.5f, LoopMode.LOOP).position.x, 1e-2f)
        assertEquals(0f, table.sampleAtNormalizedDistance(-0.5f, LoopMode.ONCE).position.x, 1e-2f)
        assertEquals(10f, table.sampleAtNormalizedDistance(5f, LoopMode.ONCE).position.x, 1e-2f)
        assertEquals(10f, table.sampleAtNormalizedDistance(5f, LoopMode.HOLD).position.x, 1e-2f)
        assertEquals(10f, table.sampleAtNormalizedDistance(1f, LoopMode.PING_PONG).position.x, 1e-2f)
    }

    @Test
    fun `closed square samples are finite with perpendicular normals`() {
        val table = closedSquare()
        for (step in 0..100) {
            val sample = table.sampleAtNormalizedDistance(step / 100f, LoopMode.PING_PONG)
            assertTrue(sample.position.x.isFinite() && sample.position.y.isFinite())
            assertTrue(sample.tangent.length() in 0.99f..1.01f)
            assertTrue(sample.normal.length() in 0.99f..1.01f)
            assertEquals(0f, sample.tangent.dot(sample.normal), 1e-3f)
        }
    }

    @Test
    fun `degenerate splines return the safe default`() {
        val empty = SplineArcLengthTable(emptyList())
        assertEquals(PathSample.EMPTY, empty.sampleAtNormalizedDistance(0.5f))
        assertEquals(0f, empty.totalLength)

        val single = SplineArcLengthTable(listOf(BezierVertex(Vec3(3f, 4f, 5f))))
        assertEquals(PathSample.EMPTY, single.sampleAtNormalizedDistance(0.5f))

        val zeroLength =
            SplineArcLengthTable(
                listOf(BezierVertex(Vec3.ONE), BezierVertex(Vec3.ONE)),
            )
        assertEquals(PathSample.EMPTY, zeroLength.sampleAtNormalizedDistance(0.5f))
        for (index in zeroLength.cumLength.indices) {
            assertTrue(zeroLength.cumLength[index].isFinite())
        }
    }

    @Test
    fun `feather radius is carried on vertices`() {
        val vertex = BezierVertex(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, featherRadius = 2.5f)
        assertEquals(2.5f, vertex.featherRadius)
    }

    private fun straightLine(): SplineArcLengthTable =
        SplineArcLengthTable(
            listOf(
                BezierVertex(Vec3(0f, 0f, 0f)),
                BezierVertex(Vec3(10f, 0f, 0f)),
            ),
        )

    private fun closedSquare(): SplineArcLengthTable =
        SplineArcLengthTable(
            listOf(
                BezierVertex(Vec3(0f, 0f, 0f)),
                BezierVertex(Vec3(10f, 0f, 0f)),
                BezierVertex(Vec3(10f, 10f, 0f)),
                BezierVertex(Vec3(0f, 10f, 0f)),
            ),
            closed = true,
        )

    private fun assertVec(
        actual: Vec3,
        expected: Vec3,
        tolerance: Float,
    ) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }
}
