/*
 * Kinetix3D Studio — core-platform-api
 * The single unified OS surface consumed by engine/app modules. Implementations
 * are wired by PlatformApiProvider; this façade itself contains no SDK checks.
 */

package com.kinetix3d.platform.api

interface PlatformApi {
    val mediaImport: MediaImportApiManager
    val videoCodec: VideoCodecApiManager
    val gpuGraphics: GpuGraphicsApiManager
    val storageAndExport: StorageAndExportApiManager
    val hapticsAndInput: HapticsAndInputApiManager
    val thermalAndMemory: ThermalAndMemoryApiManager
    val npuAiInference: NpuAiInferenceApiManager
}
