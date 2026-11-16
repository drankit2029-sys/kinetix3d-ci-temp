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

/** How an operator behaves under 512×512 tile-bucket rendering. */
sealed interface TilingCapability {
    /** Output depends only on the same pixel: safe for any tile, zero apron. */
    data object PointExact : TilingCapability

    /** Samples neighbours within [radiusPx]: tileable while radiusPx ≤ maxApronPx. */
    data class LocalNeighborhood(
        val radiusPx: Int,
    ) : TilingCapability

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
        val unit: String? = null,
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
    ) : ParamSpec

    data class Vec3Param(
        override val key: String,
        override val displayName: String,
        val defaultX: Float = 0f,
        val defaultY: Float = 0f,
        val defaultZ: Float = 0f,
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

data class ModeSpec(
    val modeId: String,
    val label: String,
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
    val version: Int = 1,
) {
    init {
        require(id.isNotBlank()) { "Operator id must not be blank" }
        require(sockets.isNotEmpty()) { "Operator '$id' must declare at least one socket" }
        require(sockets.map { it.name }.toSet().size == sockets.size) { "Duplicate socket name in '$id'" }
        require(params.map { it.key }.toSet().size == params.size) { "Duplicate param key in '$id'" }
        require(modes.map { it.modeId }.toSet().size == modes.size) { "Duplicate mode id in '$id'" }
    }
}

/**
 * Static descriptor catalog. The 14 operators listed here are exactly the v1.0
 * (Phase 5) set; PolygonNode/FollowPathNode ship in Phase 4, the point/instancing
 * suite and Generator/GLSL sandbox are v1.5.
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
                    ModeSpec("gaussian", "Gaussian"),
                    ModeSpec("kawase", "Kawase Dual Filter"),
                    ModeSpec("bokeh_dof", "Bokeh DoF"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("radius", "Radius", default = 8f, min = 0f, max = 128f, unit = "px"),
                    ParamSpec.IntParam("quality", "Quality", default = 2, min = 1, max = 4),
                ),
            tiling = TilingCapability.LocalNeighborhood(128),
        )

    val glow =
        KinetixOperatorSchema(
            id = "kinetix.glow",
            displayName = "Glow",
            category = OperatorCategory.LIGHT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("optical_mip", "Optical Mip-Chain"),
                    ModeSpec("saber", "Saber"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("threshold", "Threshold", default = 0.8f, max = 4f),
                    ParamSpec.FloatParam("intensity", "Intensity", default = 1f, max = 8f),
                    ParamSpec.FloatParam("radius", "Radius", default = 24f, min = 0f, max = 128f, unit = "px"),
                ),
            tiling = TilingCapability.LocalNeighborhood(128),
        )

    val tiles =
        KinetixOperatorSchema(
            id = "kinetix.tiles",
            displayName = "Tiles",
            category = OperatorCategory.DISTORT,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec("mirror", "Mirror"),
                    ModeSpec("repeat", "Repeat"),
                    ModeSpec("kaleidoscope", "Kaleidoscope"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("gridX", "Grid X", default = 3f, min = 1f, max = 32f, step = 1f),
                    ParamSpec.FloatParam("gridY", "Grid Y", default = 3f, min = 1f, max = 32f, step = 1f),
                    ParamSpec.FloatParam("angle", "Angle", default = 0f, min = -180f, max = 180f, unit = "°"),
                ),
            tiling = TilingCapability.PointExact,
        )

    val warp =
        KinetixOperatorSchema(
            id = "kinetix.warp",
            displayName = "Warp",
            category = OperatorCategory.DISTORT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("turbulent", "Turbulent"),
                    ModeSpec("displace_map", "Displace Map"),
                    ModeSpec("wave", "Wave"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("amplitude", "Amplitude", default = 16f, min = 0f, max = 256f, unit = "px"),
                    ParamSpec.FloatParam("frequency", "Frequency", default = 4f, min = 0f, max = 64f),
                    ParamSpec.IntParam("seed", "Seed", default = 0, max = 9999),
                ),
            tiling = TilingCapability.LocalNeighborhood(256),
        )

    val glitch =
        KinetixOperatorSchema(
            id = "kinetix.glitch",
            displayName = "Glitch",
            category = OperatorCategory.DISTORT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("rgb_split", "RGB Split"),
                    ModeSpec("block", "Block Corruption"),
                    ModeSpec("scanline", "Scanline"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("intensity", "Intensity", default = 0.5f),
                    ParamSpec.IntParam("seed", "Seed", default = 0, max = 9999),
                    ParamSpec.IntParam("blockSize", "Block Size", default = 16, min = 1, max = 256),
                ),
            tiling = TilingCapability.PointExact,
        )

    val vectorify =
        KinetixOperatorSchema(
            id = "kinetix.vectorify",
            displayName = "Vectorify",
            category = OperatorCategory.FILTER,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("stylize", "Stylize"),
                    ModeSpec("posterize", "Oklab Posterize"),
                ),
            params =
                listOf(
                    ParamSpec.IntParam("levels", "Levels", default = 6, min = 2, max = 32),
                    ParamSpec.FloatParam(
                        "smoothRadius",
                        "Smooth Radius",
                        default = 4f,
                        min = 0f,
                        max = 32f,
                        unit = "px",
                    ),
                ),
            tiling = TilingCapability.LocalNeighborhood(16),
        )

    val oscillate =
        KinetixOperatorSchema(
            id = "kinetix.oscillate",
            displayName = "Oscillate / Auto-Shake",
            category = OperatorCategory.GEOMETRY,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec("oscillate", "Oscillate"),
                    ModeSpec("auto_shake", "Auto-Shake"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("amplitude", "Amplitude", default = 8f, min = 0f, max = 128f, unit = "px"),
                    ParamSpec.FloatParam("frequency", "Frequency", default = 2f, min = 0f, max = 30f, unit = "Hz"),
                    ParamSpec.IntParam("seed", "Seed", default = 0, max = 9999),
                ),
            tiling = TilingCapability.PointExact,
        )

    val copyBackground =
        KinetixOperatorSchema(
            id = "kinetix.copy-background",
            displayName = "CopyBackground / Glass",
            category = OperatorCategory.FILTER,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("copy_background", "Copy Background"),
                    ModeSpec("acrylic", "Acrylic"),
                    ModeSpec("loupe", "Loupe"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("blurRadius", "Blur Radius", default = 16f, min = 0f, max = 64f, unit = "px"),
                    ParamSpec.FloatParam("tint", "Tint", default = 0f, min = -1f, max = 1f),
                ),
            tiling = TilingCapability.LocalNeighborhood(64),
            readsUnderlyingComposite = true,
        )

    val layerStyle =
        KinetixOperatorSchema(
            id = "kinetix.layer-style",
            displayName = "Stroke / Bevel / Shadow",
            category = OperatorCategory.FILTER,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("stroke", "Stroke"),
                    ModeSpec("bevel", "Bevel"),
                    ModeSpec("shadow", "Drop Shadow"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("width", "Width", default = 4f, min = 0f, max = 64f, unit = "px"),
                    ParamSpec.FloatParam("softness", "Softness", default = 2f, min = 0f, max = 64f, unit = "px"),
                    ParamSpec.Color4fParam("color", "Color", defaultA = 1f),
                ),
            tiling = TilingCapability.LocalNeighborhood(128),
        )

    val cornerPin =
        KinetixOperatorSchema(
            id = "kinetix.corner-pin",
            displayName = "CornerPin / Card3D",
            category = OperatorCategory.GEOMETRY,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec("corner_pin", "Corner Pin"),
                    ModeSpec("card3d", "Card3D"),
                ),
            params =
                listOf(
                    ParamSpec.Vec2Param("topLeft", "Top Left"),
                    ParamSpec.Vec2Param("topRight", "Top Right"),
                    ParamSpec.Vec2Param("bottomRight", "Bottom Right"),
                    ParamSpec.Vec2Param("bottomLeft", "Bottom Left"),
                    ParamSpec.FloatParam("perspective", "Perspective", default = 0f, min = 0f, max = 1f),
                ),
            tiling = TilingCapability.PointExact,
        )

    val optics =
        KinetixOperatorSchema(
            id = "kinetix.optics",
            displayName = "LensOptics & GodRays",
            category = OperatorCategory.LIGHT,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("god_rays", "God Rays"),
                    ModeSpec("anamorphic", "Anamorphic Streak"),
                    ModeSpec("chromatic_aberration", "Chromatic Aberration"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("threshold", "Threshold", default = 0.9f, max = 4f),
                    ParamSpec.FloatParam("length", "Ray Length", default = 64f, min = 0f, max = 512f, unit = "px"),
                    ParamSpec.Color4fParam("tint", "Tint", defaultR = 1f, defaultG = 0.9f, defaultB = 0.7f),
                ),
            tiling = TilingCapability.GlobalSpatialBarrier,
        )

    val timeFx =
        KinetixOperatorSchema(
            id = "kinetix.time-fx",
            displayName = "TimeFX",
            category = OperatorCategory.TIME,
            sockets = listOf(imageIn(), imageOut),
            modes =
                listOf(
                    ModeSpec("echo", "Time Echo"),
                    ModeSpec("freeze", "Freeze Frame"),
                ),
            params =
                listOf(
                    ParamSpec.IntParam("echoCount", "Echo Count", default = 3, min = 1, max = 16),
                    ParamSpec.FloatParam("stepSeconds", "Step", default = 0.1f, min = 0f, max = 2f, unit = "s"),
                ),
            tiling = TilingCapability.PointExact,
        )

    val colorSuite =
        KinetixOperatorSchema(
            id = "kinetix.color-suite",
            displayName = "ColorSuite",
            category = OperatorCategory.COLOR,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("cdl", "ASC-CDL"),
                    ModeSpec("curves", "Curves"),
                    ModeSpec("lut", "3D LUT (.cube)"),
                    ModeSpec("hsl", "HSL Qualifier"),
                ),
            params =
                listOf(
                    ParamSpec.FloatParam("exposure", "Exposure", default = 0f, min = -5f, max = 5f, unit = "stops"),
                    ParamSpec.FloatParam("contrast", "Contrast", default = 1f, min = 0f, max = 4f),
                    ParamSpec.FloatParam("saturation", "Saturation", default = 1f, min = 0f, max = 4f),
                    ParamSpec.FloatParam("temperature", "Temperature", default = 0f, min = -1f, max = 1f),
                ),
            tiling = TilingCapability.PointExact,
        )

    val keyer =
        KinetixOperatorSchema(
            id = "kinetix.keyer",
            displayName = "Keyer",
            category = OperatorCategory.KEYING,
            sockets = listOf(imageIn(), maskIn, imageOut),
            modes =
                listOf(
                    ModeSpec("chroma", "Chroma Key"),
                    ModeSpec("polyhedral", "3D Polyhedral Key"),
                    ModeSpec("luma", "Luma Key"),
                ),
            params =
                listOf(
                    ParamSpec.Color4fParam("keyColor", "Key Color", defaultG = 1f),
                    ParamSpec.FloatParam("tolerance", "Tolerance", default = 0.1f),
                    ParamSpec.FloatParam("softness", "Softness", default = 0.05f),
                    ParamSpec.FloatParam("despill", "Despill", default = 0.5f),
                ),
            tiling = TilingCapability.PointExact,
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
                    ParamSpec.FloatParam("feather", "Feather", default = 0f, min = 0f, max = 128f, unit = "px"),
                    ParamSpec.FloatParam(
                        "cornerRadius",
                        "Corner Radius",
                        default = 0f,
                        min = 0f,
                        max = 256f,
                        unit = "px",
                    ),
                ),
            tiling = TilingCapability.LocalNeighborhood(128),
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
            tiling = TilingCapability.PointExact,
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
