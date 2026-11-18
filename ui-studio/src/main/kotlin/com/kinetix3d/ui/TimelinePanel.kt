/*
 * Kinetix3D Studio — ui-studio
 * Phase 2 timeline surface: tracks, clips, ruler, playhead and transport.
 * Phone-first: clip edges use a 24dp hit target (4dp visual handle); trim is a
 * coarse horizontal drag, never a precision-only control.
 */

package com.kinetix3d.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetix3d.model.ClipNode
import com.kinetix3d.model.CompositionGraph
import com.kinetix3d.model.PolygonClipPayload
import com.kinetix3d.model.PreCompClipPayload
import com.kinetix3d.model.TextClipPayload
import java.util.Locale

private val TimelineBackground = Color(0xFF0B0F14)
private val TimelineTrack = Color(0xFF141A22)
private val TimelineTrackAlt = Color(0xFF10151C)
private val TimelineGrid = Color(0xFF2A3442)
private val TimelineText = Color(0xFFE7ECF3)
private val TimelineMuted = Color(0xFF8A94A3)
private val TimelineAccent = Color(0xFF38E1C6)
private val ClipColors =
    listOf(
        Color(0xFF2D6CDF),
        Color(0xFFB45A2A),
        Color(0xFF6A4CC7),
        Color(0xFF2A8C6A),
    )

data class TimelineClipUi(
    val id: String,
    val label: String,
    val startUs: Long,
    val durationUs: Long,
    val color: Color,
)

data class TimelineTrackUi(
    val name: String,
    val type: String,
    val clips: List<TimelineClipUi>,
)

data class TimelineProjectUi(
    val name: String,
    val durationUs: Long,
    val tracks: List<TimelineTrackUi>,
)

fun demoTimelineProject(): TimelineProjectUi {
    val second = 1_000_000L
    return TimelineProjectUi(
        name = "Demo Edit",
        durationUs = 24 * second,
        tracks =
            listOf(
                TimelineTrackUi(
                    name = "V2",
                    type = "VIDEO",
                    clips =
                        listOf(
                            TimelineClipUi("v2a", "Title", 2 * second, 4 * second, ClipColors[2]),
                            TimelineClipUi("v2b", "Logo", 14 * second, 5 * second, ClipColors[2]),
                        ),
                ),
                TimelineTrackUi(
                    name = "V1",
                    type = "VIDEO",
                    clips =
                        listOf(
                            TimelineClipUi("v1a", "Winter Olympics", 0L, 8 * second, ClipColors[0]),
                            TimelineClipUi("v1b", "B-Roll", 8 * second, 6 * second, ClipColors[1]),
                            TimelineClipUi("v1c", "Interview", 14 * second, 10 * second, ClipColors[0]),
                        ),
                ),
                TimelineTrackUi(
                    name = "A1",
                    type = "AUDIO",
                    clips =
                        listOf(
                            TimelineClipUi("a1a", "Music Bed", 0L, 24 * second, ClipColors[3]),
                        ),
                ),
            ),
    )
}

fun CompositionGraph.toTimelineProjectUi(): TimelineProjectUi {
    val duration = durationUs().coerceAtLeast(1_000_000L)
    val tracksUi =
        tracks.mapIndexed { index, track ->
            val clipsUi =
                clips.values
                    .filter { it.trackId == track.trackId }
                    .sortedBy { it.timelineInUs }
                    .map { clip ->
                        TimelineClipUi(
                            id = clip.clipId,
                            label = clipLabel(clip),
                            startUs = clip.timelineInUs,
                            durationUs = clip.durationUs,
                            color = ClipColors[index % ClipColors.size],
                        )
                    }
            TimelineTrackUi(
                name = track.trackId,
                type = track.type.name,
                clips = clipsUi,
            )
        }
    return TimelineProjectUi(name = name, durationUs = duration, tracks = tracksUi)
}

private fun clipLabel(clip: ClipNode): String =
    when (val payload = clip.payload) {
        is TextClipPayload -> payload.textSource.text
        is PolygonClipPayload -> payload.polygonId
        is PreCompClipPayload -> payload.targetCompId
        else -> clip.assetId ?: clip.clipId
    }

