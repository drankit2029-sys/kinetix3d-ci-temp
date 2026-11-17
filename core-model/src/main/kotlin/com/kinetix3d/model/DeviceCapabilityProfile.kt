/*
 * Kinetix3D Studio — core-model
 * Device capability contract produced by the first-boot profiler (:core-platform-api)
 * and consumed by every engine. 100% Android-free: keys are plain Kotlin enums.
 */

package com.kinetix3d.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class HardwareTier {
    TIER_1,
    TIER_2,
    TIER_3,
}

@Serializable
enum class VideoMime {
    H264,
    HEVC,
    VP9,
    AV1,
}

@Serializable
data class MaxResFps(
    val width: Int,
    val height: Int,
    val fps: Double,
)

/**
 * Protobuf map keys must be integral or string, so the enum-keyed codec matrix is
 * persisted through its `name` and rebuilt on decode.
 */
object CodecMatrixSerializer : KSerializer<Map<VideoMime, MaxResFps>> {
    private val delegate = MapSerializer(String.serializer(), MaxResFps.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<VideoMime, MaxResFps>,
    ) {
        delegate.serialize(encoder, value.mapKeys { it.key.name })
    }

    override fun deserialize(decoder: Decoder): Map<VideoMime, MaxResFps> =
        delegate.deserialize(decoder).mapKeys { VideoMime.valueOf(it.key) }
}

/**
 * Serializable hardware profile. Produced once on first boot (or on
 * Settings → Re-evaluate Device Hardware) and passed to every strategy manager;
 * managers never re-probe.
 *
 * [decodeMatrix] and [encodeMatrix] are deliberately separate: most mid-range
 * SoCs decode AV1/VP9 but have no hardware encoder for them, and a chip that
 * decodes 4K60 may only encode 4K30. Conflating the two ceilings makes the
 * exporter claim unsupported hardware encoding and under-reports the decoder
 * pool ceiling.
 *
 * [safeVramBudgetMb] is the tile-bucket threshold from the spec: 18 MB on
 * Tier 3, 24 MB on Tier 2, 32 MB on Tier 1. A single render pass above the
 * budget is sliced into `512x512` tiles instead of allocating a full FBO.
 */
@Serializable
data class DeviceCapabilityProfile(
    val tier: HardwareTier,
    val maxHwDecoders: Int,
    val supportsRgba16f: Boolean,
    val supportsAhbZeroCopy: Boolean,
    val maxTextureSize: Int,
    val safeVramBudgetMb: Int,
    @Serializable(with = CodecMatrixSerializer::class)
    val decodeMatrix: Map<VideoMime, MaxResFps>,
    @Serializable(with = CodecMatrixSerializer::class)
    val encodeMatrix: Map<VideoMime, MaxResFps> = emptyMap(),
    /** `GL_RENDERER` captured by the Stage-3 probe; consumers must never re-create EGL to read it. */
    val glRenderer: String? = null,
    /** GLES major/minor reported by the probe context; 3.0 is the conservative fallback. */
    val glesMajor: Int = 3,
    val glesMinor: Int = 0,
    /** `ActivityManager.largeMemoryClass` in MB; 0 when the memory probe failed. */
    val largeMemoryClassMb: Int = 0,
    val approximate: Boolean = false,
) {
    /** ES 3.2 is required for compute-heavy paths and `glBlendFuncSeparatei` WBOIT. */
    val supportsGles32: Boolean
        get() = glesMajor > 3 || (glesMajor == 3 && glesMinor >= 2)

    /** ES 3.1+ compute shaders; ES 3.0 devices use the vertex/fragment fallbacks. */
    val supportsCompute: Boolean
        get() = glesMajor > 3 || (glesMajor == 3 && glesMinor >= 1)

    companion object {
        /** Conservative fallback persisted when any probe stage fails (never fail first boot). */
        fun tier2Default(approximate: Boolean = true): DeviceCapabilityProfile =
            DeviceCapabilityProfile(
                tier = HardwareTier.TIER_2,
                maxHwDecoders = 2,
                supportsRgba16f = false,
                supportsAhbZeroCopy = false,
                maxTextureSize = 4096,
                safeVramBudgetMb = TIER2_VRAM_BUDGET_MB,
                decodeMatrix =
                    mapOf(
                        VideoMime.H264 to MaxResFps(1920, 1080, 60.0),
                        VideoMime.HEVC to MaxResFps(1920, 1080, 30.0),
                        VideoMime.VP9 to MaxResFps(1920, 1080, 30.0),
                        VideoMime.AV1 to MaxResFps(1280, 720, 30.0),
                    ),
                encodeMatrix =
                    mapOf(
                        VideoMime.H264 to MaxResFps(1920, 1080, 30.0),
                        VideoMime.HEVC to MaxResFps(1920, 1080, 30.0),
                    ),
                glesMajor = 3,
                glesMinor = 0,
                largeMemoryClassMb = 0,
                approximate = approximate,
            )

        /** Tile-bucket VRAM thresholds from the Refined Features tier table. */
        const val TIER1_VRAM_BUDGET_MB: Int = 32
        const val TIER2_VRAM_BUDGET_MB: Int = 24
        const val TIER3_VRAM_BUDGET_MB: Int = 18
    }
}
