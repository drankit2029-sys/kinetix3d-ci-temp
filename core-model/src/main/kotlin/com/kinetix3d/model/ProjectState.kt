/*
 * Kinetix3D Studio — core-model
 * Immutable project state: clips are Nodes, every clip owns a NodeGraph that powers
 * both the linear Effects Rack and the free-form Node Studio.
 *
 * Every type in this file is @Serializable so ProjectState (and mutations carrying
 * clip/track/graph payloads) round-trips through the WAL and .kproj files.
 */

package com.kinetix3d.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Composition settings & color
// ---------------------------------------------------------------------------

@Serializable
enum class ColorPrimaries {
    REC709,
    REC2020,

    /** ACES 2065-1 archival primaries (AP0). */
    ACES_AP0,

    /** ACEScg working-space primaries (AP1). */
    ACES_AP1,
}

@Serializable
enum class TransferFunction { REC709, HLG, PQ, LINEAR }

/** Linear working space used between the Input IDT and Output ODT. */
@Serializable
enum class WorkingColorSpace {
    /** ACEScg (AP1) linear; the default for wide-gamut/HDR sources. */
    ACESCG,

    /** Linear sRGB; the explicit opt-out for SDR-only projects. */
    LINEAR_SRGB,
}

@Serializable
data class ColorSpaceSpec(
    val primaries: ColorPrimaries = ColorPrimaries.REC709,
    val transfer: TransferFunction = TransferFunction.REC709,
)

@Serializable
data class CompSettings(
    val width: Int,
    val height: Int,
    val fps: Double,
    val colorSpace: ColorSpaceSpec = ColorSpaceSpec(),
    val bitDepth: Int = 8,
    val workingSpace: WorkingColorSpace = WorkingColorSpace.ACESCG,
) {
    init {
        require(width > 0 && height > 0) { "Composition dimensions must be positive" }
        require(fps > 0.0) { "Frame rate must be positive" }
        require(bitDepth == 8 || bitDepth == 10 || bitDepth == 16) { "Unsupported bit depth: $bitDepth" }
    }
}

// ---------------------------------------------------------------------------
// Transform / blending
// ---------------------------------------------------------------------------

@Serializable
data class TransformBlock(
    val position: Vec3 = Vec3.ZERO,
    val anchor: Vec3 = Vec3.ZERO,
    val rotation: Quaternion = Quaternion.IDENTITY,
    val scale: Vec3 = Vec3.ONE,
    val skewDegrees: Vec2 = Vec2.ZERO,
    val bend: Float = 0f,
)

/**
 * The 28-mode layer algebra from Refined Features §4.2 (Porter-Duff Normal and
 * Dissolve plus the 26 separable modes). [ADD] is retained as the historical
 * name for Linear Dodge and is exactly equivalent to [LINEAR_DODGE].
 */
@Serializable
enum class BlendMode {
    NORMAL,
    DISSOLVE,
    DARKEN,
    MULTIPLY,
    COLOR_BURN,
    LINEAR_BURN,
    DARKER_COLOR,
    LIGHTEN,
    SCREEN,
    COLOR_DODGE,
    LINEAR_DODGE,

    /** Alias of [LINEAR_DODGE] (Linear Dodge (Add)). */
    ADD,
    LIGHTER_COLOR,
    OVERLAY,
    SOFT_LIGHT,
    HARD_LIGHT,
    VIVID_LIGHT,
    LINEAR_LIGHT,
    PIN_LIGHT,
    HARD_MIX,
    DIFFERENCE,
    EXCLUSION,
    SUBTRACT,
    DIVIDE,
    HUE,
    SATURATION,
    COLOR,
    LUMINOSITY,
    ;

    /** Collapses the legacy [ADD] alias onto its spec name [LINEAR_DODGE]. */
    fun canonical(): BlendMode = if (this == ADD) LINEAR_DODGE else this
}

@Serializable
enum class MatteMode {
    NONE,
    ALPHA,
    ALPHA_INVERTED,
    LUMA,
    LUMA_INVERTED,
}

// ---------------------------------------------------------------------------
// Time remapping — deliberately OUTSIDE the node graph so the decoder
// scheduler never evaluates a graph to know when to pull a frame.
// ---------------------------------------------------------------------------

