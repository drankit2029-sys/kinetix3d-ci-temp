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
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetix3d.model.ClipNode
import com.kinetix3d.model.CompositionGraph
import com.kinetix3d.model.PolygonClipPayload
import com.kinetix3d.model.PreCompClipPayload
import com.kinetix3d.model.TextClipPayload
import com.kinetix3d.model.TimelineMarker
import com.kinetix3d.model.VideoClipPayload
import com.kinetix3d.render.playback.PlaybackClock
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
    val isVideo: Boolean = false,
    val sourceInUs: Long = 0L,
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
    val fps: Double = 60.0,
)

fun demoTimelineProject(): TimelineProjectUi {
    val second = 1_000_000L
    return TimelineProjectUi(
        name = "Demo Edit",
        durationUs = 24 * second,
        fps = 60.0,
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
                            TimelineClipUi("v1a", "Winter Olympics", 0L, 8 * second, ClipColors[0], isVideo = true),
                            TimelineClipUi("v1b", "B-Roll", 8 * second, 6 * second, ClipColors[1], isVideo = true),
                            TimelineClipUi("v1c", "Interview", 14 * second, 10 * second, ClipColors[0], isVideo = true),
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
                            isVideo = clip.payload is VideoClipPayload,
                            sourceInUs = clip.sourceInUs,
                        )
                    }
            TimelineTrackUi(
                name = track.trackId,
                type = track.type.name,
                clips = clipsUi,
            )
        }
    return TimelineProjectUi(
        name = name,
        durationUs = duration,
        tracks = tracksUi,
        fps = settings.fps,
    )
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
    inPointUs: Long = 0L,
    outPointUs: Long = 0L,
    onPlayheadChange: (Long) -> Unit,
    isPlaying: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    muted: Boolean = false,
    onMutedChange: (Boolean) -> Unit = {},
    selectedClipId: String? = null,
    onSelectClip: (String?) -> Unit = {},
    onTrimClip: (String, Long, Long) -> Unit = { _, _, _ -> },
    onTrimStart: () -> Unit = {},
    onTrimEnd: () -> Unit = {},
    onRippleTrimClip: (String, Long) -> Unit = { _, _ -> },
    onSlipClip: (String, Long) -> Unit = { _, _ -> },
    onSlideClip: (String, Long) -> Unit = { _, _ -> },
    onSplitClip: (String) -> Unit = {},
    onDeleteClip: (String) -> Unit = {},
    onDetachAudio: (String) -> Unit = {},
    onMoveClipStart: (String) -> Unit = {},
    onMoveClip: (String, Long) -> Unit = { _, _ -> },
    onMoveClipEnd: (String, Long) -> Unit = { _, _ -> },
    thumbnails: Map<String, ImageBitmap> = emptyMap(),
    waveforms: Map<String, FloatArray> = emptyMap(),
    markers: List<TimelineMarker> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var pxPerSecond by remember { mutableFloatStateOf(72f) }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val currentPlayhead by rememberUpdatedState(playheadUs)
    val currentOnPlayheadChange by rememberUpdatedState(onPlayheadChange)
    val currentOnPlayingChange by rememberUpdatedState(onPlayingChange)
    val clock = remember { PlaybackClock(project.durationUs, project.fps) }
    clock.durationUs = project.durationUs
    clock.frameRate = project.fps
    val durationUs = project.durationUs.coerceAtLeast(1_000_000L)
    val timelineWidth = ((durationUs / 1_000_000f) * pxPerSecond).dp
    val timelineWidthPx = with(density) { timelineWidth.toPx() }
    val viewportWidthPx = with(density) { (screenWidthDp - 76).coerceAtLeast(120).dp.toPx() }
    val scrollPx = scrollState.value.toFloat()
    val visibleStartUs =
        if (timelineWidthPx > 0f) (scrollPx / timelineWidthPx * durationUs).toLong() else 0L
    val visibleEndUs =
        if (timelineWidthPx > 0f) {
            ((scrollPx + viewportWidthPx) / timelineWidthPx * durationUs).toLong()
        } else {
            durationUs
        }
    val trackHeight = 44.dp
    val rulerHeight = 28.dp
    val headerWidth = 76.dp
    val selectedClip =
        project.tracks
            .asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == selectedClipId }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            clock.pause(System.nanoTime())
            return@LaunchedEffect
        }
        val startNanos = System.nanoTime()
        clock.seekTo(currentPlayhead, startNanos)
        clock.play(startNanos)
        while (isPlaying) {
            withFrameNanos { frameNanos ->
                val next = clock.framePtsUsAt(frameNanos)
                currentOnPlayheadChange(next)
                if (!clock.isPlaying) currentOnPlayingChange(false)
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
            Button(
                onClick = { onPlayingChange(!isPlaying) },
                modifier = Modifier.height(40.dp),
            ) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            TextButton(onClick = { onMutedChange(!muted) }) {
                Text(if (muted) "Unmute" else "Mute", color = TimelineText)
            }
            TextButton(onClick = { pxPerSecond = (pxPerSecond - 16f).coerceAtLeast(32f) }) {
                Text("−", color = TimelineText)
            }
            TextButton(onClick = { pxPerSecond = (pxPerSecond + 16f).coerceAtMost(160f) }) {
                Text("+", color = TimelineText)
            }
            TextButton(
                onClick = {
                    val availableDp = (screenWidthDp - 76 - 16).coerceAtLeast(120)
                    pxPerSecond =
                        (availableDp / (durationUs / 1_000_000f)).coerceIn(32f, 160f)
                },
            ) {
                Text("Fit", color = TimelineText)
            }
        }
        if (selectedClip != null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = "${selectedClip.label} · ${formatTime(selectedClip.durationUs)}",
                    color = TimelineAccent,
                    fontSize = 11.sp,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { onSplitClip(selectedClip.id) }) {
                        Text("Split", fontSize = 10.sp)
                    }
                    TextButton(onClick = { onDeleteClip(selectedClip.id) }) {
                        Text("Delete", fontSize = 10.sp)
                    }
                    if (selectedClip.isVideo) {
                        TextButton(onClick = { onDetachAudio(selectedClip.id) }) {
                            Text("Detach Audio", fontSize = 10.sp)
                        }
                    }
                    TextButton(
                        onClick = {
                            onTrimStart()
                            onTrimClip(
                                selectedClip.id,
                                selectedClip.startUs + TRIM_STEP_US,
                                selectedClip.startUs + selectedClip.durationUs,
                            )
                            onTrimEnd()
                        },
                    ) {
                        Text("Trim In +0.5s", fontSize = 10.sp)
                    }
                    TextButton(
                        onClick = {
                            onTrimStart()
                            onTrimClip(
                                selectedClip.id,
                                selectedClip.startUs,
                                selectedClip.startUs + selectedClip.durationUs - TRIM_STEP_US,
                            )
                            onTrimEnd()
                        },
                    ) {
                        Text("Trim Out −0.5s", fontSize = 10.sp)
                    }
                    TextButton(
                        onClick = {
                            onRippleTrimClip(
                                selectedClip.id,
                                selectedClip.startUs + selectedClip.durationUs - TRIM_STEP_US,
                            )
                        },
                    ) {
                        Text("Ripple Out −0.5s", fontSize = 10.sp)
                    }
                    TextButton(onClick = { onSlipClip(selectedClip.id, -TRIM_STEP_US) }) {
                        Text("Slip −0.5s", fontSize = 10.sp)
                    }
                    TextButton(onClick = { onSlipClip(selectedClip.id, TRIM_STEP_US) }) {
                        Text("Slip +0.5s", fontSize = 10.sp)
                    }
                    TextButton(onClick = { onSlideClip(selectedClip.id, -TRIM_STEP_US) }) {
                        Text("Slide −0.5s", fontSize = 10.sp)
                    }
                    TextButton(onClick = { onSlideClip(selectedClip.id, TRIM_STEP_US) }) {
                        Text("Slide +0.5s", fontSize = 10.sp)
                    }
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
                    inPointUs = inPointUs,
                    outPointUs = outPointUs,
                    onPlayheadChange = { pts ->
                        clock.seekTo(pts, System.nanoTime())
                        onPlayheadChange(pts)
                    },
                    selectedClipId = selectedClipId,
                    onSelectClip = onSelectClip,
                    onTrimClip = onTrimClip,
                    onTrimStart = onTrimStart,
                    onTrimEnd = onTrimEnd,
                    visibleStartUs = visibleStartUs,
                    visibleEndUs = visibleEndUs,
                    onMoveClipStart = onMoveClipStart,
                    onMoveClip = onMoveClip,
                    onMoveClipEnd = onMoveClipEnd,
                    thumbnails = thumbnails,
                    waveforms = waveforms,
                    markers = markers,
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
    inPointUs: Long,
    outPointUs: Long,
    onPlayheadChange: (Long) -> Unit,
    selectedClipId: String?,
    onSelectClip: (String?) -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onTrimStart: () -> Unit,
    onTrimEnd: () -> Unit,
    visibleStartUs: Long,
    visibleEndUs: Long,
    onMoveClipStart: (String) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onMoveClipEnd: (String, Long) -> Unit,
    thumbnails: Map<String, ImageBitmap>,
    waveforms: Map<String, FloatArray>,
    markers: List<TimelineMarker>,
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
    val currentSelectedClipId by rememberUpdatedState(selectedClipId)
    val currentOnSelectClip by rememberUpdatedState(onSelectClip)
    val currentOnPlayheadChange by rememberUpdatedState(onPlayheadChange)
    val currentOnTrimClip by rememberUpdatedState(onTrimClip)
    val currentOnTrimStart by rememberUpdatedState(onTrimStart)
    val currentOnTrimEnd by rememberUpdatedState(onTrimEnd)
    val currentOnMoveClipStart by rememberUpdatedState(onMoveClipStart)
    val currentOnMoveClip by rememberUpdatedState(onMoveClip)
    val currentOnMoveClipEnd by rememberUpdatedState(onMoveClipEnd)
    var trimHit by remember { mutableStateOf<ClipEdgeHit?>(null) }
    var trimStartUs by remember { mutableLongStateOf(0L) }
    var trimEndUs by remember { mutableLongStateOf(0L) }
    var trimDeltaUs by remember { mutableLongStateOf(0L) }
    var moveClip by remember { mutableStateOf<TimelineClipUi?>(null) }
    var moveStartUs by remember { mutableLongStateOf(0L) }
    var moveDeltaUs by remember { mutableLongStateOf(0L) }

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
                            currentOnSelectClip(if (hit.id == currentSelectedClipId) null else hit.id)
                        } else {
                            // Seeking must not clear the selection: Split/Delete act on it.
                            currentOnPlayheadChange(xToUs(offset.x, widthPx, durationUs))
                        }
                    }
                }.pointerInput(durationUs, widthPx, edgeHitPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val edgeHit =
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
                            if (edgeHit != null) {
                                trimHit = edgeHit
                                trimStartUs = edgeHit.clip.startUs
                                trimEndUs = edgeHit.clip.startUs + edgeHit.clip.durationUs
                                trimDeltaUs = 0L
                                moveClip = null
                                currentOnSelectClip(edgeHit.clip.id)
                                currentOnTrimStart()
                            } else {
                                trimHit = null
                                val bodyHit =
                                    hitTestClip(
                                        x = offset.x,
                                        y = offset.y,
                                        project = currentProject,
                                        widthPx = widthPx,
                                        rulerPx = rulerPx,
                                        trackPx = trackPx,
                                        durationUs = durationUs,
                                    )
                                if (bodyHit != null) {
                                    moveClip = bodyHit
                                    moveStartUs = bodyHit.startUs
                                    moveDeltaUs = 0L
                                    currentOnSelectClip(bodyHit.id)
                                    currentOnMoveClipStart(bodyHit.id)
                                } else {
                                    moveClip = null
                                }
                            }
                        },
                        onDragEnd = {
                            val moving = moveClip
                            if (moving != null) {
                                currentOnMoveClipEnd(moving.id, (moveStartUs + moveDeltaUs).coerceAtLeast(0L))
                            }
                            if (trimHit != null) currentOnTrimEnd()
                            trimHit = null
                            moveClip = null
                        },
                        onDragCancel = {
                            if (trimHit != null) currentOnTrimEnd()
                            trimHit = null
                            moveClip = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val hit = trimHit
                            val moving = moveClip
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
                            } else if (moving != null) {
                                moveDeltaUs += (dragAmount.x / widthPx * durationUs.toFloat()).toLong()
                                currentOnMoveClip(moving.id, (moveStartUs + moveDeltaUs).coerceAtLeast(0L))
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
            track.clips
                .filter { it.startUs + it.durationUs >= visibleStartUs && it.startUs <= visibleEndUs }
                .forEach { clip ->
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
                    val thumb = thumbnails[clip.id]
                    if (thumb != null) {
                        val clipWidth = (right - left - 2f).coerceAtLeast(2f)
                        val clipHeight = (rectBottom - rectTop).coerceAtLeast(1f)
                        val thumbWidth =
                            (clipHeight * thumb.width / thumb.height)
                                .coerceAtMost(clipWidth)
                                .coerceAtLeast(1f)
                        drawImage(
                            image = thumb,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(thumb.width, thumb.height),
                            dstOffset = IntOffset((left + 1f).toInt(), rectTop.toInt()),
                            dstSize = IntSize(thumbWidth.toInt(), clipHeight.toInt()),
                            alpha = 0.55f,
                        )
                    }
                    val wave = waveforms[clip.id]
                    if (wave != null && !clip.isVideo) {
                        val centerY = (rectTop + rectBottom) / 2f
                        val maxHeight = (rectBottom - rectTop) * 0.8f
                        val step = (right - left - 2f) / wave.size
                        wave.forEachIndexed { index, peak ->
                            val x = left + 1f + index * step
                            val half = (peak * maxHeight / 2f).coerceAtLeast(0.5f)
                            drawLine(
                                color = Color(0xCCFFFFFF),
                                start = Offset(x, centerY - half),
                                end = Offset(x, centerY + half),
                                strokeWidth = 1.5f,
                            )
                        }
                    }
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
                    drawRect(
                        color = Color(0x99000000),
                        topLeft = Offset(left + 4f, rectTop + 2f),
                        size = Size(layout.size.width + 8f, layout.size.height + 4f),
                    )
                    drawText(layout, topLeft = Offset(left + 6f, rectTop + 4f))
                }
        }
        markers.forEach { marker ->
            val x = (marker.ptsUs.toFloat() / durationUs.toFloat()) * size.width
            drawLine(Color(0xFFFFB454), Offset(x, 0f), Offset(x, rulerPx), strokeWidth = 2f)
            drawCircle(Color(0xFFFFB454), radius = 3f, center = Offset(x, 2f))
        }
        if (outPointUs > inPointUs) {
            val inX = (inPointUs.toFloat() / durationUs.toFloat()) * size.width
            val outX = (outPointUs.toFloat() / durationUs.toFloat()) * size.width
            drawRect(
                color = TimelineAccent.copy(alpha = 0.12f),
                topLeft = Offset(inX, 0f),
                size = Size((outX - inX).coerceAtLeast(1f), size.height),
            )
            drawLine(TimelineAccent, Offset(inX, 0f), Offset(inX, size.height), strokeWidth = 2f)
            drawLine(TimelineAccent, Offset(outX, 0f), Offset(outX, size.height), strokeWidth = 2f)
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
