/*
 * Kinetix3D Studio — core-platform-api
 * Video decode strategy. Capability probing stays here so import can route
 * unsupported profiles to the re-encode path without catching vendor crashes.
 */

package com.kinetix3d.platform.api

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.kinetix3d.model.ColorRange
import com.kinetix3d.model.ColorStandard
import com.kinetix3d.model.ColorTransfer
import com.kinetix3d.platform.CodecProbeSupport

interface VideoCodecApiManager {
    fun queryCapabilities(mime: String): DecoderCapabilities

    fun createOptimalDecoder(
        mime: String,
        width: Int,
        height: Int,
        surface: Surface,
    ): Result<MediaCodec>
}

data class DecoderCapabilities(
    val hardwareAccelerated: Boolean,
    val profiles: IntArray?,
    val levels: IntArray?,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Double,
    val supportsLowLatency: Boolean,
)

/**
 * Typed decoder failures. [ReEncodeRequired] tells import to transcode to
 * [targetMime]; [VendorFailure] wraps a driver-specific code so callers never
 * need to catch `MediaCodec.CodecException` themselves.
 */
sealed class DecoderError(
    message: String,
) : Exception(message) {
    class UnsupportedCodec(
        val mime: String,
    ) : DecoderError("No hardware decoder for $mime")

    class ReEncodeRequired(
        val targetMime: String,
        val reason: String,
    ) : DecoderError("Re-encode to $targetMime required: $reason")

    class VendorFailure(
        val code: Int,
        message: String,
    ) : DecoderError(message)
}

/** Color description extracted from a decoder's output [MediaFormat]. */
data class ColorMetadata(
    val standard: ColorStandard = ColorStandard.UNKNOWN,
    val transfer: ColorTransfer = ColorTransfer.UNKNOWN,
    val range: ColorRange = ColorRange.UNKNOWN,
)

/**
 * Maps `MediaFormat`'s color keys onto the Android-free core-model enums.
 * Unknown/absent keys stay [ColorStandard.UNKNOWN] / [ColorTransfer.UNKNOWN] /
 * [ColorRange.UNKNOWN] so the IDT can choose a conservative default.
 */
fun colorMetadataOf(format: MediaFormat): ColorMetadata {
    val standard =
        when (format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)) {
            MediaFormat.COLOR_STANDARD_BT601_PAL,
            MediaFormat.COLOR_STANDARD_BT601_NTSC,
            -> ColorStandard.BT601

            MediaFormat.COLOR_STANDARD_BT709 -> ColorStandard.BT709

            MediaFormat.COLOR_STANDARD_BT2020 -> ColorStandard.BT2020

            else -> ColorStandard.UNKNOWN
        }
    val transfer =
        when (format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)) {
            MediaFormat.COLOR_TRANSFER_LINEAR -> ColorTransfer.LINEAR
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> ColorTransfer.SDR
            MediaFormat.COLOR_TRANSFER_HLG -> ColorTransfer.HLG
            MediaFormat.COLOR_TRANSFER_ST2084 -> ColorTransfer.PQ
            else -> ColorTransfer.UNKNOWN
        }
    val range =
        when (format.intOrNull(MediaFormat.KEY_COLOR_RANGE)) {
            MediaFormat.COLOR_RANGE_FULL -> ColorRange.FULL
            MediaFormat.COLOR_RANGE_LIMITED -> ColorRange.LIMITED
            else -> ColorRange.UNKNOWN
        }
    return ColorMetadata(standard = standard, transfer = transfer, range = range)
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

/**
 * Hardware-first decoder selection. `c2.qti.*` / `c2.exynos.*` / `c2.mtk.*` are
 * preferred when several hardware decoders advertise the same MIME.
 */
