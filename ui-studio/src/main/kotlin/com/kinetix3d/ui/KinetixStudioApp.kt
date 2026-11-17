/*
 * Kinetix3D Studio — Day 1 shell.
 * Placeholder studio screen that proves the full module graph compiles and links:
 * app -> ui-studio -> (core-model, core-evaluator, core-platform-api,
 *                      engine-media, engine-render, engine-ai)
 * It also surfaces the live DeviceCapabilityProfile and DirtyRenderLock crash
 * guard state owned by :app's KinetixApplication.
 */

package com.kinetix3d.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetix3d.media.KinetixAudioNative
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.DirtyRenderLock
import com.kinetix3d.model.HardwareTier
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
) {
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
