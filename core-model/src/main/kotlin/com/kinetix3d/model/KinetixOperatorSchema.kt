/*
 * Kinetix3D Studio — core-model
 * Declarative operator metadata. This lives in :core-model — NOT in :ui-studio or
 * :engine-render — so the model, the node editor UI and the render engine can all
 * consume the same catalog without a dependency cycle. :engine-render only maps
 * schema.id → GpuNodeRenderer.
 */

package com.kinetix3d.model

enum class OperatorCategory {
    GROUP,
    FILTER,
    DISTORT,
    LIGHT,
    TIME,
    COLOR,
    KEYING,
    GEOMETRY,
    UTILITY,
}

/**
 * Unit of a numeric operator parameter.
 *
 * Every spatial quantity — blur radius, stroke width, displace amplitude, ray
 * length, polygon feather — MUST be authored in [COMP_HEIGHT_NORM] so a `540p`
 * proxy and a `4K` export render identically. Raw pixels never enter the model.
 */
enum class UnitType {
    /** Fraction of composition height; `1.0 == comp height`. Resolution independent. */
    COMP_HEIGHT_NORM,

    /** Angle in degrees. */
    DEGREES,

    /** Duration in seconds. */
    SECONDS,

    /** Temporal frequency in hertz. */
    HERTZ,

    /** Exposure in photographic stops. */
    STOPS,
}

/** How an operator behaves under 512×512 tile-bucket rendering. */
sealed interface TilingCapability {
    /** Output depends only on the same pixel: safe for any tile, zero apron. */
    data object PointExact : TilingCapability

    /**
     * Samples neighbours within [radius] composition-height fractions.
     * Tileable while `radius * compHeightPx <= maxApronPx`.
     */
    data class LocalNeighborhood(
        val radius: Float,
    ) : TilingCapability {
        init {
            require(radius >= 0f) { "LocalNeighborhood radius must be non-negative" }
        }
    }

    /**
     * Output pixel `p` samples upstream at a remapped coordinate (tile wrap,
     * homography, path transform). The tile composer must propagate the inverse
     * mapped RoI upstream instead of assuming a same-position neighbourhood.
     */
    data object CoordinateRemap : TilingCapability

    /** Non-local by nature (sort, rays, global blur): hard Tile Barrier upstream. */
    data object GlobalSpatialBarrier : TilingCapability
}

sealed interface ParamSpec {
    val key: String
    val displayName: String

    data class FloatParam(
        override val key: String,
        override val displayName: String,
        val default: Float,
        val min: Float = 0f,
        val max: Float = 1f,
        val step: Float = 0.01f,
        val unit: UnitType? = null,
    ) : ParamSpec

    data class IntParam(
        override val key: String,
        override val displayName: String,
        val default: Int,
        val min: Int = 0,
        val max: Int = Int.MAX_VALUE,
    ) : ParamSpec

    data class BoolParam(
        override val key: String,
        override val displayName: String,
        val default: Boolean,
    ) : ParamSpec

    data class Vec2Param(
        override val key: String,
        override val displayName: String,
        val defaultX: Float = 0f,
        val defaultY: Float = 0f,
        val unit: UnitType? = null,
    ) : ParamSpec

    data class Vec3Param(
        override val key: String,
        override val displayName: String,
        val defaultX: Float = 0f,
        val defaultY: Float = 0f,
        val defaultZ: Float = 0f,
        val unit: UnitType? = null,
    ) : ParamSpec

    data class QuaternionParam(
        override val key: String,
        override val displayName: String,
        val default: Quaternion = Quaternion.IDENTITY,
    ) : ParamSpec

    data class Color4fParam(
        override val key: String,
        override val displayName: String,
        val defaultR: Float = 0f,
        val defaultG: Float = 0f,
        val defaultB: Float = 0f,
        val defaultA: Float = 1f,
    ) : ParamSpec

    data class SplineVerticesParam(
        override val key: String,
        override val displayName: String,
        val maxVertices: Int = 64,
        val closed: Boolean = false,
    ) : ParamSpec
}

enum class SocketDirection { INPUT, OUTPUT }