class AndroidVideoCodecApiManager : VideoCodecApiManager {
    override fun queryCapabilities(mime: String): DecoderCapabilities {
        val info = CodecProbeSupport.findCodec(mime, encoder = false) ?: return EMPTY_CAPABILITIES
        val capabilities =
            runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                ?: return EMPTY_CAPABILITIES
        val video =
            capabilities.videoCapabilities ?: return EMPTY_CAPABILITIES.copy(
                hardwareAccelerated = CodecProbeSupport.isHardwareCodec(info),
            )
        val maxWidth = video.supportedWidths.upper
        val maxHeight = video.supportedHeights.upper
        val profiles =
            capabilities.profileLevels
                .map { it.profile }
                .distinct()
                .toIntArray()
                .takeIf { it.isNotEmpty() }
        val levels =
            capabilities.profileLevels
                .map { it.level }
                .distinct()
                .toIntArray()
                .takeIf { it.isNotEmpty() }
        return DecoderCapabilities(
            hardwareAccelerated = CodecProbeSupport.isHardwareCodec(info),
            profiles = profiles,
            levels = levels,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxFps = maxFrameRate(video, maxWidth, maxHeight),
            supportsLowLatency = supportsLowLatency(capabilities),
        )
    }

    override fun createOptimalDecoder(
        mime: String,
        width: Int,
        height: Int,
        surface: Surface,
    ): Result<MediaCodec> {
        if (mime.isBlank() || width <= 0 || height <= 0) {
            return Result.failure(DecoderError.UnsupportedCodec(mime))
        }
        return try {
            val info =
                CodecProbeSupport.findCodec(mime, encoder = false)
                    ?: throw DecoderError.UnsupportedCodec(mime)
            if (!CodecProbeSupport.isHardwareCodec(info)) {
                throw DecoderError.UnsupportedCodec(mime)
            }
            val capabilities =
                runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                    ?: throw DecoderError.UnsupportedCodec(mime)
            val video = capabilities.videoCapabilities
            if (video != null) {
                val sizeSupported =
                    runCatching {
                        video.areSizeAndRateSupported(width, height, MIN_IMPORT_FPS)
                    }.recoverCatching {
                        video.isSizeSupported(width, height)
                    }.getOrDefault(false)
                if (!sizeSupported) {
                    throw DecoderError.ReEncodeRequired(
                        targetMime = CodecProbeSupport.MIME_H264,
                        reason = "$mime does not support ${width}x$height",
                    )
                }
            }
            val format =
                MediaFormat.createVideoFormat(mime, width, height).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsLowLatency(capabilities)) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    // Export-friendly default: never silently drop frames mid-render.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
                    }
                }
            val codec = MediaCodec.createByCodecName(info.name)
            try {
                codec.configure(format, surface, null, 0)
            } catch (throwable: Throwable) {
                runCatching { codec.release() }
                throw throwable
            }
            Result.success(codec)
        } catch (error: DecoderError) {
            Result.failure(error)
        } catch (error: MediaCodec.CodecException) {
            Result.failure(
                DecoderError.VendorFailure(
                    error.errorCode,
                    error.diagnosticInfo.ifEmpty { error.message ?: "MediaCodec failure" },
                ),
            )
        } catch (error: IllegalArgumentException) {
            Result.failure(
                DecoderError.VendorFailure(VENDOR_INVALID_ARGUMENT, error.message ?: "Invalid decoder argument"),
            )
        } catch (error: IllegalStateException) {
            Result.failure(
                DecoderError.VendorFailure(VENDOR_INVALID_STATE, error.message ?: "Invalid decoder state"),
            )
        }
    }

    private fun maxFrameRate(
        video: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
    ): Double {
        if (width <= 0 || height <= 0) return 0.0
        return runCatching { video.getSupportedFrameRatesFor(width, height).upper.toDouble() }
            .recoverCatching { video.supportedFrameRates.upper.toDouble() }
            .getOrDefault(0.0)
    }

    private fun supportsLowLatency(capabilities: MediaCodecInfo.CodecCapabilities): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        } else {
            false
        }

    private companion object {
        const val VENDOR_INVALID_ARGUMENT = -1
        const val VENDOR_INVALID_STATE = -2
        const val MIN_IMPORT_FPS = 24.0
        val EMPTY_CAPABILITIES =
            DecoderCapabilities(
                hardwareAccelerated = false,
                profiles = null,
                levels = null,
                maxWidth = 0,
                maxHeight = 0,
                maxFps = 0.0,
                supportsLowLatency = false,
            )
    }
}