@Composable
fun TimelinePanel(
    project: TimelineProjectUi,
    playheadUs: Long,
    onPlayheadChange: (Long) -> Unit,
    selectedClipId: String? = null,
    onSelectClip: (String?) -> Unit = {},
    onTrimClip: (String, Long, Long) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var pxPerSecond by remember { mutableFloatStateOf(72f) }
    val scrollState = rememberScrollState()
    val currentPlayhead by rememberUpdatedState(playheadUs)
    val durationUs = project.durationUs.coerceAtLeast(1_000_000L)
    val timelineWidth = ((durationUs / 1_000_000f) * pxPerSecond).dp
    val trackHeight = 44.dp
    val rulerHeight = 28.dp
    val headerWidth = 76.dp
    val selectedClip =
        project.tracks
            .asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == selectedClipId }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastNanos = 0L
        while (isPlaying) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val next = (currentPlayhead + (now - lastNanos) / 1_000L).coerceAtMost(durationUs)
                    onPlayheadChange(next)
                    if (next >= durationUs) isPlaying = false
                }
                lastNanos = now
            }
        }
    }

    Column(modifier = modifier.background(TimelineBackground)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = project.name,
                color = TimelineText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(playheadUs),
                color = TimelineAccent,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { isPlaying = !isPlaying }) {
                Text(if (isPlaying) "Pause" else "Play", color = TimelineText)
            }
            TextButton(onClick = { pxPerSecond = (pxPerSecond - 16f).coerceAtLeast(32f) }) {
                Text("−", color = TimelineText)
            }
            TextButton(onClick = { pxPerSecond = (pxPerSecond + 16f).coerceAtMost(160f) }) {
                Text("+", color = TimelineText)
            }
        }
        if (selectedClip != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedClip.label,
                    color = TimelineAccent,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onTrimClip(
                            selectedClip.id,
                            selectedClip.startUs + TRIM_STEP_US,
                            selectedClip.startUs + selectedClip.durationUs,
                        )
                    },
                ) {
                    Text("Trim In +0.5s", fontSize = 10.sp)
                }
                TextButton(
                    onClick = {
                        onTrimClip(
                            selectedClip.id,
                            selectedClip.startUs,
                            selectedClip.startUs + selectedClip.durationUs - TRIM_STEP_US,
                        )
                    },
                ) {
                    Text("Trim Out −0.5s", fontSize = 10.sp)
                }
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(rulerHeight + trackHeight * project.tracks.size.toFloat()),
        ) {
            Column(
                modifier =
                    Modifier
                        .width(headerWidth)
                        .fillMaxHeight(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .height(rulerHeight)
                            .fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "TRACKS",
                        color = TimelineMuted,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                project.tracks.forEachIndexed { index, track ->
                    Column(
                        modifier =
                            Modifier
                                .height(trackHeight)
                                .fillMaxWidth()
                                .background(if (index % 2 == 0) TimelineTrack else TimelineTrackAlt)
                                .padding(start = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = track.name,
                            color = TimelineText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = track.type,
                            color = TimelineMuted,
                            fontSize = 8.sp,
                        )
                    }
                }
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(scrollState),
            ) {
                TimelineCanvas(
                    project = project,
                    playheadUs = playheadUs,
                    onPlayheadChange = onPlayheadChange,
                    selectedClipId = selectedClipId,
                    onSelectClip = onSelectClip,
                    onTrimClip = onTrimClip,
                    pxPerSecond = pxPerSecond,
                    width = timelineWidth,
                    rulerHeight = rulerHeight,
                    trackHeight = trackHeight,
                )
            }
        }
    }
}

