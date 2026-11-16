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
    val codecMatrix: Map<VideoMime, MaxResFps>,
    val approximate: Boolean = false,
) {
    companion object {
        /** Conservative fallback persisted when any probe stage fails (never fail first boot). */
        fun tier2Default(approximate: Boolean = true): DeviceCapabilityProfile =
            DeviceCapabilityProfile(
                tier = HardwareTier.TIER_2,
                maxHwDecoders = 2,
                supportsRgba16f = false,
                supportsAhbZeroCopy = false,
                maxTextureSize = 4096,
                safeVramBudgetMb = 192,
                codecMatrix =
                    mapOf(
                        VideoMime.H264 to MaxResFps(1920, 1080, 60.0),
                        VideoMime.HEVC to MaxResFps(1920, 1080, 30.0),
                        VideoMime.VP9 to MaxResFps(1920, 1080, 30.0),
                        VideoMime.AV1 to MaxResFps(1280, 720, 30.0),
                    ),
                approximate = approximate,
            )
    }
}