enum class SocketDataType { IMAGE, MASK, POINTS, GEOMETRY, TEXT, FLOAT, VEC3, COLOR }

data class SocketSpec(
    val name: String,
    val direction: SocketDirection,
    val dataType: SocketDataType,
    val required: Boolean = false,
)

/**
 * One selectable operator mode. [visibleParams] lists the [ParamSpec.key]s the
 * inspector shows for this mode (Mode-Aware Deep Search and the Phase 5 shaders
 * both key off this list).
 */
data class ModeSpec(
    val modeId: String,
    val label: String,
    val visibleParams: List<String> = emptyList(),
    /** Overrides [KinetixOperatorSchema.tiling] when this mode is active. */
    val tilingOverride: TilingCapability? = null,
    /** Temporal feedback mode (datamosh/advection): carries VRAM-bounded checkpoints. */
    val isStateful: Boolean = false,
)

data class KinetixOperatorSchema(
    val id: String,
    val displayName: String,
    val category: OperatorCategory,
    val sockets: List<SocketSpec>,
    val modes: List<ModeSpec> = emptyList(),
    val params: List<ParamSpec> = emptyList(),
    val tiling: TilingCapability,
    /** Stateful operators carry temporal checkpoints bounded by the DoD/VRAM policies. */
    val isStateful: Boolean = false,
    /** CopyBackground & Glass: binds the read-only underlyingComposite snapshot. */
    val readsUnderlyingComposite: Boolean = false,
    /** Mode-Aware Deep Search terms (synonyms never shown in the UI). */
    val searchSynonyms: List<String> = emptyList(),
    val version: Int = 1,
) {
    init {
        require(id.isNotBlank()) { "Operator id must not be blank" }
        require(sockets.isNotEmpty()) { "Operator '$id' must declare at least one socket" }
        require(sockets.map { it.name }.toSet().size == sockets.size) { "Duplicate socket name in '$id'" }
        require(params.map { it.key }.toSet().size == params.size) { "Duplicate param key in '$id'" }
        require(modes.map { it.modeId }.toSet().size == modes.size) { "Duplicate mode id in '$id'" }
        val paramKeys = params.map { it.key }.toSet()
        modes.forEach { mode ->
            val unknown = mode.visibleParams.filterNot { it in paramKeys }
            require(unknown.isEmpty()) { "Mode '${mode.modeId}' in '$id' references unknown params $unknown" }
        }
    }

    /** Mode selected by [modeId]; a `null` id selects the schema's first mode. */
    fun mode(modeId: String? = null): ModeSpec? =
        if (modeId == null) modes.firstOrNull() else modes.firstOrNull { it.modeId == modeId }

    /** Effective tile capability for the active mode. */
    fun tilingFor(modeId: String? = null): TilingCapability = mode(modeId)?.tilingOverride ?: tiling

    /** Effective temporal-feedback flag for the active mode. */
    fun isStatefulFor(modeId: String? = null): Boolean = mode(modeId)?.isStateful ?: isStateful
}

/**
 * Static descriptor catalog. The 14 operators listed here are exactly the v1.0
 * (Phase 5) set; PolygonNode/FollowPathNode ship in Phase 4, the point/instancing
 * suite and Generator/GLSL sandbox are v1.5.
 *
 * Spatial parameters are expressed in composition-height fractions (see
 * [UnitType.COMP_HEIGHT_NORM]) — never raw pixels.
 */
object KinetixOperatorCatalog {
    private fun imageIn(required: Boolean = true) =
        SocketSpec("clipSource", SocketDirection.INPUT, SocketDataType.IMAGE, required)

    private val maskIn = SocketSpec("mask", SocketDirection.INPUT, SocketDataType.MASK)

    private val imageOut = SocketSpec("out", SocketDirection.OUTPUT, SocketDataType.IMAGE)

    val groupInput =
        KinetixOperatorSchema(
            id = NodeSchemaIds.GROUP_INPUT,
            displayName = "Group Input",
            category = OperatorCategory.GROUP,
            sockets =
                listOf(
                    SocketSpec("clipSource", SocketDirection.OUTPUT, SocketDataType.IMAGE),
                    SocketSpec("underlyingComposite", SocketDirection.OUTPUT, SocketDataType.IMAGE),
                ),
            tiling = TilingCapability.PointExact,
        )

