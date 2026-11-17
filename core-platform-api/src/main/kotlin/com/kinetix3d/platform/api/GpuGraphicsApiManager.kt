/*
 * Kinetix3D Studio — core-platform-api
 * GPU zero-copy strategy. Phase 1 exposes the bridge contract and allocates a
 * frame-sized hardware buffer; the EGLImageKHR binding itself ships with the
 * Phase 2 Rust platform crate.
 */

package com.kinetix3d.platform.api

import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi
import com.kinetix3d.model.DeviceCapabilityProfile

interface GpuGraphicsApiManager {
    /**
     * Allocates a [width] × [height] [HardwareBuffer] of [format] (for example
     * `HardwareBuffer.RGBA_8888` or `HardwareBuffer.RGBA_FP16`) with the given
     * [usage] flags, or returns `null` when the device cannot satisfy it.
     */
    fun createZeroCopyBridge(
        width: Int,
        height: Int,
        format: Int,
        usage: Long,
    ): HardwareBufferBridge?
}

/** A shared-GPU-memory bridge; always close it in `finally`. */
interface HardwareBufferBridge : AutoCloseable {
    val hardwareBuffer: HardwareBuffer

    val width: Int

    val height: Int
}

/**
 * Allocates a [HardwareBuffer] when the first-boot profile proved AHB support.
 * API 26–28 returns null, so callers take the SurfaceTexture + FBO-copy fallback
 * path.
 *
 * TODO(Phase 2): bind this buffer to an EGLImageKHR in
 * `:core-platform-api/rust` → `libkinetix_platform.so` using
 * `eglGetNativeClientBufferANDROID` → `eglCreateImageKHR` →
 * `glEGLImageTargetTexture2DOES`. No native build is wired in this step; until
 * then the bridge is allocation-only and safe to close.
 */
class AndroidGpuGraphicsApiManager(
    private val profile: DeviceCapabilityProfile,
) : GpuGraphicsApiManager {
    override fun createZeroCopyBridge(
        width: Int,
        height: Int,
        format: Int,
        usage: Long,
    ): HardwareBufferBridge? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !profile.supportsAhbZeroCopy) return null
        if (width <= 0 || height <= 0 || format == 0 || usage == 0L) return null
        return createBridgeQ(width, height, format, usage)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createBridgeQ(
        width: Int,
        height: Int,
        format: Int,
        usage: Long,
    ): HardwareBufferBridge? {
        if (format == HardwareBuffer.RGBA_FP16 && !profile.supportsRgba16f) return null
        return try {
            val buffer =
                HardwareBuffer.create(
                    width,
                    height,
                    format,
                    BUFFER_LAYERS,
                    usage,
                )
            HardwareBufferBridgeImpl(buffer, width, height)
        } catch (throwable: Throwable) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class HardwareBufferBridgeImpl(
        override val hardwareBuffer: HardwareBuffer,
        override val width: Int,
        override val height: Int,
    ) : HardwareBufferBridge {
        override fun close() {
            hardwareBuffer.close()
        }
    }

    private companion object {
        const val BUFFER_LAYERS = 1
    }
}