@Serializable
sealed interface TimeRemapSpec {
    /** 1.0 = real time, 2.0 = 2× speed, 0.5 = half speed. Reverse uses [ClipNode.reversed]. */
    @Serializable
    data class ConstantRate(
        val rate: Float,
    ) : TimeRemapSpec {
        init {
            require(rate > 0f) { "ConstantRate must be positive; use ClipNode.reversed for reverse playback" }
        }
    }

    /** Bezier velocity ramp; integral consumed by SpeedRampIntegrator (:core-evaluator). */
    @Serializable
    data class VelocityBezierCurve(
        val curve: KeyframeCurve<Float>,
    ) : TimeRemapSpec
}

// ---------------------------------------------------------------------------
// Clip payloads — sealed polymorphism replaces a bag of nullable fields.
// ---------------------------------------------------------------------------

@Serializable
sealed interface ClipPayload

@Serializable
data object VideoClipPayload : ClipPayload

@Serializable
data object AudioClipPayload : ClipPayload

@Serializable
enum class TextAlignment { LEFT, CENTER, RIGHT }

@Serializable
data class TextSource(
    val text: String,
    val fontAssetId: String? = null,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val fontSizeSp: Float = 48f,
)

@Serializable
data class TextClipPayload(
    val textSource: TextSource,
) : ClipPayload

@Serializable
data class PolygonClipPayload(
    val polygonId: String,
) : ClipPayload

@Serializable
data class NullRigClipPayload(
    val is3D: Boolean = false,
) : ClipPayload

@Serializable
data class PreCompClipPayload(
    val targetCompId: String,
    val collapseTransformations: Boolean = false,
) : ClipPayload

// ---------------------------------------------------------------------------
// Tracks & cut transitions
// ---------------------------------------------------------------------------

@Serializable
enum class TrackType { VIDEO, AUDIO, FOLDER }

@Serializable
enum class TrackHeight(
    val dp: Int,
) {
    DP_24(24),
    DP_48(48),
    DP_88(88),
}

@Serializable
data class TrackNode(
    val trackId: String,
    val type: TrackType,
    val parentFolderId: String? = null,
    val isShy: Boolean = false,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val isLocked: Boolean = false,
    val heightMode: TrackHeight = TrackHeight.DP_48,
    /** Audio DSP rack id; null for video-only tracks. */
    val audioDspChainId: String? = null,
)

/**
 * How a cut transition sources frames beyond a clip's trimmed handles
 * (Blueprint §3.2 / Refined Features §4.3):
 * * [OVERLAP_HANDLES] — use the real media beyond the in/out trim points.
 * * [RIPPLE_OVERLAP] — shorten/ripple the neighbours so no synthesized frames are needed.
 * * [SYNTHESIZE_MIRROR_TILE] — mirror/extend the edge frame.
 * * [SYNTHESIZE_OPTICAL_FLOW] — extrapolate edge motion with optical flow.
 */
@Serializable
enum class HandlePolicy {
    OVERLAP_HANDLES,
    RIPPLE_OVERLAP,
    SYNTHESIZE_MIRROR_TILE,
    SYNTHESIZE_OPTICAL_FLOW,
}

/**
 * One cut-point transition. Lives on the CompositionGraph, never on a ClipNode —
 * a transition belongs to the cut between two clips, not to either side of it.
 */
@Serializable
data class CutTransition(
    val transitionId: String,
    val outgoingClipId: String,
    val incomingClipId: String,
    val durationUs: Long,
    val alignment: Float = 0.5f,
    val handlePolicy: HandlePolicy = HandlePolicy.OVERLAP_HANDLES,
    val progressCurve: KeyframeCurve<Float> = KeyframeCurve.linear(),
    val nodeGraph: NodeGraph = NodeGraph(),
) {
    init {
        require(durationUs > 0L) { "Transition duration must be positive" }
        require(alignment in 0f..1f) { "Transition alignment must be within 0..1" }
    }
}

// ---------------------------------------------------------------------------
// Clips
// ---------------------------------------------------------------------------

