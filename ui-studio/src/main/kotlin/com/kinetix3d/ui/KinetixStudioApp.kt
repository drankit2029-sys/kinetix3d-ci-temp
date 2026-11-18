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
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetix3d.media.KinetixAudioNative
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.DirtyRenderLock
import com.kinetix3d.model.HardwareTier
import com.kinetix3d.platform.api.PlatformApi
import com.kinetix3d.platform.project.ProjectStore
import com.kinetix3d.render.KinetixRenderView
import com.kinetix3d.render.demoClipGraph
import com.kinetix3d.render.demoEffectsGraph
import com.kinetix3d.render.export.HardwareExportSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    var effectsEnabled by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var persistenceStatus by remember { mutableStateOf("in-memory demo") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val exportProgress = remember { MutableStateFlow(0f) }
    val progress by exportProgress.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val demoGraph = remember(effectsEnabled) { if (effectsEnabled) demoEffectsGraph() else demoClipGraph() }
    val timelineProject = remember(demoState) { demoState.activeComposition().toTimelineProjectUi() }

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
                        .verticalScroll(rememberScrollState())
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
                        factory = { context -> KinetixRenderView(context) },
                        update = { view ->
                            view.setDeviceProfile(deviceProfile ?: DeviceCapabilityProfile.tier2Default())
                            view.setProjectState(demoState, demoGraph)
                            view.setPlaying(isPlaying)
                            view.setPlayheadUs(playheadUs)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            "4K SOURCE — 960p PREVIEW · " +
                                if (effectsEnabled) "ColorSuite → Blur → Glow" else "Clean",
                        color = StudioMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { effectsEnabled = !effectsEnabled }) {
                        Text(
                            text = if (effectsEnabled) "FX On" else "FX Off",
                            color = StudioAccent,
                            fontSize = 12.sp,
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
                            val path = mediaPath
                            if (api == null || path == null) {
                                exportStatus = "export unavailable (no media)"
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
                                            val duration =
                                                minOf(
                                                    demoState.activeComposition().durationUs(),
                                                    EXPORT_DEMO_US,
                                                )
                                            HardwareExportSession(
                                                sourcePath = path,
                                                graph = demoGraph,
                                                comp = demoState.settings.copy(width = 1920, height = 1080),
                                                profile = deviceProfile ?: DeviceCapabilityProfile.tier2Default(),
                                                outputFile = output,
                                                durationUs = duration,
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
                        Text(if (exportStatus == "exporting…") "Exporting…" else "Export 3s")
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
                    onPlayheadChange = { playheadUs = it },
                    isPlaying = isPlaying,
                    onPlayingChange = { isPlaying = it },
                    selectedClipId = selectedClipId,
                    onSelectClip = { selectedClipId = it },
                    onTrimClip = { id, start, end ->
                        val updated = demoState.trimClip(id, start, end)
                        demoState = updated
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp)),
                )
                Text(
                    text = "project: $persistenceStatus",
                    color = StudioMuted,
                    fontSize = 11.sp,
                )
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