@Composable
private fun TimelineCanvas(
    project: TimelineProjectUi,
    playheadUs: Long,
    onPlayheadChange: (Long) -> Unit,
    selectedClipId: String?,
    onSelectClip: (String?) -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    pxPerSecond: Float,
    width: Dp,
    rulerHeight: Dp,
    trackHeight: Dp,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val durationUs = project.durationUs.coerceAtLeast(1_000_000L)
    val widthPx = with(density) { width.toPx() }
    val rulerPx = with(density) { rulerHeight.toPx() }
    val trackPx = with(density) { trackHeight.toPx() }
    val edgeHitPx = with(density) { TRIM_EDGE_HIT_DP.dp.toPx() }
    val totalHeight = rulerPx + trackPx * project.tracks.size
    val currentProject by rememberUpdatedState(project)
    val currentOnSelectClip by rememberUpdatedState(onSelectClip)
    val currentOnPlayheadChange by rememberUpdatedState(onPlayheadChange)
    val currentOnTrimClip by rememberUpdatedState(onTrimClip)
    var trimHit by remember { mutableStateOf<ClipEdgeHit?>(null) }
    var trimStartUs by remember { mutableLongStateOf(0L) }
    var trimEndUs by remember { mutableLongStateOf(0L) }
    var trimDeltaUs by remember { mutableLongStateOf(0L) }

    Canvas(
        modifier =
            Modifier
                .width(width)
                .height(with(density) { totalHeight.toDp() })
                .pointerInput(durationUs, widthPx) {
                    detectTapGestures { offset ->
                        val hit =
                            hitTestClip(
                                x = offset.x,
                                y = offset.y,
                                project = currentProject,
                                widthPx = widthPx,
                                rulerPx = rulerPx,
                                trackPx = trackPx,
                                durationUs = durationUs,
                            )
                        if (hit != null) {
                            currentOnSelectClip(hit.id)
                        } else {
                            currentOnSelectClip(null)
                            currentOnPlayheadChange(xToUs(offset.x, widthPx, durationUs))
                        }
                    }
                }.pointerInput(durationUs, widthPx, edgeHitPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val hit =
                                hitTestClipEdge(
                                    x = offset.x,
                                    y = offset.y,
                                    project = currentProject,
                                    widthPx = widthPx,
                                    rulerPx = rulerPx,
                                    trackPx = trackPx,
                                    durationUs = durationUs,
                                    edgeHitPx = edgeHitPx,
                                )
                            if (hit != null) {
                                trimHit = hit
                                trimStartUs = hit.clip.startUs
                                trimEndUs = hit.clip.startUs + hit.clip.durationUs
                                trimDeltaUs = 0L
                                currentOnSelectClip(hit.clip.id)
                            } else {
                                trimHit = null
                            }
                        },
                        onDragEnd = { trimHit = null },
                        onDragCancel = { trimHit = null },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val hit = trimHit
                            if (hit != null) {
                                trimDeltaUs += (dragAmount.x / widthPx * durationUs.toFloat()).toLong()
                                val (newStart, newEnd) =
                                    trimRange(
                                        startUs = trimStartUs,
                                        endUs = trimEndUs,
                                        deltaUs = trimDeltaUs,
                                        edge = hit.edge,
                                    )
                                currentOnTrimClip(hit.clip.id, newStart, newEnd)
                            } else {
                                currentOnPlayheadChange(xToUs(change.position.x, widthPx, durationUs))
                            }
                        },
                    )
                },
    ) {
        drawRect(TimelineTrack, size = Size(size.width, rulerPx))
        val seconds = (durationUs / 1_000_000L).toInt().coerceAtLeast(1)
        for (second in 0..seconds) {
            val x = (second.toFloat() / seconds.toFloat()) * size.width
            drawLine(TimelineGrid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            if (second % 5 == 0) {
                val layout =
                    textMeasurer.measure(
                        text = "${second}s",
                        style = TextStyle(color = TimelineMuted, fontSize = 8.sp),
                    )
                drawText(layout, topLeft = Offset(x + 3f, 4f))
            }
        }
        project.tracks.forEachIndexed { index, track ->
            val top = rulerPx + index * trackPx
            drawRect(
                color = if (index % 2 == 0) TimelineTrack else TimelineTrackAlt,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackPx),
            )
            track.clips.forEach { clip ->
                val left = (clip.startUs.toFloat() / durationUs.toFloat()) * size.width
                val right = ((clip.startUs + clip.durationUs).toFloat() / durationUs.toFloat()) * size.width
                val rectTop = top + 4f
                val rectBottom = top + trackPx - 4f
                drawRoundRect(
                    color = clip.color,
                    topLeft = Offset(left + 1f, rectTop),
                    size = Size((right - left - 2f).coerceAtLeast(2f), rectBottom - rectTop),
                    cornerRadius = CornerRadius(6f, 6f),
                )
                if (clip.id == selectedClipId) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(left + 1f, rectTop),
                        size = Size((right - left - 2f).coerceAtLeast(2f), rectBottom - rectTop),
                        cornerRadius = CornerRadius(6f, 6f),
                        style = Stroke(width = 2f),
                    )
                    val handleWidth = 4.dp.toPx()
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(left + 1f, rectTop),
                        size = Size(handleWidth, rectBottom - rectTop),
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(right - 1f - handleWidth, rectTop),
                        size = Size(handleWidth, rectBottom - rectTop),
                    )
                }
                val layout =
                    textMeasurer.measure(
                        text = clip.label,
                        style = TextStyle(color = Color.White, fontSize = 9.sp),
                    )
                drawText(layout, topLeft = Offset(left + 6f, rectTop + 4f))
            }
        }
        val playheadX = (playheadUs.toFloat() / durationUs.toFloat()) * size.width
        drawLine(TimelineAccent, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2f)
        drawCircle(TimelineAccent, radius = 5f, center = Offset(playheadX, 2f))
    }
}

private const val TRIM_STEP_US = 500_000L

private fun formatTime(us: Long): String {
    val totalMs = (us / 1_000L).coerceAtLeast(0L)
    val minutes = totalMs / 60_000L
    val seconds = (totalMs / 1_000L) % 60L
    val millis = totalMs % 1_000L
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}
