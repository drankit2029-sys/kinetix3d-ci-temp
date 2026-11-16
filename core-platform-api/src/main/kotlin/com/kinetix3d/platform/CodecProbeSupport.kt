/*
 * Kinetix3D Studio — core-platform-api
 * Shared MediaCodecList helpers used by the import-time capability query and the
 * first-boot profiler. Kept internal so no androidx/Java detail leaks into the
 * model layer.
 */

package com.kinetix3d.platform

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.kinetix3d.model.VideoMime
import java.util.Locale

internal object CodecProbeSupport {
    const val MIME_H264: String = "video/avc"
    const val MIME_HEVC: String = "video/hevc"
    const val MIME_VP9: String = "video/x-vnd.on2.vp9"
    const val MIME_AV1: String = "video/av01"

    fun mimeFor(videoMime: VideoMime): String =
        when (videoMime) {
            VideoMime.H264 -> MIME_H264
            VideoMime.HEVC -> MIME_HEVC
            VideoMime.VP9 -> MIME_VP9
            VideoMime.AV1 -> MIME_AV1
        }

    /**
     * `MediaCodecInfo.isHardwareAccelerated` only exists on API 29+; older devices
     * are classified by the vendor naming conventions of OMX/Codec2 software codecs.
     */
    fun isHardwareCodec(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase(Locale.ROOT)
            !name.startsWith("omx.google.") &&
                !name.startsWith("c2.android.") &&
                !name.contains(".sw.") &&
                !name.contains("software")
        }

    /**
     * Vendor Codec2 implementations are preferred over the generic c2.android /
     * OMX.google entries because the latter run on the CPU (or fail on odd sizes).
     */
    fun vendorPriority(name: String): Int {
        val normalized = name.lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("c2.qti.") -> 40
            normalized.startsWith("c2.exynos.") -> 40
            normalized.startsWith("c2.mtk.") -> 40
            normalized.startsWith("omx.qcom.") -> 30
            normalized.startsWith("omx.exynos.") -> 30
            normalized.startsWith("omx.mtk.") -> 30
            else -> 0
        }
    }

    /** Best matching codec of any kind; used for capability reporting. */
    fun findCodec(
        mime: String,
        encoder: Boolean,
    ): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .asSequence()
            .filter { info ->
                info.isEncoder == encoder && info.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
            }.maxWithOrNull(
                compareByDescending<MediaCodecInfo> { isHardwareCodec(it) }
                    .thenByDescending { vendorPriority(it.name) },
            )

    /** Best hardware-only codec; returns null when the device only ships software codecs. */
    fun findHardwareCodec(
        mime: String,
        encoder: Boolean,
    ): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .asSequence()
            .filter { info ->
                info.isEncoder == encoder &&
                    info.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } &&
                    isHardwareCodec(info)
            }.maxWithOrNull(compareByDescending<MediaCodecInfo> { vendorPriority(it.name) })
}
