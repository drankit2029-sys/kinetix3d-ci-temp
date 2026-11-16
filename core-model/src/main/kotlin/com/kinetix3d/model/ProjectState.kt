/*
 * Kinetix3D Studio — core-model
 * Immutable project state: clips are Nodes, every clip owns a NodeGraph that powers
 * both the linear Effects Rack and the free-form Node Studio.
 */

package com.kinetix3d.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

// ---------------------------------------------------------------------------
// Composition settings & color
// ---------------------------------------------------------------------------

enum class ColorPrimaries { REC709, REC2020, ACES_AP0 }

enum class TransferFunction { REC709, HLG, PQ, LINEAR }

data class ColorSpaceSpec(
    val primaries: ColorPrimaries = ColorPrimaries.REC709,
    val transfer: TransferFunction = TransferFunction.REC709,
)

data class CompSettings(
    val width: Int,
    val height: Int,
    val fps: Double,
    val colorSpace: ColorSpaceSpec = ColorSpaceSpec(),
    val bitDepth: Int = 8,
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

data class TransformBlock(
    val position: Vec3 = Vec3.ZERO,
    val anchor: Vec3 = Vec3.ZERO,
    val rotation: Quaternion = Quaternion.IDENTITY,
    val scale: Vec3 = Vec3.ONE,
    val skewDegrees: Vec2 = Vec2.ZERO,
    val bend: Float = 0f,
)

enum class BlendMode {
    NORMAL,
    ADD,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    DARKEN,
    LIGHTEN,
    COLOR_DODGE,
    COLOR_BURN,
    HARD_LIGHT,
    SOFT_LIGHT,
    DIFFERENCE,
    EXCLUSION,
    HUE,
    SATURATION,
    COLOR,
    LUMINOSITY,
}

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

sealed interface TimeRemapSpec {
    /** 1.0 = real time, 2.0 = 2× speed, 0.5 = half speed. */
    data class ConstantRate(
        val rate: Float,
    ) : TimeRemapSpec {
        init {
            require(rate > 0f) { "ConstantRate must be positive (reverse playback is not a v1.0 feature)" }
        }
    }

    /** Bezier velocity ramp; integral consumed by SpeedRampIntegrator (:core-evaluator). */
    data class VelocityBezierCurve(
        val curve: KeyframeCurve<Float>,
    ) : TimeRemapSpec
}

// ---------------------------------------------------------------------------
// Clip payloads — sealed polymorphism replaces a bag of nullable fields.
// ---------------------------------------------------------------------------

sealed interface ClipPayload

data object VideoClipPayload : ClipPayload

data object AudioClipPayload : ClipPayload

enum class TextAlignment { LEFT, CENTER, RIGHT }

data class TextSource(
    val text: String,
    val fontAssetId: String? = null,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val fontSizeSp: Float = 48f,
)

data class TextClipPayload(
    val textSource: TextSource,
) : ClipPayload

data class PolygonClipPayload(
    val polygonId: String,
) : ClipPayload

data class NullRigClipPayload(
    val is3D: Boolean = false,
) : ClipPayload

data class PreCompClipPayload(
    val targetCompId: String,
    val collapseTransformations: Boolean = false,
) : ClipPayload

// ---------------------------------------------------------------------------
// Tracks & cut transitions
// ---------------------------------------------------------------------------

enum class TrackType { VIDEO, AUDIO, FOLDER }

enum class TrackHeight(
    val dp: Int,
) {
    DP_24(24),
    DP_48(48),
    DP_88(88),
}

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

/** What to do when a cut transition needs media beyond a clip's trimmed handles. */
enum class HandlePolicy { FREEZE, LOOP, PING_PONG, STRETCH }

/**
 * One cut-point transition. Lives on the CompositionGraph, never on a ClipNode —
 * a transition belongs to the cut between two clips, not to either side of it.
 */
data class CutTransition(
    val transitionId: String,
    val outgoingClipId: String,
    val incomingClipId: String,
    val durationUs: Long,
    val alignment: Float = 0.5f,
    val handlePolicy: HandlePolicy = HandlePolicy.FREEZE,
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

enum class AssetKind { VIDEO, AUDIO, IMAGE, FONT, MODEL, OTHER }

enum class ColorStandard { UNKNOWN, BT601, BT709, BT2020 }

enum class ColorTransfer { UNKNOWN, SDR, HLG, PQ, LINEAR }

enum class ColorRange { UNKNOWN, LIMITED, FULL }

/**
 * Immutable media-pool entry. [uri] is always a plain [String] — never
 * android.net.Uri — so this module stays 100% JVM-clean.
 */
data class MediaAssetDescriptor(
    val assetId: String,
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AssetKind,
    /** Heuristic re-link fingerprint (positioned head/mid/tail reads), never a content hash. */
    val fingerprint: String? = null,
    val durationUs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val colorStandard: ColorStandard = ColorStandard.UNKNOWN,
    val colorTransfer: ColorTransfer = ColorTransfer.UNKNOWN,
    val colorRange: ColorRange = ColorRange.UNKNOWN,
    val proxyUri: String? = null,
)

// ---------------------------------------------------------------------------
// Composition & project
// ---------------------------------------------------------------------------

data class CompositionGraph(
    val compositionId: String,
    val name: String,
    val settings: CompSettings,
    val clips: PersistentMap<String, ClipNode> = persistentMapOf(),
    val tracks: PersistentList<TrackNode> = persistentListOf(),
    val transitions: PersistentList<CutTransition> = persistentListOf(),
) {
    /** Timeline duration = right-most clip edge (0 for an empty comp). */
    fun durationUs(): Long = clips.values.maxOfOrNull { it.timelineOutUs } ?: 0L

    fun trackById(trackId: String): TrackNode? = tracks.firstOrNull { it.trackId == trackId }
}

data class ProjectState(
    val projectId: String,
    val settings: CompSettings,
    val compositions: PersistentMap<String, CompositionGraph>,
    val activeCompId: String,
    val mediaPool: PersistentMap<String, MediaAssetDescriptor> = persistentMapOf(),
) {
    init {
        require(activeCompId in compositions) { "activeCompId '$activeCompId' is not in compositions" }
    }

    fun activeComposition(): CompositionGraph = compositions.getValue(activeCompId)
}
