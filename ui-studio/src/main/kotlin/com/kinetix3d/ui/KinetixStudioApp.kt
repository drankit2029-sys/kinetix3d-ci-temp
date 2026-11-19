/*
 * Kinetix3D Studio — Day 1 shell.
 * Placeholder studio screen that proves the full module graph compiles and links:
 * app -> ui-studio -> (core-model, core-evaluator, core-platform-api,
 *                      engine-media, engine-render, engine-ai)
 * It also surfaces the live DeviceCapabilityProfile and DirtyRenderLock crash
 * guard state owned by :app's KinetixApplication.
 */

package com.kinetix3d.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetix3d.media.DecoderPoolManager
import com.kinetix3d.media.KinetixAudioNative
import com.kinetix3d.media.ScheduledClip
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.DirtyRenderLock
import com.kinetix3d.model.HardwareTier
import com.kinetix3d.model.ProjectEditor
import com.kinetix3d.model.ProjectState
import com.kinetix3d.platform.api.PlatformApi
import com.kinetix3d.platform.project.ProjectStore
import com.kinetix3d.render.KinetixRenderView
import com.kinetix3d.render.RenderStats
import com.kinetix3d.render.demoClipGraph
import com.kinetix3d.render.demoEffectsGraph
import com.kinetix3d.render.demoKernelGraph
import com.kinetix3d.render.export.HardwareExportSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import com.kinetix3d.ai.ModuleInfo as EngineAi
import com.kinetix3d.evaluator.ModuleInfo as CoreEvaluator
import com.kinetix3d.media.ModuleInfo as EngineMedia
import com.kinetix3d.model.ModuleInfo as CoreModel
import com.kinetix3d.platform.ModuleInfo as CorePlatformApi
import com.kinetix3d.render.ModuleInfo as EngineRender

private val StudioBackground = Color(0xFF10141B)
private val StudioAccent = Color(0xFF38E1C6)
private val StudioMuted = Color(0xFF8A94A3)
private val StudioText = Color(0xFFE7ECF3)
private val StudioWarning = Color(0xFFFFB454)

private const val EXPORT_DEMO_US = 3_000_000L
private const val KEY_SEEK_STEP_US = 500_000L
private const val UNDO_LIMIT = 50

private fun formatSeconds(us: Long): String = String.format(Locale.US, "%.1fs", us / 1_000_000.0)

/** Demo media lookup exposed to :app without leaking the engine-render dependency. */
fun demoMediaPath(context: Context): String? = KinetixRenderView.findTestMedia(context)

private fun probeRustAbi(): String =
    try {
        "abi v${KinetixAudioNative.abiVersion()}"
    } catch (t: Throwable) {
        "unavailable (${t.javaClass.simpleName})"
    }

private data class StudioModule(
    val name: String,
    val description: String,
)

private val studioModules =
    listOf(
        StudioModule(CorePlatformApi.NAME, "Version-adaptive OS APIs · first-boot profiler"),
        StudioModule(CoreModel.NAME, "ProjectState · NodeGraph · SQLite WAL"),
        StudioModule(CoreEvaluator.NAME, "Bezier/spring solvers · ArcLengthLUT · expressions"),
        StudioModule(EngineMedia.NAME, "Decoder pool · All-I proxy worker · Rust audio (cpal/AAudio)"),
        StudioModule(EngineRender.NAME, "GLES 3.2 DAG · DoD FBO pool · tile-bucket render"),
        StudioModule(EngineAi.NAME, "Offline NPU AI: SAM 2 · DepthAnything · Whisper"),
        StudioModule(ModuleInfo.NAME, "Adaptive studio UI · timeline · OmniTrackpad"),
    )