@Serializable
data class ClipNode(
    val clipId: String,
    val trackId: String,
    val timelineInUs: Long,
    val timelineOutUs: Long,
    val sourceInUs: Long = 0L,
    val sourceOutUs: Long = timelineOutUs - timelineInUs,
    /** UUID v4/v7 key into ProjectState.mediaPool. */
    val assetId: String? = null,
    val payload: ClipPayload = VideoClipPayload,
    val timeRemap: TimeRemapSpec = TimeRemapSpec.ConstantRate(1f),
    /**
     * Reverse playback (Phase 3 Reverse Clip tool). Time-remap rates stay
     * positive; the decoder walks the source range from `sourceOutUs` backwards.
     */
    val reversed: Boolean = false,
    /** Set when this clip is nested inside a PreComp clip. */
    val parentClipId: String? = null,
    /** Links separated video (V1) and audio (A1) clips. */
    val linkGroupId: String? = null,
    val transform: TransformBlock = TransformBlock(),
    val nodeGraph: NodeGraph = NodeGraph(),
    val blendMode: BlendMode = BlendMode.NORMAL,
    val matteSourceClipId: String? = null,
    val matteMode: MatteMode = MatteMode.NONE,
) {
    init {
        require(timelineOutUs > timelineInUs) { "Clip timeline range must be non-empty" }
        require(sourceOutUs >= sourceInUs) { "Clip source range must not be inverted" }
    }

    val durationUs: Long get() = timelineOutUs - timelineInUs
}

// ---------------------------------------------------------------------------
// Media pool
// ---------------------------------------------------------------------------

@Serializable
enum class AssetKind { VIDEO, AUDIO, IMAGE, FONT, MODEL, OTHER }

@Serializable
enum class ColorStandard { UNKNOWN, BT601, BT709, BT2020 }

@Serializable
enum class ColorTransfer { UNKNOWN, SDR, HLG, PQ, LINEAR }

@Serializable
enum class ColorRange { UNKNOWN, LIMITED, FULL }

/**
 * Immutable media-pool entry. [uri] is always a plain [String] — never
 * android.net.Uri — so this module stays 100% JVM-clean.
 */
const val DEFAULT_MEDIA_BIN: String = "default"

@Serializable
data class MediaAssetDescriptor(
    val assetId: String,
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AssetKind,
    /** Heuristic re-link fingerprint (positioned head/mid/tail reads), never a content hash. */
    val fastFingerprint: String? = null,
    val durationUs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val colorStandard: ColorStandard = ColorStandard.UNKNOWN,
    val colorTransfer: ColorTransfer = ColorTransfer.UNKNOWN,
    val colorRange: ColorRange = ColorRange.UNKNOWN,
    val proxyUri: String? = null,
    /** Media-pool bin; [DEFAULT_MEDIA_BIN] when unassigned. */
    val binId: String = DEFAULT_MEDIA_BIN,
    /** Free-form tags used to filter the pool. */
    val tags: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Composition & project
// ---------------------------------------------------------------------------

@Serializable
data class CompositionGraph(
    val compositionId: String,
    val name: String,
    val settings: CompSettings,
    @Serializable(with = PersistentMapSerializer::class)
    val clips: PersistentMap<String, ClipNode> = persistentMapOf(),
    @Serializable(with = PersistentListSerializer::class)
    val tracks: PersistentList<TrackNode> = persistentListOf(),
    @Serializable(with = PersistentListSerializer::class)
    val transitions: PersistentList<CutTransition> = persistentListOf(),
) {
    /** Timeline duration = right-most clip edge (0 for an empty comp). */
    fun durationUs(): Long = clips.values.maxOfOrNull { it.timelineOutUs } ?: 0L

    fun trackById(trackId: String): TrackNode? = tracks.firstOrNull { it.trackId == trackId }
}

@Serializable
data class TimelineMarker(
    val markerId: String,
    val ptsUs: Long,
    val label: String = "",
)

@Serializable
data class ProjectState(
    val projectId: String,
    val settings: CompSettings,
    @Serializable(with = PersistentMapSerializer::class)
    val compositions: PersistentMap<String, CompositionGraph>,
    val activeCompId: String,
    @Serializable(with = PersistentMapSerializer::class)
    val mediaPool: PersistentMap<String, MediaAssetDescriptor> = persistentMapOf(),
    @Serializable(with = PersistentListSerializer::class)
    val markers: PersistentList<TimelineMarker> = persistentListOf(),
) {
    init {
        require(activeCompId in compositions) { "activeCompId '$activeCompId' is not in compositions" }
    }

    fun activeComposition(): CompositionGraph = compositions.getValue(activeCompId)
}
