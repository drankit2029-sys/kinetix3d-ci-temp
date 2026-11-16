/*
 * Kinetix3D Studio — core-platform-api
 * GPU zero-copy strategy. Phase 1 exposes the bridge contract and allocates a
 * hardware buffer; the EGLImageKHR binding itself ships with the Phase 2 Rust
 * platform crate.
 */

package com.kinetix3d.platform.api

import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi
import com.kinetix3d.model.DeviceCapabilityProfile

interface GpuGraphicsApiManager {
    fun createZeroCopyBridge(): HardwareBufferBridge?
}

/** Marker interface for a shared-GPU-memory bridge; always close it in `finally`. */
interface HardwareBufferBridge : AutoCloseable

/**
 * Allocates an RGBA_8888 [HardwareBuffer] when the first-boot profile proved
 * AHB support. API 26–28 returns null, so callers take the SurfaceTexture +
 * FBO-copy fallback path.
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
    override fun createZeroCopyBridge(): HardwareBufferBridge? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !profile.supportsAhbZeroCopy) return null
        return createBridgeQ()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createBridgeQ(): HardwareBufferBridge? =
        try {
            val buffer =
                HardwareBuffer.create(
                    BUFFER_WIDTH,
                    BUFFER_HEIGHT,
                    HardwareBuffer.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
                )
            HardwareBufferBridgeImpl(buffer)
        } catch (throwable: Throwable) {
            null
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class HardwareBufferBridgeImpl(
        private val buffer: HardwareBuffer,
    ) : HardwareBufferBridge {
        override fun close() {
            buffer.close()
        }
    }

    private companion object {
        const val BUFFER_WIDTH = 4
        const val BUFFER_HEIGHT = 4
    }
}
