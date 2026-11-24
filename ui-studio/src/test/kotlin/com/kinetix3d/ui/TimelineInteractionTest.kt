package com.kinetix3d.ui

import androidx.compose.ui.graphics.Color
import com.kinetix3d.model.TimelineMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineInteractionTest {
    private val clip = TimelineClipUi("clip", "Clip", 1_000_000L, 4_000_000L, Color.Blue)
    private val project =
        TimelineProjectUi(
            name = "Test",
            durationUs = 10_000_000L,
            tracks = listOf(TimelineTrackUi("V1", "VIDEO", listOf(clip))),
        )
    private val widthPx = 1000f
    private val rulerPx = 28f
    private val trackPx = 44f
    private val durationUs = 10_000_000L
    private val edgeHitPx = 24f

    @Test
    fun `snapUs picks the nearest target within the threshold`() {
        assertEquals(1_000_000L, snapUs(1_050_000L, listOf(0L, 1_000_000L, 2_000_000L), 100_000L))
        assertEquals(1_050_000L, snapUs(1_050_000L, listOf(0L, 1_000_000L), 10_000L))
        assertEquals(1_050_000L, snapUs(1_050_000L, emptyList(), 100_000L))
    }

    @Test
    fun `snapTargets include clip edges, playhead and markers`() {
        val markers = listOf(TimelineMarker("m1", 3_000_000L))
        val withClip = snapTargetsUs(project, 500_000L, markers, null)
        assertTrue(1_000_000L in withClip)
        assertTrue(5_000_000L in withClip)
        assertTrue(500_000L in withClip)
        assertTrue(3_000_000L in withClip)
        val withoutClip = snapTargetsUs(project, 500_000L, markers, "clip")
        assertTrue(1_000_000L !in withoutClip)
        assertTrue(5_000_000L !in withoutClip)
        assertTrue(500_000L in withoutClip)
        assertTrue(3_000_000L in withoutClip)
    }

    @Test
    fun `marker ticks have a coarse hit target`() {
        val markers = listOf(TimelineMarker("m1", 2_000_000L), TimelineMarker("m2", 8_000_000L))
        assertEquals(
            "m1",
            hitTestMarker(210f, 10f, markers, widthPx, rulerPx, durationUs, 12f)?.markerId,
        )
        assertNull(hitTestMarker(230f, 10f, markers, widthPx, rulerPx, durationUs, 12f))
        assertNull(hitTestMarker(200f, rulerPx + 10f, markers, widthPx, rulerPx, durationUs, 12f))
    }

    @Test
    fun `clip edges have generous hit targets`() {
        val left =
            hitTestClipEdge(
                x = 101f,
                y = rulerPx + 10f,
                project = project,
                widthPx = widthPx,
                rulerPx = rulerPx,
                trackPx = trackPx,
                durationUs = durationUs,
                edgeHitPx = edgeHitPx,
            )
        assertEquals(ClipEdge.START, left?.edge)

        val right =
            hitTestClipEdge(
                x = 499f,
                y = rulerPx + 10f,
                project = project,
                widthPx = widthPx,
                rulerPx = rulerPx,
                trackPx = trackPx,
                durationUs = durationUs,
                edgeHitPx = edgeHitPx,
            )
        assertEquals(ClipEdge.END, right?.edge)

        assertNull(
            hitTestClipEdge(
                x = 300f,
                y = rulerPx + 10f,
                project = project,
                widthPx = widthPx,
                rulerPx = rulerPx,
                trackPx = trackPx,
                durationUs = durationUs,
                edgeHitPx = edgeHitPx,
            ),
        )
    }

    @Test
    fun `trim range clamps to zero and minimum duration`() {
        val (start, end) = trimRange(1_000_000L, 5_000_000L, -2_000_000L, ClipEdge.START)
        assertEquals(0L, start)
        assertEquals(5_000_000L, end)

        val (start2, end2) = trimRange(1_000_000L, 5_000_000L, 10_000_000L, ClipEdge.END)
        assertEquals(1_000_000L, start2)
        assertEquals(15_000_000L, end2)

        val (start3, end3) = trimRange(1_000_000L, 1_050_000L, 1_000_000L, ClipEdge.START)
        assertEquals(950_000L, start3)
        assertEquals(1_050_000L, end3)
    }

    @Test
    fun `x to us clamps to the timeline`() {
        assertEquals(0L, xToUs(-10f, widthPx, durationUs))
        assertEquals(5_000_000L, xToUs(500f, widthPx, durationUs))
        assertEquals(10_000_000L, xToUs(1100f, widthPx, durationUs))
    }
}