@Composable
fun KinetixStudioApp(
    deviceProfile: DeviceCapabilityProfile? = null,
    renderGuard: DirtyRenderLock.Inspection? = null,
    projectStore: ProjectStore? = null,
    platformApi: PlatformApi? = null,
    mediaPath: String? = null,
) {
    var playheadUs by remember { mutableLongStateOf(0L) }
    var demoState by remember { mutableStateOf(demoProjectState()) }
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    var selectedEffects by remember { mutableStateOf<List<String>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var persistenceStatus by remember { mutableStateOf("in-memory demo") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var renderStats by remember { mutableStateOf(RenderStats()) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var inPointUs by remember { mutableLongStateOf(0L) }
    var outPointUs by remember { mutableLongStateOf(0L) }
    var renderView by remember { mutableStateOf<KinetixRenderView?>(null) }
    val exportProgress = remember { MutableStateFlow(0f) }
    val progress by exportProgress.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kinetix", Context.MODE_PRIVATE) }
    var activeMediaPath by remember { mutableStateOf(prefs.getString("source_path", null) ?: mediaPath) }
    val scope = rememberCoroutineScope()
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val imported =
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val mime = context.contentResolver.getType(uri).orEmpty()
                                val extension =
                                    when {
                                        mime.contains("png") -> "png"
                                        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                                        mime.contains("quicktime") -> "mov"
                                        else -> "mp4"
                                    }
                                val target =
                                    File(
                                        context.getExternalFilesDir(null),
                                        "kinetix-import-${System.currentTimeMillis()}.$extension",
                                    )
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    target.outputStream().use { output -> input.copyTo(output) }
                                } ?: error("Unable to open the selected file")
                                target.absolutePath
                            }
                        }
                    imported
                        .onSuccess { path ->
                            activeMediaPath = path
                            prefs.edit().putString("source_path", path).apply()
                            renderView?.setSourcePath(path)
                            importStatus = "imported"
                        }.onFailure { error ->
                            importStatus = "import failed: ${error.message}"
                        }
                }
            }
        }
    val demoGraph =
        remember(selectedEffects) {
            when {
                selectedEffects.isEmpty() -> demoClipGraph()
                selectedEffects == listOf("color") -> demoEffectsGraph()
                else -> demoKernelGraph(selectedEffects)
            }
        }
    val effectLabel =
        when {
            selectedEffects.isEmpty() -> "Clean"
            selectedEffects == listOf("color") -> "ColorSuite → Blur → Glow"
            else -> selectedEffects.joinToString(" → ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    val timelineProject = remember(demoState) { demoState.activeComposition().toTimelineProjectUi() }
    val hasRange = outPointUs > inPointUs
    val rangeStartUs = if (hasRange) inPointUs else 0L
    val rangeEndUs =
        if (hasRange) {
            outPointUs
        } else {
            minOf(demoState.activeComposition().durationUs(), EXPORT_DEMO_US)
        }
    val rangeDurationUs = (rangeEndUs - rangeStartUs).coerceAtLeast(1L)
    val scheduledClips =
        remember(timelineProject, activeMediaPath) {
            timelineProject.tracks
                .mapIndexedNotNull { index, track ->
                    if (track.type != "VIDEO") {
                        null
                    } else {
                        track.clips.map { clip ->
                            ScheduledClip(
                                clipId = clip.id,
                                trackId = track.name,
                                startUs = clip.startUs,
                                endUs = clip.startUs + clip.durationUs,
                                sourcePath = activeMediaPath.orEmpty(),
                                priority = timelineProject.tracks.size - index,
                            )
                        }
                    }
                }.flatten()
        }
    val activeClip =
        remember(scheduledClips, playheadUs) {
            DecoderPoolManager(maxDecoders = 4).activeClip(playheadUs, scheduledClips)
        }
    val focusRequester = remember { FocusRequester() }
    val undoStack = remember { mutableStateListOf<ProjectState>() }
    val redoStack = remember { mutableStateListOf<ProjectState>() }
    val applyEdit: (ProjectState) -> Unit = { updated ->
        if (updated != demoState) {
            undoStack.add(demoState)
            if (undoStack.size > UNDO_LIMIT) undoStack.removeAt(0)
            redoStack.clear()
            demoState = updated
        }
    }
    val splitClip: (String) -> Unit = { id ->
        val before = demoState
        val updated = ProjectEditor.splitClip(before, id, playheadUs)
        if (updated != before) {
            val newId =
                updated
                    .activeComposition()
                    .clips.keys
                    .firstOrNull { it !in before.activeComposition().clips }
            val left = updated.activeComposition().clips[id]
            val right = newId?.let { updated.activeComposition().clips[it] }
            applyEdit(updated)
            selectedClipId = newId ?: id
            val store = projectStore
            if (left != null && right != null && store != null) {
                scope.launch {
                    runCatching { store.recordClipSplit(updated, left, right) }
                        .onSuccess { persistenceStatus = "saved" }
                        .onFailure { persistenceStatus = "save failed" }
                }
            }
        }
    }
    val splitAll: () -> Unit = {
        val updated = ProjectEditor.splitAll(demoState, playheadUs)
        if (updated != demoState) {
            applyEdit(updated)
            selectedClipId = null
            val store = projectStore
            if (store != null) {
                scope.launch {
                    runCatching { store.recordSnapshot(updated) }
                        .onSuccess { persistenceStatus = "split all saved" }
                        .onFailure { persistenceStatus = "save failed" }
                }
            }
        }
    }
    val rippleTrim: (String) -> Unit = { id ->
        val clip = timelineProject.tracks.flatMap { it.clips }.firstOrNull { it.id == id }
        if (clip != null) {
            val updated =
                ProjectEditor.rippleTrimOut(
                    demoState,
                    id,
                    clip.startUs + clip.durationUs - KEY_SEEK_STEP_US,
                )
            if (updated != demoState) {
                applyEdit(updated)
                val store = projectStore
                if (store != null) {
                    scope.launch {
                        runCatching { store.recordSnapshot(updated) }
                            .onSuccess { persistenceStatus = "ripple saved" }
                            .onFailure { persistenceStatus = "save failed" }
                    }
                }
            }
        }
    }
    val detachAudio: (String) -> Unit = { id ->
        val updated = ProjectEditor.detachAudio(demoState, id)
        if (updated != demoState) {
            applyEdit(updated)
            val store = projectStore
            if (store != null) {
                scope.launch {
                    runCatching { store.recordSnapshot(updated) }
                        .onSuccess { persistenceStatus = "audio detached" }
                        .onFailure { persistenceStatus = "save failed" }
                }
            }
        }
    }
    val deleteClip: (String) -> Unit = { id ->
        val updated = ProjectEditor.removeClip(demoState, id)
        if (updated != demoState) {
            applyEdit(updated)
            selectedClipId = null
            val store = projectStore
            if (store != null) {
                scope.launch {
                    runCatching { store.recordClipDelete(updated, id) }
                        .onSuccess { persistenceStatus = "saved" }
                        .onFailure { persistenceStatus = "save failed" }
                }
            }
        }
    }
    val cycleClip: () -> Unit = {
        val ids = timelineProject.tracks.flatMap { it.clips }.map { it.id }
        if (ids.isNotEmpty()) {
            val index = ids.indexOf(selectedClipId)
            selectedClipId = ids[(index + 1) % ids.size]
        }
    }
    val undo: () -> Unit = {
        val previous = undoStack.removeLastOrNull()
        if (previous != null) {
            redoStack.add(demoState)
            demoState = previous
            selectedClipId = null
            val store = projectStore
            if (store != null) {
                scope.launch {
                    runCatching { store.recordSnapshot(previous) }
                        .onSuccess { persistenceStatus = "undo saved" }
                        .onFailure { persistenceStatus = "save failed" }
                }
            }
        }
    }
    val redo: () -> Unit = {
        val next = redoStack.removeLastOrNull()
        if (next != null) {
            undoStack.add(demoState)
            demoState = next
            selectedClipId = null
            val store = projectStore
            if (store != null) {
                scope.launch {
                    runCatching { store.recordSnapshot(next) }
                        .onSuccess { persistenceStatus = "redo saved" }
                        .onFailure { persistenceStatus = "save failed" }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(activeClip?.clipId) {
        val path = activeClip?.sourcePath
        if (!path.isNullOrBlank()) renderView?.setSourcePath(path)
    }

    LaunchedEffect(projectStore) {
        val store = projectStore ?: return@LaunchedEffect
        val loaded = runCatching { store.load() }.getOrNull()
        if (loaded == null) {
            runCatching { store.save(demoState) }
            persistenceStatus = "new project saved"
        } else {
            val recovered = runCatching { store.recover(loaded) }.getOrDefault(loaded)
            demoState = recovered
            persistenceStatus = "restored ${recovered.projectId}"
        }
    }

    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = StudioBackground,
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.isCtrlPressed && event.key == Key.Z) {
                                if (event.isShiftPressed) redo() else undo()
                                return@onPreviewKeyEvent true
                            }
                            when (event.key) {
                                Key.C -> {
                                    cycleClip()
                                    true
                                }

                                Key.S -> {
                                    if (event.isShiftPressed) splitAll() else selectedClipId?.let(splitClip)
                                    true
                                }

                                Key.R -> {
                                    selectedClipId?.let(rippleTrim)
                                    true
                                }

                                Key.D -> {
                                    selectedClipId?.let(detachAudio)
                                    true
                                }

                                Key.I -> {
                                    inPointUs = playheadUs
                                    true
                                }

                                Key.O -> {
                                    outPointUs = playheadUs
                                    true
                                }

                                Key.Delete, Key.Backspace -> {
                                    selectedClipId?.let(deleteClip)
                                    true
                                }

                                Key.Spacebar -> {
                                    isPlaying = !isPlaying
                                    true
                                }

                                Key.MoveHome -> {
                                    playheadUs = 0L
                                    true
                                }

                                Key.DirectionLeft -> {
                                    playheadUs = (playheadUs - KEY_SEEK_STEP_US).coerceAtLeast(0L)
                                    true
                                }

                                Key.DirectionRight -> {
                                    playheadUs =
                                        (playheadUs + KEY_SEEK_STEP_US)
                                            .coerceAtMost(timelineProject.durationUs)
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        }.verticalScroll(rememberScrollState())
                        .padding(innerPadding)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Kinetix3D Studio",
                    color = StudioText,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Day 1 — multi-module foundation",
                    color = StudioAccent,
                    fontSize = 14.sp,
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp)),
                ) {
                    AndroidView(
                        factory = { context ->
                            KinetixRenderView(context).also { view ->
                                view.onStats = { stats -> renderStats = stats }
                                renderView = view
                                view.setSourcePath(activeMediaPath)
                            }
                        },
                        update = { view ->
                            view.setDeviceProfile(deviceProfile ?: DeviceCapabilityProfile.tier2Default())
                            view.setProjectState(demoState, demoGraph)
                            view.setPlaying(isPlaying)
                            view.setPlayheadUs(playheadUs)
                            view.setSourcePath(activeMediaPath)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = "4K SOURCE — 960p PREVIEW · $effectLabel",
                    color = StudioMuted,
                    fontSize = 12.sp,
                )
                Text(
                    text =
                        "preview ${renderStats.fps.roundToInt()} fps · " +
                            "${String.format(Locale.US, "%.1f", renderStats.frameMillis)} ms · " +
                            "${renderStats.tiles} tiles ${renderStats.format}",
                    color = StudioMuted,
                    fontSize = 11.sp,
                )
                Text(
                    text =
                        "source: ${activeMediaPath?.let { File(it).name } ?: "procedural"}" +
                            " · clip: ${activeClip?.clipId ?: "none"}" +
                            (importStatus?.let { " · $it" } ?: ""),
                    color = StudioMuted,
                    fontSize = 11.sp,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedEffects.isEmpty(),
                        onClick = { selectedEffects = emptyList() },
                        label = { Text("Clean") },
                    )
                    FilterChip(
                        selected = selectedEffects == listOf("color"),
                        onClick = { selectedEffects = listOf("color") },
                        label = { Text("Color") },
                    )
                    listOf("warp", "glitch", "vectorify", "keyer").forEach { effect ->
                        FilterChip(
                            selected = effect in selectedEffects,
                            onClick = {
                                val base = selectedEffects.filter { it != "color" }
                                selectedEffects = if (effect in base) base - effect else base + effect
                            },
                            label = { Text(effect.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val api = platformApi
                            val path = activeMediaPath
                            if (api == null || path == null) {
                                exportStatus = "export unavailable (no media)"
                                return@Button
                            }
                            if (path.endsWith(".png", true) || path.endsWith(".jpg", true) ||
                                path.endsWith(".jpeg", true)
                            ) {
                                exportStatus = "export needs a video source"
                                return@Button
                            }
                            scope.launch {
                                exportStatus = "exporting…"
                                exportProgress.value = 0f
                                val result =
                                    runCatching {
                                        withContext(Dispatchers.Default) {
                                            val output =
                                                File(
                                                    context.cacheDir,
                                                    "kinetix-export-${System.currentTimeMillis()}.mp4",
                                                )
                                            HardwareExportSession(
                                                sourcePath = path,
                                                graph = demoGraph,
                                                comp = demoState.settings.copy(width = 1920, height = 1080),
                                                profile = deviceProfile ?: DeviceCapabilityProfile.tier2Default(),
                                                outputFile = output,
                                                durationUs = rangeEndUs,
                                                startUs = rangeStartUs,
                                            ).export { value -> exportProgress.value = value }
                                        }
                                    }
                                result
                                    .onSuccess { export ->
                                        val published =
                                            runCatching {
                                                api.storageAndExport.publishExportAtomically(
                                                    export.file,
                                                    export.file.name,
                                                    export.mimeType,
                                                )
                                            }
                                        exportStatus =
                                            published.fold(
                                                onSuccess = {
                                                    export.file.delete()
                                                    "exported ${export.frames} frames → $it"
                                                },
                                                onFailure = { "export saved, publish failed: ${it.message}" },
                                            )
                                    }.onFailure { error ->
                                        exportStatus = "export failed: ${error.message}"
                                    }
                            }
                        },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(
                            if (exportStatus == "exporting…") {
                                "Exporting…"
                            } else {
                                "Export ${String.format(Locale.US, "%.1fs", rangeDurationUs / 1_000_000.0)}"
                            },
                        )
                    }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("video/*", "image/*")) },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("Import")
                    }
                    Text(
                        text = exportStatus ?: "hardware H.264 · 1080p",
                        color = StudioMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (exportStatus == "exporting…") {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TimelinePanel(
                    project = timelineProject,
                    playheadUs = playheadUs,
                    inPointUs = inPointUs,
                    outPointUs = outPointUs,
                    onPlayheadChange = { playheadUs = it },
                    isPlaying = isPlaying,
                    onPlayingChange = { isPlaying = it },
                    selectedClipId = selectedClipId,
                    onSelectClip = { selectedClipId = it },
                    onTrimClip = { id, start, end ->
                        val updated = demoState.trimClip(id, start, end)
                        applyEdit(updated)
                        val clip = updated.activeComposition().clips[id]
                        val store = projectStore
                        if (clip != null && store != null) {
                            scope.launch {
                                runCatching { store.recordClipUpdate(updated, clip) }
                                    .onSuccess { persistenceStatus = "saved" }
                                    .onFailure { persistenceStatus = "save failed" }
                            }
                        }
                    },
                    onRippleTrimClip = { id, _ -> rippleTrim(id) },
                    onSplitClip = splitClip,
                    onDeleteClip = deleteClip,
                    onDetachAudio = detachAudio,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(12.dp)),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { inPointUs = playheadUs }) {
                        Text("Set In")
                    }
                    TextButton(onClick = { outPointUs = playheadUs }) {
                        Text("Set Out")
                    }
                    TextButton(onClick = splitAll) {
                        Text("Split All")
                    }
                    TextButton(onClick = undo, enabled = undoStack.isNotEmpty()) {
                        Text("Undo")
                    }
                    TextButton(onClick = redo, enabled = redoStack.isNotEmpty()) {
                        Text("Redo")
                    }
                    Text(
                        text =
                            "project: $persistenceStatus" +
                                if (hasRange) {
                                    " · range ${formatSeconds(inPointUs)}–${formatSeconds(outPointUs)}"
                                } else {
                                    ""
                                },
                        color = StudioMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                SectionLabel(
                    text = "DEVICE",
                    modifier = Modifier.padding(top = 8.dp),
                )
                ProfileSummary(deviceProfile)
                SectionLabel(
                    text = "SAFE RECOVERY",
                    modifier = Modifier.padding(top = 8.dp),
                )
                RecoverySummary(renderGuard)
                SectionLabel(
                    text = "MODULES",
                    modifier = Modifier.padding(top = 8.dp),
                )
                studioModules.forEach { module ->
                    ModuleRow(module)
                }
                Text(
                    text = "rust audio core: ${remember { probeRustAbi() }}",
                    color = StudioAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "minSdk 26 · targetSdk 37 · compileSdk 37 · RGBA16F pipeline",
                    color = StudioMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ProfileSummary(profile: DeviceCapabilityProfile?) {
    if (profile == null) {
        StatusLine("probing hardware…", StudioMuted)
        return
    }
    StatusLine(
        "${profile.tier.displayName()} · ${profile.safeVramBudgetMb} MB VRAM",
        StudioAccent,
    )
    StatusLine(profile.capabilityLine(), StudioText)
    profile.glRenderer?.let { renderer -> StatusLine("GL: $renderer", StudioMuted) }
    if (profile.approximate) {
        StatusLine("conservative estimate — first-boot probe not finalized", StudioWarning)
    }
}

@Composable
private fun RecoverySummary(inspection: DirtyRenderLock.Inspection?) {
    if (inspection == null) {
        StatusLine("checking render guard…", StudioMuted)
        return
    }
    when (inspection.state) {
        DirtyRenderLock.State.MISSING -> {
            StatusLine("render guard pristine (no frame written yet)", StudioMuted)
        }

        DirtyRenderLock.State.CLEAN -> {
            StatusLine("render guard clean · last swap completed", StudioAccent)
        }

        DirtyRenderLock.State.CORRUPT -> {
            StatusLine("torn guard record ignored · normal boot", StudioMuted)
        }

        DirtyRenderLock.State.ACTIVE_RECORD -> {
            StatusLine(
                "SAFE RECOVERY ARMED · interrupted frame ${inspection.generation} " +
                    "(node ${inspection.nodeIdHash.toString(16)})",
                StudioWarning,
            )
        }
    }
}

@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = StudioMuted,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

@Composable
private fun StatusLine(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
}

private fun HardwareTier.displayName(): String =
    when (this) {
        HardwareTier.TIER_1 -> "TIER 1 · flagship"
        HardwareTier.TIER_2 -> "TIER 2 · mainstream"
        HardwareTier.TIER_3 -> "TIER 3 · low-RAM"
    }

private fun DeviceCapabilityProfile.capabilityLine(): String =
    buildString {
        append(maxHwDecoders)
        append(" hw decoders · ")
        append(if (supportsRgba16f) "RGBA16F" else "RGBA8 only")
        append(if (supportsAhbZeroCopy) " · AHB zero-copy" else " · FBO-copy fallback")
        append(" · max ")
        append(maxTextureSize)
        append("px")
    }

@Composable
private fun ModuleRow(module: StudioModule) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(StudioAccent),
        )
        Column {
            Text(
                text = module.name,
                color = StudioText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = module.description,
                color = StudioMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141B)
@Composable
private fun KinetixStudioAppPreview() {
    KinetixStudioApp()
}
