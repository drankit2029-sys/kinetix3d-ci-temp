/*
 * Kinetix3D Studio — Day 1 shell.
 * Placeholder studio screen that proves the full module graph compiles and links:
 * app -> ui-studio -> (core-model, core-evaluator, core-platform-api,
 *                      engine-media, engine-render, engine-ai)
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
import androidx.compose.foundation.shape.CircleShape
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
        StudioModule(EngineMedia.NAME, "Decoder pool · All-I proxy worker · Oboe audio"),
        StudioModule(EngineRender.NAME, "GLES 3.2 DAG · DoD FBO pool · tile-bucket render"),
        StudioModule(EngineAi.NAME, "Offline NPU AI: SAM 2 · DepthAnything · Whisper"),
        StudioModule(ModuleInfo.NAME, "Adaptive studio UI · timeline · OmniTrackpad"),
    )

@Composable
fun KinetixStudioApp() {
    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = StudioBackground,
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
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
                Text(
                    text = "MODULES",
                    color = StudioMuted,
                    fontSize = 11.sp,
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