    val groupOutput =
        KinetixOperatorSchema(
            id = NodeSchemaIds.GROUP_OUTPUT,
            displayName = "Group Output",
            category = OperatorCategory.GROUP,
            sockets = listOf(imageIn(), maskIn, imageOut),
            tiling = TilingCapability.PointExact,
        )

    val blur =
        KinetixOperatorSchema(
            id = "kinetix.blur",
            displayName = "Blur",
            category = OperatorCategory.FILTER,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("gaussian", "Gaussian", visibleParams = listOf("radius", "quality")),
                    ModeSpec("kawase", "Kawase Dual Filter", visibleParams = listOf("radius", "quality")),
                    ModeSpec("bokeh_dof", "Bokeh DoF", visibleParams = listOf("radius", "quality")),
                    ModeSpec("box", "Box", visibleParams = listOf("radius", "quality")),
                    ModeSpec(
                        "directional_motion",
                        "Directional Motion",
                        visibleParams = listOf("radius", "angle", "quality"),
                    ),
                    ModeSpec(
                        "radial_zoom",
                        "Radial Zoom",
                        visibleParams = listOf("radius", "quality"),
                        tilingOverride = TilingCapability.GlobalSpatialBarrier,
                    ),
                    ModeSpec(
                        "radial_spin",
                        "Radial Spin",
                        visibleParams = listOf("radius", "angle", "quality"),
                        tilingOverride = TilingCapability.GlobalSpatialBarrier,
                    ),
                    ModeSpec("surface_bilateral", "Surface Bilateral", visibleParams = listOf("radius", "quality")),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam(
                        "radius",
                        "Radius",
                        default = 0.02f,
                        min = 0f,
                        max = 0.5f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.FloatParam(
                        "angle",
                        "Angle",
                        default = 0f,
                        min = -180f,
                        max = 180f,
                        step = 1f,
                        unit = UnitType.DEGREES,
                    ),
                    ParamSpec.IntParam("quality", "Quality", default = 2, min = 1, max = 4),
                ),
            tiling = TilingCapability.LocalNeighborhood(0.5f),
            searchSynonyms =
                listOf(
                    "gaussian",
                    "lens blur",
                    "dof",
                    "depth of field",
                    "zoom blur",
                    "radial",
                    "motion blur",
                    "bilateral",
                    "box blur",
                ),
        )

    val glow =
        KinetixOperatorSchema(
            id = "kinetix.glow",
            displayName = "Glow",
            category = OperatorCategory.LIGHT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec(
                        "optical_mip",
                        "Optical Mip-Chain",
                        visibleParams = listOf("threshold", "intensity", "radius"),
                    ),
                    ModeSpec("saber", "Neon Saber Rim", visibleParams = listOf("threshold", "intensity", "radius")),
                    ModeSpec(
                        "outer_edge",
                        "Outer Edge Glow",
                        visibleParams = listOf("threshold", "intensity", "radius"),
                    ),
                    ModeSpec("inner", "Inner Glow", visibleParams = listOf("threshold", "intensity", "radius")),
                    ModeSpec(
                        "anamorphic_streak",
                        "Anamorphic Streak",
                        visibleParams = listOf("threshold", "intensity", "radius"),
                    ),
                    ModeSpec(
                        "film_halation",
                        "35mm Film Halation",
                        visibleParams = listOf("threshold", "intensity", "radius"),
                    ),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("threshold", "Threshold", default = 0.8f, max = 4f),
                    ParamSpec.FloatParam("intensity", "Intensity", default = 1f, max = 8f),
                    ParamSpec.FloatParam(
                        "radius",
                        "Radius Spread",
                        default = 0.03f,
                        min = 0f,
                        max = 0.25f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                ),
            tiling = TilingCapability.LocalNeighborhood(0.25f),
            searchSynonyms =
                listOf(
                    "bloom",
                    "halation",
                    "anamorphic",
                    "neon",
                    "saber",
                    "outer glow",
                    "inner glow",
                    "light spill",
                ),
        )

