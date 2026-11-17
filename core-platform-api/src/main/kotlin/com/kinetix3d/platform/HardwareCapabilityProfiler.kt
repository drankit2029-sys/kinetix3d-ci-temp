/*
 * Kinetix3D Studio — core-platform-api
 * First-boot hardware profiler. Every stage is best-effort: a probe failure
 * persists the conservative Tier-2 default (approximate = true) instead of
 * failing first boot. EGL work runs on a dedicated thread because contexts are
 * thread-affine.
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kinetix3d.platform

import android.app.ActivityManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.HardwareTier
import com.kinetix3d.model.MaxResFps
import com.kinetix3d.model.VideoMime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HardwareCapabilityProfiler(
    private val context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * Cold-boot cache read. Deliberately creates no EGL context: the cache is
     * accepted when `(SDK_INT, Build.FINGERPRINT)` still match. `GL_RENDERER` /
     * `GL_VERSION` recorded by the probe are kept in the sidecar for the
     * RenderThread's lazy re-verification (a driver update can change RGBA16F
     * behavior without an SDK bump; call [invalidateCache] or re-run the
     * benchmark then).
     */
    fun loadCachedProfile(): DeviceCapabilityProfile? {
        val profileFile = profileFile()
        val sidecarFile = sidecarFile()
        if (!profileFile.isFile || !sidecarFile.isFile) return null
        return try {
            val lines = sidecarFile.readLines()
            if (readSidecarValue(lines, KEY_SCHEMA) != CACHE_SCHEMA_VERSION.toString()) return null
            if (readSidecarValue(lines, KEY_DEVICE) != currentDeviceKey()) return null
            val bytes = profileFile.readBytes()
            ProtoBuf.decodeFromByteArray(DeviceCapabilityProfile.serializer(), bytes)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Cached device profile is unreadable; ignoring it", throwable)
            null
        }
    }

    /** Settings → Re-evaluate Device Hardware also calls this before re-probing. */
    fun invalidateCache() {
        profileFile().delete()
        sidecarFile().delete()
    }

    /**
     * Always re-probes and rewrites the cache. Never throws for hardware reasons;
     * only cancellation propagates.
     */
    suspend fun runFirstBootBenchmark(): DeviceCapabilityProfile {
        val result =
            try {
                probeOnDedicatedThread()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(TAG, "Hardware probe failed; using Tier-2 defaults", throwable)
                ProbeResult(DeviceCapabilityProfile.tier2Default(approximate = true), null, null)
            }
        try {
            persist(result)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Failed to persist the device profile", throwable)
        }
        return result.profile
    }

    private suspend fun probeOnDedicatedThread(): ProbeResult {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, PROBE_THREAD_NAME).apply { isDaemon = true }
            }
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            withContext(dispatcher) { probeAllStages() }
        } finally {
            dispatcher.close()
        }
    }

    private suspend fun probeAllStages(): ProbeResult {
        val maxDecoders =
            try {
                probeMaxHwDecoders()
            } catch (throwable: Throwable) {
                Log.w(TAG, "Stage 1 (decoder-count) probe failed", throwable)
                0
            }
        if (maxDecoders <= 0) {
            return ProbeResult(DeviceCapabilityProfile.tier2Default(approximate = true), null, null)
        }

        var approximate = false
        val codecProbe =
            try {
                probeCodecMatrix()
            } catch (throwable: Throwable) {
                Log.w(TAG, "Stage 2 (codec-matrix) probe failed", throwable)
                CodecMatrixProbeResult(emptyMap(), emptyMap())
            }
        if (codecProbe.decode.isEmpty() && codecProbe.encode.isEmpty()) approximate = true

        val gles = probeGles()
        if (gles.approximate) approximate = true

        val supportsAhb =
            try {
                probeAhbZeroCopy()
            } catch (throwable: Throwable) {
                false
            }

        val memory =
            try {
                probeMemory()
            } catch (throwable: Throwable) {
                Log.w(TAG, "Stage 4 (memory) probe failed", throwable)
                MemoryProbeResult(totalMemMb = 0, lowRam = false, approximate = true)
            }
        if (memory.approximate) approximate = true

        val cpuCores = readCpuCoreCount()
        Log.d(
            TAG,
            "Probe: decoders=$maxDecoders cpuCores=$cpuCores totalMemMb=${memory.totalMemMb} " +
                "lowRam=${memory.lowRam} gl=${gles.renderer.orEmpty()}",
        )

        // Tier thresholds follow Blueprint §1.1 / Refined Features §2.1:
        // TIER_1 = flagship (>=8GB, >=6 decoders, RGBA16F), TIER_3 = low-RAM
        // (<4GB or isLowRamDevice), everything else is TIER_2. A failed memory
        // probe (totalMemMb == 0) deliberately stays TIER_2, never TIER_1.
        val tier =
            when {
                memory.lowRam || (memory.totalMemMb in 1 until TIER3_MAX_MEMORY_MB) -> HardwareTier.TIER_3

                memory.totalMemMb >= TIER1_MIN_MEMORY_MB &&
                    maxDecoders >= TIER1_MIN_DECODERS &&
                    gles.supportsRgba16f -> HardwareTier.TIER_1

                else -> HardwareTier.TIER_2
            }
        val safeVramBudgetMb =
            when (tier) {
                HardwareTier.TIER_1 -> DeviceCapabilityProfile.TIER1_VRAM_BUDGET_MB
                HardwareTier.TIER_2 -> DeviceCapabilityProfile.TIER2_VRAM_BUDGET_MB
                HardwareTier.TIER_3 -> DeviceCapabilityProfile.TIER3_VRAM_BUDGET_MB
            }
        val profile =
            DeviceCapabilityProfile(
                tier = tier,
                maxHwDecoders = maxDecoders,
                supportsRgba16f = gles.supportsRgba16f,
                supportsAhbZeroCopy = supportsAhb,
                maxTextureSize = gles.maxTextureSize,
                safeVramBudgetMb = safeVramBudgetMb,
                decodeMatrix = codecProbe.decode,
                encodeMatrix = codecProbe.encode,
                approximate = approximate,
                glRenderer = gles.renderer,
            )
        return ProbeResult(profile, gles.renderer, gles.version)
    }

    // ---------------------------------------------------------------------
    // Stage 1 — simultaneous 720p H.264 hardware decoders (cap 16)
    //
    // Each probe is bound to its own *detached* SurfaceTexture + Surface:
    // Surface-backed decoders allocate from the Gralloc/AHardwareBuffer producer
    // pool, whose concurrent-instance ceiling differs from ByteBuffer mode.
    // ---------------------------------------------------------------------

    private fun probeMaxHwDecoders(): Int {
        val codec =
            CodecProbeSupport.findHardwareCodec(CodecProbeSupport.MIME_H264, encoder = false)
                ?: return 0
        val format = MediaFormat.createVideoFormat(CodecProbeSupport.MIME_H264, PROBE_WIDTH, PROBE_HEIGHT)
        val liveDecoders = ArrayList<DecoderProbe>(MAX_HW_DECODERS)
        try {
            while (liveDecoders.size < MAX_HW_DECODERS) {
                val decoder = allocateDecoderWithTimeout(codec.name, format) ?: break
                liveDecoders.add(decoder)
            }
            return liveDecoders.size
        } finally {
            for (decoder in liveDecoders) {
                decoder.release()
            }
        }
    }

    /**
     * Allocates one surface-backed decoder with a hard timeout.
     *
     * `Future.cancel(true)` cannot abort an in-flight vendor binder call inside
     * `configure()` / `start()`. The worker therefore publishes its probe into
     * [lateProbe]; when the timeout wins, [cancelled] makes the late worker release
     * immediately, and the caller sweeps anything published in the race window.
     * A leaked hardware decoder slot would permanently reduce the pool ceiling.
     */
    private fun allocateDecoderWithTimeout(
        codecName: String,
        format: MediaFormat,
    ): DecoderProbe? {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "$PROBE_THREAD_NAME-decoder").apply { isDaemon = true }
            }
        val cancelled = AtomicBoolean(false)
        val lateProbe = AtomicReference<DecoderProbe?>(null)
        var future: Future<DecoderProbe>? = null
        try {
            future =
                executor.submit<DecoderProbe> {
                    val codec = MediaCodec.createByCodecName(codecName)
                    val surfaceTexture = SurfaceTexture(false)
                    val surface = Surface(surfaceTexture)
                    val probe = DecoderProbe(codec, surfaceTexture, surface)
                    var published = false
                    try {
                        codec.configure(format, surface, null, 0)
                        codec.start()
                        lateProbe.set(probe)
                        published = true
                        if (cancelled.get()) {
                            if (lateProbe.compareAndSet(probe, null)) probe.release()
                            throw CancellationException("Decoder probe timed out")
                        }
                        probe
                    } catch (throwable: Throwable) {
                        // Before publication the worker owns the probe outright;
                        // after publication either this worker or the caller's
                        // timeout sweep released it exactly once.
                        if (!published) probe.release()
                        throw throwable
                    }
                }
            val allocated = future.get(DECODER_ALLOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            lateProbe.compareAndSet(allocated, null)
            return allocated
        } catch (error: TimeoutException) {
            cancelled.set(true)
            future?.cancel(true)
            lateProbe.getAndSet(null)?.release()
            Log.d(TAG, "Decoder allocation timed out after ${DECODER_ALLOCATION_TIMEOUT_MS}ms", error)
            return null
        } catch (error: ExecutionException) {
            when (error.cause) {
                is MediaCodec.CodecException -> Log.d(TAG, "Decoder allocation failed: CodecException")
                is IllegalArgumentException -> Log.d(TAG, "Decoder allocation failed: IllegalArgumentException")
                is IllegalStateException -> Log.d(TAG, "Decoder allocation failed: IllegalStateException")
                else -> Log.d(TAG, "Decoder allocation failed")
            }
            return null
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelled.set(true)
            future?.cancel(true)
            lateProbe.getAndSet(null)?.release()
            return null
        } finally {
            executor.shutdownNow()
        }
    }

    private class DecoderProbe(
        private val decoder: MediaCodec,
        private val surfaceTexture: SurfaceTexture,
        private val surface: Surface,
    ) {
        fun release() {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { surface.release() }
            runCatching { surfaceTexture.release() }
        }
    }

    // ---------------------------------------------------------------------
    // Stage 2 — hardware decode and encode matrices for H.264, HEVC, VP9 and AV1
    //
    // Decode and encode stay separate: most mid-range SoCs decode AV1/VP9 but
    // have no hardware encoder for them, and a 4K60 decoder may only encode
    // 4K30. Conflating the ceilings makes the exporter claim unsupported
    // hardware encoding and under-reports the decoder pool ceiling.
    // ---------------------------------------------------------------------

    private fun probeCodecMatrix(): CodecMatrixProbeResult {
        val decode = LinkedHashMap<VideoMime, MaxResFps>()
        val encode = LinkedHashMap<VideoMime, MaxResFps>()
        for (videoMime in VideoMime.entries) {
            val mime = CodecProbeSupport.mimeFor(videoMime)
            bestSupported(mime, encoder = false)?.let { decode[videoMime] = it }
            bestSupported(mime, encoder = true)?.let { encode[videoMime] = it }
        }
        return CodecMatrixProbeResult(decode = decode, encode = encode)
    }

    private fun bestSupported(
        mime: String,
        encoder: Boolean,
    ): MaxResFps? {
        val info = CodecProbeSupport.findHardwareCodec(mime, encoder) ?: return null
        val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return null
        val video = capabilities.videoCapabilities ?: return null
        for ((width, height) in CANDIDATE_RESOLUTIONS) {
            val supported =
                runCatching {
                    video.areSizeAndRateSupported(width, height, CANDIDATE_RATE_FPS)
                }.recoverCatching {
                    video.isSizeSupported(width, height)
                }.getOrDefault(false)
            if (!supported) continue
            val fps =
                runCatching { video.getSupportedFrameRatesFor(width, height).upper.toDouble() }
                    .getOrDefault(0.0)
            if (fps <= 0.0) continue
            return MaxResFps(width, height, minOf(fps, MAX_REPORTED_FPS))
        }
        return null
    }

    private data class CodecMatrixProbeResult(
        val decode: Map<VideoMime, MaxResFps>,
        val encode: Map<VideoMime, MaxResFps>,
    )

    // ---------------------------------------------------------------------
    // Stage 3 — offscreen EGL + GLES extension probe
    // ---------------------------------------------------------------------

    private fun probeGles(): GlesProbeResult {
        val gles =
            try {
                OffscreenGlesContext.create()
            } catch (throwable: Throwable) {
                null
            } ?: return GlesProbeResult(
                supportsRgba16f = false,
                maxTextureSize = DEFAULT_MAX_TEXTURE_SIZE,
                renderer = null,
                version = null,
                approximate = true,
            )
        return try {
            // R11F_G11F_B10F is color-renderable in core GLES 3.0; the half/float
            // extensions are what actually gate RGBA16F render target support.
            val supportsRgba16f =
                gles.extensions.any { extension ->
                    extension == "GL_EXT_color_buffer_float" || extension == "GL_EXT_color_buffer_half_float"
                }
            val reportedTextureSize = gles.maxTextureSize
            GlesProbeResult(
                supportsRgba16f = supportsRgba16f,
                maxTextureSize =
                    if (reportedTextureSize > 0) {
                        reportedTextureSize
                    } else {
                        DEFAULT_MAX_TEXTURE_SIZE
                    },
                renderer = gles.renderer,
                version = gles.version,
                approximate = reportedTextureSize <= 0,
            )
        } finally {
            gles.close()
        }
    }

    private fun probeAhbZeroCopy(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return probeAhbZeroCopyQ()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun probeAhbZeroCopyQ(): Boolean =
        try {
            val buffer =
                HardwareBuffer.create(
                    AHB_PROBE_SIZE,
                    AHB_PROBE_SIZE,
                    HardwareBuffer.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
                )
            buffer.close()
            true
        } catch (throwable: Throwable) {
            false
        }

    // ---------------------------------------------------------------------
    // Stage 4 — memory / CPU tier
    // ---------------------------------------------------------------------

    private fun probeMemory(): MemoryProbeResult {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            return MemoryProbeResult(totalMemMb = 0, lowRam = false, approximate = true)
        }
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemMb = (memoryInfo.totalMem / BYTES_PER_MB).toInt()
        return MemoryProbeResult(
            totalMemMb = totalMemMb,
            lowRam = activityManager.isLowRamDevice,
            approximate = totalMemMb <= 0,
        )
    }

    private suspend fun readCpuCoreCount(): Int =
        withContext(Dispatchers.IO) {
            try {
                File(PROC_CPUINFO).useLines { lines ->
                    lines.count { line -> line.startsWith("processor") }.coerceAtLeast(1)
                }
            } catch (throwable: Throwable) {
                Runtime.getRuntime().availableProcessors()
            }
        }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private suspend fun persist(result: ProbeResult) {
        withContext(Dispatchers.IO) {
            val profileBytes =
                ProtoBuf.encodeToByteArray(
                    DeviceCapabilityProfile.serializer(),
                    result.profile,
                )
            val renderer = result.glRenderer.orEmpty()
            val version = result.glVersion.orEmpty()
            val sidecar =
                buildString {
                    appendLine("$KEY_SCHEMA=$CACHE_SCHEMA_VERSION")
                    appendLine("$KEY_DEVICE=${currentDeviceKey()}")
                    appendLine("$KEY_GL_DEVICE=${currentDeviceKey()}|$renderer|$version")
                    appendLine("$KEY_RENDERER=$renderer")
                    appendLine("$KEY_VERSION=$version")
                }
            writeAtomically(profileFile(), profileBytes)
            writeAtomically(sidecarFile(), sidecar.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun readSidecarValue(
        lines: List<String>,
        key: String,
    ): String? = lines.firstOrNull { line -> line.startsWith("$key=") }?.substringAfter('=')

    private fun currentDeviceKey(): String = "${Build.VERSION.SDK_INT}|${Build.FINGERPRINT}"

    private fun profileFile(): File = File(appContext.filesDir, PROFILE_FILE_NAME)

    private fun sidecarFile(): File = File(appContext.filesDir, SIDECAR_FILE_NAME)

    private data class ProbeResult(
        val profile: DeviceCapabilityProfile,
        val glRenderer: String?,
        val glVersion: String?,
    )

    private data class GlesProbeResult(
        val supportsRgba16f: Boolean,
        val maxTextureSize: Int,
        val renderer: String?,
        val version: String?,
        val approximate: Boolean,
    )

    private data class MemoryProbeResult(
        val totalMemMb: Int,
        val lowRam: Boolean,
        val approximate: Boolean,
    )

    private companion object {
        const val TAG = "KinetixHwProfile"
        const val PROBE_THREAD_NAME = "kinetix-hw-probe"
        const val PROFILE_FILE_NAME = "device_profile.pb"
        const val SIDECAR_FILE_NAME = "device_profile.key"
        const val CACHE_SCHEMA_VERSION = 2
        const val KEY_SCHEMA = "schema"
        const val KEY_DEVICE = "deviceKey"
        const val KEY_GL_DEVICE = "glKey"
        const val KEY_RENDERER = "glRenderer"
        const val KEY_VERSION = "glVersion"
        const val PROC_CPUINFO = "/proc/cpuinfo"
        const val BYTES_PER_MB = 1024L * 1024L

        const val PROBE_WIDTH = 1280
        const val PROBE_HEIGHT = 720
        const val MAX_HW_DECODERS = 16
        const val DECODER_ALLOCATION_TIMEOUT_MS = 1_500L

        const val DEFAULT_MAX_TEXTURE_SIZE = 4096
        const val MAX_REPORTED_FPS = 120.0
        const val CANDIDATE_RATE_FPS = 30.0
        const val AHB_PROBE_SIZE = 4

        const val TIER1_MIN_MEMORY_MB = 8 * 1024
        const val TIER1_MIN_DECODERS = 6
        const val TIER3_MAX_MEMORY_MB = 4 * 1024

        val CANDIDATE_RESOLUTIONS =
            listOf(
                7680 to 4320,
                4096 to 2160,
                3840 to 2160,
                2560 to 1440,
                1920 to 1080,
                1280 to 720,
            )
    }
}
