/*
 * Kinetix3D Studio — core-platform-api
 * NPU backend strategy probing. This module only chooses a backend; Phase 7's
 * engine-ai instantiates the LiteRT delegate (no LiteRT dependency here).
 */

package com.kinetix3d.platform.api

import android.os.Build
import com.kinetix3d.platform.OffscreenGlesContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

interface NpuAiInferenceApiManager {
    suspend fun selectPreferredBackend(): PreferredNpuBackend
}

enum class PreferredNpuBackend {
    QNN_HTP,
    NEUROPILOT,
    TENSOR_TPU,
    OPENCL_GPU,
    XNNPACK_CPU,
}

class AndroidNpuAiInferenceApiManager : NpuAiInferenceApiManager {
    override suspend fun selectPreferredBackend(): PreferredNpuBackend =
        withContext(Dispatchers.IO) {
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
            val socManufacturer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Build.SOC_MANUFACTURER
                } else {
                    ""
                }
            val fingerprint =
                listOf(
                    Build.MANUFACTURER,
                    Build.BRAND,
                    Build.BOARD,
                    Build.HARDWARE,
                    Build.DEVICE,
                    Build.MODEL,
                    socModel,
                    socManufacturer,
                ).joinToString(separator = " ").lowercase(Locale.ROOT)

            when {
                // Google Tensor (gs101 / gs201 / zuma …) ships its own TPU.
                TENSOR_HINTS.any(fingerprint::contains) -> PreferredNpuBackend.TENSOR_TPU

                // Qualcomm Hexagon HTP via the QNN delegate.
                QUALCOMM_HINTS.any(fingerprint::contains) -> PreferredNpuBackend.QNN_HTP

                // MediaTek APUs are exposed through NeuroPilot.
                hasMediatekApu(fingerprint) -> PreferredNpuBackend.NEUROPILOT

                gpuRendererSuggestsOpenCl() -> PreferredNpuBackend.OPENCL_GPU

                else -> PreferredNpuBackend.XNNPACK_CPU
            }
        }

    private fun hasMediatekApu(fingerprint: String): Boolean {
        val mediatek = MEDIATEK_HINTS.any(fingerprint::contains)
        if (!mediatek) return false
        if (fingerprint.contains("apu") || fingerprint.contains("dimensity")) return true
        return fingerprint.split(' ', '/', ':', '-', '_').any { token -> MT_APU_SOC.matches(token) }
    }

    private fun gpuRendererSuggestsOpenCl(): Boolean {
        val context =
            try {
                OffscreenGlesContext.create()
            } catch (throwable: Throwable) {
                null
            } ?: return false
        return try {
            val renderer = context.renderer?.lowercase(Locale.ROOT) ?: return false
            OPENCL_RENDERER_HINTS.any(renderer::contains)
        } finally {
            context.close()
        }
    }

    private companion object {
        val TENSOR_HINTS = listOf("tensor", "gs101", "gs201", "zuma", "ripcurrent")
        val QUALCOMM_HINTS =
            listOf(
                "qualcomm",
                "snapdragon",
                "mahimahi",
                "qcom",
                "msm",
                "sm6",
                "sm7",
                "sm8",
            )
        val MEDIATEK_HINTS = listOf("mediatek", "dimensity", "helio")
        val OPENCL_RENDERER_HINTS = listOf("adreno", "mali", "powervr", "xclipse", "immortalis")
        val MT_APU_SOC = Regex("""mt[68]\d{3}""")
    }
}