    val tiles =
        KinetixOperatorSchema(
            id = "kinetix.tiles",
            displayName = "Tiles",
            category = OperatorCategory.DISTORT,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec("mirror", "Mirror", visibleParams = listOf("gridX", "gridY", "angle")),
                    ModeSpec("repeat", "Repeat", visibleParams = listOf("gridX", "gridY", "angle")),
                    ModeSpec("kaleidoscope", "Kaleidoscope", visibleParams = listOf("gridX", "gridY", "angle")),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("gridX", "Grid X", default = 3f, min = 1f, max = 32f, step = 1f),
                    ParamSpec.FloatParam("gridY", "Grid Y", default = 3f, min = 1f, max = 32f, step = 1f),
                    ParamSpec.FloatParam(
                        "angle",
                        "Angle",
                        default = 0f,
                        min = -180f,
                        max = 180f,
                        step = 1f,
                        unit = UnitType.DEGREES,
                    ),
                ),
            tiling = TilingCapability.CoordinateRemap,
            searchSynonyms = listOf("mirror tiles", "kaleidoscope", "repeat", "grid", "offset tiles"),
        )

    val warp =
        KinetixOperatorSchema(
            id = "kinetix.warp",
            displayName = "Warp",
            category = OperatorCategory.DISTORT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("turbulent", "Turbulent", visibleParams = listOf("amplitude", "frequency", "seed")),
                    ModeSpec("displace_map", "Displace Map", visibleParams = listOf("amplitude", "frequency", "seed")),
                    ModeSpec("wave", "Wave", visibleParams = listOf("amplitude", "frequency", "seed")),
                    ModeSpec("swirl", "Swirl", visibleParams = listOf("amplitude", "frequency", "seed")),
                    ModeSpec("bulge", "Bulge", visibleParams = listOf("amplitude", "frequency", "seed")),
                    ModeSpec(
                        "bezier_mesh",
                        "Bezier Mesh",
                        visibleParams = listOf("amplitude", "seed"),
                        tilingOverride = TilingCapability.CoordinateRemap,
                    ),
                    ModeSpec("liquify", "Liquify", visibleParams = listOf("amplitude", "frequency", "seed")),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam(
                        "amplitude",
                        "Amplitude",
                        default = 0.02f,
                        min = 0f,
                        max = 0.25f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.FloatParam("frequency", "Frequency", default = 4f, min = 0f, max = 64f, step = 0.1f),
                    ParamSpec.IntParam("seed", "Seed", default = 0, max = 9999),
                ),
            tiling = TilingCapability.LocalNeighborhood(0.25f),
            searchSynonyms = listOf("turbulence", "displace", "wave", "swirl", "bulge", "liquify", "mesh warp"),
        )

    val glitch =
        KinetixOperatorSchema(
            id = "kinetix.glitch",
            displayName = "Glitch",
            category = OperatorCategory.DISTORT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("rgb_split", "RGB Split", visibleParams = listOf("intensity", "seed")),
                    ModeSpec("block", "Block Corruption", visibleParams = listOf("intensity", "seed", "blockSize")),
                    ModeSpec("scanline", "Scanline", visibleParams = listOf("intensity", "seed")),
                    ModeSpec(
                        "datamosh",
                        "True P-Frame Datamosh",
                        visibleParams = listOf("intensity", "seed"),
                        isStateful = true,
                    ),
                    ModeSpec(
                        "pixel_sort",
                        "Pixel Sort",
                        visibleParams = listOf("intensity", "seed"),
                        tilingOverride = TilingCapability.GlobalSpatialBarrier,
                    ),
                    ModeSpec("vhs_crt", "VHS / CRT", visibleParams = listOf("intensity", "seed", "blockSize")),
                    ModeSpec(
                        "wave_slice_tear",
                        "Wave Slice Tear",
                        visibleParams = listOf("intensity", "seed", "blockSize"),
                    ),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("intensity", "Intensity", default = 0.5f),
                    ParamSpec.IntParam("seed", "Seed", default = 0, max = 9999),
                    ParamSpec.FloatParam(
                        "blockSize",
                        "Block Size",
                        default = 0.015f,
                        min = 0.001f,
                        max = 0.24f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                ),
            tiling = TilingCapability.PointExact,
            // Datamosh carries temporal feedback checkpoints in one of the modes.
            isStateful = true,
            searchSynonyms = listOf("datamosh", "pixel sort", "vhs", "crt", "scanline", "rgb split", "tear"),
        )

    val vectorify =
        KinetixOperatorSchema(
            id = "kinetix.vectorify",
            displayName = "Vectorify",
            category = OperatorCategory.FILTER,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("stylize", "Stylize", visibleParams = listOf("levels", "smoothRadius")),
                    ModeSpec("posterize", "Oklab Posterize", visibleParams = listOf("levels")),
                ),
            params =
                listOf(
                    ParamSpec.IntParam("levels", "Levels", default = 6, min = 2, max = 32),
                    ParamSpec.FloatParam(
                        "smoothRadius",
                        "Smooth Radius",
                        default = 0.004f,
                        min = 0f,
                        max = 0.03f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                ),
            tiling = TilingCapability.LocalNeighborhood(0.03f),
            searchSynonyms = listOf("posterize", "oklab", "stylize", "vector", "edge outline"),
        )

    val oscillate =
        KinetixOperatorSchema(
            id = "kinetix.oscillate",
            displayName = "Oscillate / Auto-Shake",
            category = OperatorCategory.GEOMETRY,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec("oscillate", "Oscillate", visibleParams = listOf("amplitude", "frequency", "seed")),
                    ModeSpec("auto_shake", "Auto-Shake", visibleParams = listOf("amplitude", "frequency", "seed")),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam(
                        "amplitude",
                        "Amplitude",
                        default = 0.01f,
                        min = 0f,
                        max = 0.12f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.FloatParam(
                        "frequency",
                        "Frequency",
                        default = 2f,
                        min = 0f,
                        max = 30f,
                        step = 0.1f,
                        unit = UnitType.HERTZ,
                    ),
                    ParamSpec.IntParam("seed", "Seed", default = 0, max = 9999),
                ),
            tiling = TilingCapability.CoordinateRemap,
            searchSynonyms = listOf("shake", "handheld", "jitter", "wobble", "oscillate"),
        )

    val copyBackground =
        KinetixOperatorSchema(
            id = "kinetix.copy-background",
            displayName = "CopyBackground / Glass",
            category = OperatorCategory.FILTER,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("copy_background", "Copy Background", visibleParams = listOf("blurRadius", "tint")),
                    ModeSpec("acrylic", "Acrylic", visibleParams = listOf("blurRadius", "tint")),
                    ModeSpec("loupe", "Loupe", visibleParams = listOf("blurRadius", "tint")),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam(
                        "blurRadius",
                        "Blur Radius",
                        default = 0.015f,
                        min = 0f,
                        max = 0.06f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.FloatParam("tint", "Tint", default = 0f, min = -1f, max = 1f),
                ),
            tiling = TilingCapability.LocalNeighborhood(0.06f),
            readsUnderlyingComposite = true,
            searchSynonyms = listOf("glass", "acrylic", "frosted", "loupe", "magnify", "backdrop blur"),
        )

    val layerStyle =
        KinetixOperatorSchema(
            id = "kinetix.layer-style",
            displayName = "Stroke / Bevel / Shadow",
            category = OperatorCategory.FILTER,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("stroke", "Stroke", visibleParams = listOf("width", "softness", "color")),
                    ModeSpec("bevel", "Bevel", visibleParams = listOf("width", "softness", "color")),
                    ModeSpec("shadow", "Drop Shadow", visibleParams = listOf("width", "softness", "color")),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam(
                        "width",
                        "Width",
                        default = 0.004f,
                        min = 0f,
                        max = 0.06f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.FloatParam(
                        "softness",
                        "Softness",
                        default = 0.002f,
                        min = 0f,
                        max = 0.06f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.Color4fParam("color", "Color", defaultA = 1f),
                ),
            tiling = TilingCapability.LocalNeighborhood(0.06f),
            searchSynonyms = listOf("stroke", "bevel", "drop shadow", "outline", "emboss"),
        )

    val cornerPin =
        KinetixOperatorSchema(
            id = "kinetix.corner-pin",
            displayName = "CornerPin / Card3D",
            category = OperatorCategory.GEOMETRY,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec(
                        "corner_pin",
                        "Corner Pin",
                        visibleParams = listOf("topLeft", "topRight", "bottomRight", "bottomLeft", "perspective"),
                    ),
                    ModeSpec(
                        "card3d",
                        "Card3D",
                        visibleParams = listOf("topLeft", "topRight", "bottomRight", "bottomLeft", "perspective"),
                    ),
                ),
            params =
                listOf(
                    ParamSpec.Vec2Param("topLeft", "Top Left", unit = UnitType.COMP_HEIGHT_NORM),
                    ParamSpec.Vec2Param("topRight", "Top Right", unit = UnitType.COMP_HEIGHT_NORM),
                    ParamSpec.Vec2Param("bottomRight", "Bottom Right", unit = UnitType.COMP_HEIGHT_NORM),
                    ParamSpec.Vec2Param("bottomLeft", "Bottom Left", unit = UnitType.COMP_HEIGHT_NORM),
                    ParamSpec.FloatParam("perspective", "Perspective", default = 0f, min = 0f, max = 1f),
                ),
            tiling = TilingCapability.CoordinateRemap,
            searchSynonyms = listOf("corner pin", "card3d", "homography", "perspective", "page peel"),
        )

    val optics =
        KinetixOperatorSchema(
            id = "kinetix.optics",
            displayName = "LensOptics & GodRays",
            category = OperatorCategory.LIGHT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("god_rays", "God Rays", visibleParams = listOf("threshold", "length", "tint")),
                    ModeSpec("anamorphic", "Anamorphic Streak", visibleParams = listOf("threshold", "length", "tint")),
                    ModeSpec(
                        "chromatic_aberration",
                        "Chromatic Aberration",
                        visibleParams = listOf("length", "tint"),
                    ),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("threshold", "Threshold", default = 0.9f, max = 4f),
                    ParamSpec.FloatParam(
                        "length",
                        "Ray Length",
                        default = 0.06f,
                        min = 0f,
                        max = 0.5f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.Color4fParam("tint", "Tint", defaultR = 1f, defaultG = 0.9f, defaultB = 0.7f),
                ),
            tiling = TilingCapability.GlobalSpatialBarrier,
            searchSynonyms = listOf("god rays", "light rays", "anamorphic", "flare", "chromatic aberration", "lens"),
        )

    val timeFx =
        KinetixOperatorSchema(
            id = "kinetix.time-fx",
            displayName = "TimeFX",
            category = OperatorCategory.TIME,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec("echo", "Time Echo", visibleParams = listOf("echoCount", "stepSeconds")),
                    ModeSpec("freeze", "Freeze Frame", visibleParams = listOf("stepSeconds")),
                ),
            params =
                listOf(
                    ParamSpec.IntParam("echoCount", "Echo Count", default = 3, min = 1, max = 16),
                    ParamSpec.FloatParam(
                        "stepSeconds",
                        "Step",
                        default = 0.1f,
                        min = 0f,
                        max = 2f,
                        step = 0.01f,
                        unit = UnitType.SECONDS,
                    ),
                ),
            tiling = TilingCapability.PointExact,
            searchSynonyms = listOf("time echo", "motion trails", "freeze frame", "ghosting"),
        )

    val colorSuite =
        KinetixOperatorSchema(
            id = "kinetix.color-suite",
            displayName = "ColorSuite",
            category = OperatorCategory.COLOR,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec(
                        "cdl",
                        "ASC-CDL",
                        visibleParams = listOf("exposure", "contrast", "saturation", "temperature"),
                    ),
                    ModeSpec("curves", "Curves", visibleParams = listOf("contrast", "saturation")),
                    ModeSpec("lut", "3D LUT (.cube)", visibleParams = listOf("saturation")),
                    ModeSpec("hsl", "HSL Qualifier", visibleParams = listOf("saturation", "temperature")),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam(
                        "exposure",
                        "Exposure",
                        default = 0f,
                        min = -5f,
                        max = 5f,
                        step = 0.05f,
                        unit = UnitType.STOPS,
                    ),
                    ParamSpec.FloatParam("contrast", "Contrast", default = 1f, min = 0f, max = 4f),
                    ParamSpec.FloatParam("saturation", "Saturation", default = 1f, min = 0f, max = 4f),
                    ParamSpec.FloatParam("temperature", "Temperature", default = 0f, min = -1f, max = 1f),
                ),
            tiling = TilingCapability.PointExact,
            searchSynonyms = listOf("cdl", "curves", "lut", "hsl", "grade", "color correction", "exposure"),
        )

    val keyer =
        KinetixOperatorSchema(
            id = "kinetix.keyer",
            displayName = "Keyer",
            category = OperatorCategory.KEYING,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec(
                        "chroma",
                        "Chroma Key",
                        visibleParams = listOf("keyColor", "tolerance", "softness", "despill"),
                    ),
                    ModeSpec(
                        "polyhedral",
                        "3D Polyhedral Key",
                        visibleParams = listOf("keyColor", "tolerance", "softness", "despill"),
                    ),
                    ModeSpec("luma", "Luma Key", visibleParams = listOf("keyColor", "tolerance", "softness")),
                ),
            params =
                listOf(
                    ParamSpec.Color4fParam("keyColor", "Key Color", defaultG = 1f),
                    ParamSpec.FloatParam("tolerance", "Tolerance", default = 0.1f),
                    ParamSpec.FloatParam("softness", "Softness", default = 0.05f),
                    ParamSpec.FloatParam("despill", "Despill", default = 0.5f),
                ),
            tiling = TilingCapability.PointExact,
            searchSynonyms = listOf("chroma key", "green screen", "luma key", "despill", "polyhedral key"),
        )

    val polygon =
        KinetixOperatorSchema(
            id = "kinetix.polygon",
            displayName = "Polygon",
            category = OperatorCategory.GEOMETRY,
            sockets = listOf(imageOut),
            params =
                listOf(
                    ParamSpec.SplineVerticesParam("vertices", "Vertices", maxVertices = 256, closed = true),
                    ParamSpec.FloatParam(
                        "feather",
                        "Feather",
                        default = 0f,
                        min = 0f,
                        max = 0.12f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                    ParamSpec.FloatParam(
                        "cornerRadius",
                        "Corner Radius",
                        default = 0f,
                        min = 0f,
                        max = 0.24f,
                        step = 0.001f,
                        unit = UnitType.COMP_HEIGHT_NORM,
                    ),
                ),
            tiling = TilingCapability.LocalNeighborhood(0.24f),
            searchSynonyms = listOf("polygon", "mask", "shape", "bezier", "feather"),
        )

    val followPath =
        KinetixOperatorSchema(
            id = "kinetix.follow-path",
            displayName = "Follow Path",
            category = OperatorCategory.GEOMETRY,
            sockets = listOf(imageIn(), imageOut),
            params =
                listOf(
                    ParamSpec.SplineVerticesParam("path", "Path", maxVertices = 256, closed = true),
                    ParamSpec.FloatParam("progress", "Progress", default = 0f),
                    ParamSpec.BoolParam("orientToPath", "Orient to Path", default = true),
                ),
            tiling = TilingCapability.CoordinateRemap,
            searchSynonyms = listOf("follow path", "motion path", "orient", "spline"),
        )

    /** The 14 v1.0 polymorphic operators, in product-spec order. */
    val coreV100: List<KinetixOperatorSchema> =
        listOf(
            blur,
            glow,
            tiles,
            warp,
            glitch,
            vectorify,
            oscillate,
            copyBackground,
            layerStyle,
            cornerPin,
            optics,
            timeFx,
            colorSuite,
            keyer,
        )

    /** Every schema known to the app, including group/polygon/path helpers. */
    val all: List<KinetixOperatorSchema> =
        listOf(groupInput, groupOutput) + coreV100 +
            listOf(polygon, followPath)

    private val index: Map<String, KinetixOperatorSchema> = all.associateBy { it.id }

    fun byId(id: String): KinetixOperatorSchema? = index[id]

    fun requireById(id: String): KinetixOperatorSchema =
        requireNotNull(index[id]) { "Unknown operator schema id '$id'" }
}
