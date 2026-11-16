/*
 * Kinetix3D Studio — core-platform-api
 * Hand-written composition root (deliberately no Hilt/Dagger/Koin). Import path:
 * `com.kinetix3d.platform.PlatformApiProvider`. The façade type lives in
 * `com.kinetix3d.platform.api.PlatformApi`.
 */

package com.kinetix3d.platform

import android.content.Context
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.platform.api.AndroidGpuGraphicsApiManager
import com.kinetix3d.platform.api.AndroidHapticsAndInputApiManager
import com.kinetix3d.platform.api.AndroidMediaImportApiManager
import com.kinetix3d.platform.api.AndroidNpuAiInferenceApiManager
import com.kinetix3d.platform.api.AndroidStorageAndExportApiManager
import com.kinetix3d.platform.api.AndroidThermalAndMemoryApiManager
import com.kinetix3d.platform.api.AndroidVideoCodecApiManager
import com.kinetix3d.platform.api.GpuGraphicsApiManager
import com.kinetix3d.platform.api.HapticsAndInputApiManager
import com.kinetix3d.platform.api.MediaImportApiManager
import com.kinetix3d.platform.api.NpuAiInferenceApiManager
import com.kinetix3d.platform.api.PlatformApi
import com.kinetix3d.platform.api.StorageAndExportApiManager
import com.kinetix3d.platform.api.ThermalAndMemoryApiManager
import com.kinetix3d.platform.api.VideoCodecApiManager

object PlatformApiProvider {
    /**
     * Fast, non-blocking composition for `Application.onCreate` and tests. Reads
     * the persisted profile synchronously (two small files); when no first-boot
     * benchmark has run yet it falls back to the conservative Tier-2 profile with
     * `approximate = true`. Call [createWithFreshProfile] to probe and persist.
     */
    fun create(context: Context): PlatformApi {
        val appContext = context.applicationContext
        val profile =
            HardwareCapabilityProfiler(appContext).loadCachedProfile()
                ?: DeviceCapabilityProfile.tier2Default(approximate = true)
        return create(appContext, profile)
    }

    /** Wires the seven managers around an already-resolved profile. No re-probing. */
    fun create(
        context: Context,
        profile: DeviceCapabilityProfile,
    ): PlatformApi {
        val appContext = context.applicationContext
        return DefaultPlatformApi(
            mediaImport = AndroidMediaImportApiManager(appContext),
            videoCodec = AndroidVideoCodecApiManager(),
            gpuGraphics = AndroidGpuGraphicsApiManager(profile),
            storageAndExport = AndroidStorageAndExportApiManager(appContext),
            hapticsAndInput = AndroidHapticsAndInputApiManager(appContext),
            thermalAndMemory = AndroidThermalAndMemoryApiManager(appContext),
            npuAiInference = AndroidNpuAiInferenceApiManager(),
        )
    }

    /**
     * Runs the first-boot benchmark (or Settings → Re-evaluate Device Hardware)
     * on its dedicated probe thread, persists the fresh profile and wires it.
     */
    suspend fun createWithFreshProfile(context: Context): PlatformApi {
        val appContext = context.applicationContext
        val profile = HardwareCapabilityProfiler(appContext).runFirstBootBenchmark()
        return create(appContext, profile)
    }

    private class DefaultPlatformApi(
        override val mediaImport: MediaImportApiManager,
        override val videoCodec: VideoCodecApiManager,
        override val gpuGraphics: GpuGraphicsApiManager,
        override val storageAndExport: StorageAndExportApiManager,
        override val hapticsAndInput: HapticsAndInputApiManager,
        override val thermalAndMemory: ThermalAndMemoryApiManager,
        override val npuAiInference: NpuAiInferenceApiManager,
    ) : PlatformApi
}
