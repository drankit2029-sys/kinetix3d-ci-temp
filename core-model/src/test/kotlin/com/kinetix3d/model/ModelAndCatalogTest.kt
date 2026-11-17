@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kinetix3d.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperatorCatalogTest {
    @Test
    fun `catalog ids are unique`() {
        val ids = KinetixOperatorCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `v1_0 ships exactly the fourteen core operators`() {
        val expected =
            setOf(
                "kinetix.blur",
                "kinetix.glow",
                "kinetix.tiles",
                "kinetix.warp",
                "kinetix.glitch",
                "kinetix.vectorify",
                "kinetix.oscillate",
                "kinetix.copy-background",
                "kinetix.layer-style",
                "kinetix.corner-pin",
                "kinetix.optics",
                "kinetix.time-fx",
                "kinetix.color-suite",
                "kinetix.keyer",
            )
        assertEquals(14, KinetixOperatorCatalog.coreV100.size)
        assertEquals(expected, KinetixOperatorCatalog.coreV100.map { it.id }.toSet())
    }

    @Test
    fun `every core operator exposes an image output`() {
        KinetixOperatorCatalog.coreV100.forEach { schema ->
            assertTrue(
                schema.sockets.any { it.direction == SocketDirection.OUTPUT && it.dataType == SocketDataType.IMAGE },
                "${schema.id} must expose an image output",
            )
        }
    }

    @Test
    fun `non-local operators declare a global spatial barrier`() {
        assertEquals(
            TilingCapability.GlobalSpatialBarrier,
            KinetixOperatorCatalog.optics.tiling,
        )
        assertEquals(
            TilingCapability.PointExact,
            KinetixOperatorCatalog.colorSuite.tiling,
        )
        assertEquals(
            TilingCapability.LocalNeighborhood(0.5f),
            KinetixOperatorCatalog.blur.tiling,
        )
    }

    @Test
    fun `coordinate remaps propagate a remapped RoI instead of a same-pixel neighbourhood`() {
        listOf(
            "kinetix.tiles",
            "kinetix.corner-pin",
            "kinetix.oscillate",
            "kinetix.follow-path",
        ).forEach { id ->
            assertEquals(
                TilingCapability.CoordinateRemap,
                KinetixOperatorCatalog.requireById(id).tiling,
                "$id must declare CoordinateRemap",
            )
        }
        assertEquals(
            TilingCapability.CoordinateRemap,
            KinetixOperatorCatalog.warp.tilingFor("bezier_mesh"),
        )
    }

    @Test
    fun `spatial parameters are normalized to composition height`() {
        val floats = KinetixOperatorCatalog.all.flatMap { it.params }.filterIsInstance<ParamSpec.FloatParam>()
        val spatial = floats.filter { it.unit == UnitType.COMP_HEIGHT_NORM }
        assertTrue(spatial.isNotEmpty(), "the catalog must author spatial params in comp-height units")
        spatial.forEach { param ->
            assertTrue(param.default in 0f..1f, "${param.key} default must be a comp-height fraction")
            assertTrue(param.max <= 1f, "${param.key} max must be a comp-height fraction")
        }
        assertEquals(
            UnitType.COMP_HEIGHT_NORM,
            KinetixOperatorCatalog.blur.params
                .filterIsInstance<ParamSpec.FloatParam>()
                .first { it.key == "radius" }
                .unit,
        )
    }

    @Test
    fun `blur glow and warp expose their full mode catalogs`() {
        val blurModes =
            KinetixOperatorCatalog.blur.modes
                .map { it.modeId }
                .toSet()
        assertTrue(
            blurModes.containsAll(
                setOf("box", "directional_motion", "radial_zoom", "radial_spin", "surface_bilateral"),
            ),
        )
        assertEquals(TilingCapability.GlobalSpatialBarrier, KinetixOperatorCatalog.blur.tilingFor("radial_zoom"))
        assertEquals(TilingCapability.GlobalSpatialBarrier, KinetixOperatorCatalog.blur.tilingFor("radial_spin"))
        assertEquals(KinetixOperatorCatalog.blur.tiling, KinetixOperatorCatalog.blur.tilingFor("gaussian"))
        assertEquals("gaussian", KinetixOperatorCatalog.blur.mode()?.modeId)

        val glowModes =
            KinetixOperatorCatalog.glow.modes
                .map { it.modeId }
                .toSet()
        assertTrue(glowModes.containsAll(setOf("outer_edge", "inner", "anamorphic_streak", "film_halation")))

        val warpModes =
            KinetixOperatorCatalog.warp.modes
                .map { it.modeId }
                .toSet()
        assertTrue(warpModes.containsAll(setOf("swirl", "bulge", "bezier_mesh", "liquify")))
    }

    @Test
    fun `glitch modes carry per-mode tiling and stateful feedback`() {
        val glitch = KinetixOperatorCatalog.glitch
        assertTrue(
            glitch.modes
                .map { it.modeId }
                .toSet()
                .containsAll(setOf("datamosh", "pixel_sort", "vhs_crt", "wave_slice_tear")),
        )
        assertTrue(glitch.isStatefulFor("datamosh"))
        assertTrue(!glitch.isStatefulFor("rgb_split"))
        assertEquals(TilingCapability.GlobalSpatialBarrier, glitch.tilingFor("pixel_sort"))
        assertEquals(TilingCapability.PointExact, glitch.tilingFor("rgb_split"))
    }

    @Test
    fun `mode-aware search metadata is complete`() {
        KinetixOperatorCatalog.coreV100.forEach { schema ->
            assertTrue(schema.searchSynonyms.isNotEmpty(), "${schema.id} must expose search synonyms")
            assertTrue(schema.modes.isNotEmpty(), "${schema.id} must declare modes")
            schema.modes.forEach { mode ->
                assertTrue(mode.visibleParams.isNotEmpty(), "${schema.id}/${mode.modeId} must declare visible params")
            }
        }
    }

    @Test
    fun `copy background binds the underlying composite`() {
        assertTrue(KinetixOperatorCatalog.copyBackground.readsUnderlyingComposite)
        assertEquals(false, KinetixOperatorCatalog.blur.readsUnderlyingComposite)
    }

    @Test
    fun `schema rejects duplicate param keys`() {
        assertFailsWith<IllegalArgumentException> {
            KinetixOperatorSchema(
                id = "kinetix.invalid",
                displayName = "Invalid",
                category = OperatorCategory.FILTER,
                sockets = listOf(SocketSpec("out", SocketDirection.OUTPUT, SocketDataType.IMAGE)),
                params =
                    listOf(
                        ParamSpec.FloatParam("radius", "Radius", default = 1f),
                        ParamSpec.FloatParam("radius", "Radius Again", default = 2f),
                    ),
                tiling = TilingCapability.PointExact,
            )
        }
    }

    @Test
    fun `unknown schema id fails loudly`() {
        assertEquals(null, KinetixOperatorCatalog.byId("kinetix.does-not-exist"))
        assertFailsWith<IllegalArgumentException> {
            KinetixOperatorCatalog.requireById("kinetix.does-not-exist")
        }
    }
}

class DeviceCapabilityProfileTest {
    @Test
    fun `profile protobuf roundtrip preserves separate decode and encode ceilings`() {
        val profile =
            DeviceCapabilityProfile(
                tier = HardwareTier.TIER_2,
                maxHwDecoders = 6,
                supportsRgba16f = false,
                supportsAhbZeroCopy = false,
                maxTextureSize = 8192,
                safeVramBudgetMb = DeviceCapabilityProfile.TIER2_VRAM_BUDGET_MB,
                decodeMatrix =
                    mapOf(
                        VideoMime.H264 to MaxResFps(3840, 2160, 60.0),
                        VideoMime.AV1 to MaxResFps(1920, 1080, 120.0),
                    ),
                encodeMatrix =
                    mapOf(
                        VideoMime.H264 to MaxResFps(1920, 1080, 30.0),
                    ),
                glRenderer = "Mali-G68 MC4",
            )

        val decoded =
            ProtoBuf.decodeFromByteArray(
                DeviceCapabilityProfile.serializer(),
                ProtoBuf.encodeToByteArray(DeviceCapabilityProfile.serializer(), profile),
            )

        assertEquals(profile, decoded)
        assertEquals(3840, decoded.decodeMatrix.getValue(VideoMime.H264).width)
        assertEquals(1920, decoded.encodeMatrix.getValue(VideoMime.H264).width)
        assertTrue(VideoMime.AV1 in decoded.decodeMatrix)
        assertTrue(VideoMime.AV1 !in decoded.encodeMatrix)
        assertEquals("Mali-G68 MC4", decoded.glRenderer)
    }

    @Test
    fun `tier2 fallback is marked approximate and conservative`() {
        val fallback = DeviceCapabilityProfile.tier2Default()
        assertTrue(fallback.approximate)
        assertEquals(HardwareTier.TIER_2, fallback.tier)
        assertEquals(false, fallback.supportsAhbZeroCopy)
        assertEquals(DeviceCapabilityProfile.TIER2_VRAM_BUDGET_MB, fallback.safeVramBudgetMb)
        assertEquals(24, DeviceCapabilityProfile.TIER2_VRAM_BUDGET_MB)
    }

    @Test
    fun `tile bucket vram budgets match the spec thresholds`() {
        assertEquals(32, DeviceCapabilityProfile.TIER1_VRAM_BUDGET_MB)
        assertEquals(24, DeviceCapabilityProfile.TIER2_VRAM_BUDGET_MB)
        assertEquals(18, DeviceCapabilityProfile.TIER3_VRAM_BUDGET_MB)
    }
}

class ProjectStateTest {
    private val settings = CompSettings(width = 1920, height = 1080, fps = 60.0)

    @Test
    fun `composition duration is the right-most clip edge`() {
        val comp =
            CompositionGraph(
                compositionId = "comp-main",
                name = "Main",
                settings = settings,
                clips =
                    persistentMapOf(
                        "clip-a" to clip("clip-a", 0L, 2_000_000L),
                        "clip-b" to clip("clip-b", 1_500_000L, 5_000_000L),
                    ),
                tracks = persistentListOf(TrackNode("v1", TrackType.VIDEO)),
            )

        assertEquals(5_000_000L, comp.durationUs())
        assertEquals(TrackType.VIDEO, comp.trackById("v1")?.type)
    }

    @Test
    fun `project requires the active composition to exist`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectState(
                projectId = "p1",
                settings = settings,
                compositions = persistentMapOf(),
                activeCompId = "missing-comp",
            )
        }
    }

    @Test
    fun `clip payload polymorphism carries type-specific data`() {
        val textClip =
            clip("clip-text", 0L, 1_000_000L).copy(
                payload = TextClipPayload(TextSource(text = "Kinetix3D", alignment = TextAlignment.CENTER)),
            )
        val payload = textClip.payload
        assertTrue(payload is TextClipPayload)
        assertEquals("Kinetix3D", payload.textSource.text)
    }

    @Test
    fun `speed ramps live in time remap not in the node graph`() {
        val ramp =
            KeyframeCurve(
                keyframes =
                    listOf(
                        Keyframe(0L, 1f),
                        Keyframe(1_000_000L, 4f, Easing.CubicBezier()),
                    ),
            )
        val remapped =
            clip("clip-ramp", 0L, 1_000_000L).copy(
                timeRemap = TimeRemapSpec.VelocityBezierCurve(ramp),
            )

        assertTrue(remapped.timeRemap is TimeRemapSpec.VelocityBezierCurve)
        assertEquals(2, remapped.nodeGraph.nodes.size) // passthrough group input/output pair
    }

    @Test
    fun `non-positive constant rates are rejected`() {
        assertFailsWith<IllegalArgumentException> { TimeRemapSpec.ConstantRate(0f) }
        assertFailsWith<IllegalArgumentException> { TimeRemapSpec.ConstantRate(-1f) }
    }

    @Test
    fun `keyframe times must be strictly increasing`() {
        assertFailsWith<IllegalArgumentException> {
            KeyframeCurve(
                keyframes =
                    listOf(
                        Keyframe(1_000L, 0f),
                        Keyframe(1_000L, 1f),
                    ),
            )
        }
    }

    @Test
    fun `invalid composition settings are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { CompSettings(width = 0, height = 1080, fps = 60.0) }
        assertFailsWith<IllegalArgumentException> { CompSettings(width = 1920, height = 1080, fps = 0.0) }
        assertFailsWith<IllegalArgumentException> {
            CompSettings(
                width = 1920,
                height = 1080,
                fps = 60.0,
                bitDepth = 12,
            )
        }
    }

    @Test
    fun `composition defaults to ACEScg working space with a linear sRGB opt out`() {
        val settings = CompSettings(width = 1920, height = 1080, fps = 60.0)
        assertEquals(WorkingColorSpace.ACESCG, settings.workingSpace)
        assertEquals(
            WorkingColorSpace.LINEAR_SRGB,
            settings.copy(workingSpace = WorkingColorSpace.LINEAR_SRGB).workingSpace,
        )
        assertEquals(
            ColorPrimaries.ACES_AP1,
            ColorSpaceSpec(primaries = ColorPrimaries.ACES_AP1).primaries,
        )
    }

    @Test
    fun `project state survives a protobuf roundtrip with node params transitions and curves`() {
        val ramp =
            KeyframeCurve(
                keyframes =
                    listOf(
                        Keyframe(0L, 1f),
                        Keyframe(500_000L, 4f, Easing.CubicBezier()),
                        Keyframe(1_000_000L, 0.2f),
                        Keyframe(1_500_000L, 1f),
                    ),
                extrapolation = Extrapolation.LINEAR,
            )
        val graph =
            NodeGraph.passthrough().let { base ->
                base.copy(
                    nodes =
                        base.nodes.putting(
                            "node-blur",
                            GraphNode(
                                nodeId = "node-blur",
                                schemaId = "kinetix.blur",
                                modeId = "radial_zoom",
                                params =
                                    persistentMapOf(
                                        "radius" to ParamChannel.animated(ramp),
                                        "angle" to ParamChannel.driven("wiggle(2, 45)"),
                                    ),
                                enabled = false,
                                canvasPosition = Vec2(0.25f, -0.5f),
                            ),
                        ),
                )
            }
        val composition =
            CompositionGraph(
                compositionId = "comp-main",
                name = "Main",
                settings = settings,
                clips =
                    persistentMapOf(
                        "clip-a" to
                            clip("clip-a", 0L, 2_000_000L).copy(
                                timeRemap = TimeRemapSpec.VelocityBezierCurve(ramp),
                                blendMode = BlendMode.LINEAR_DODGE,
                                nodeGraph = graph,
                                reversed = true,
                            ),
                        "clip-b" to clip("clip-b", 2_000_000L, 4_000_000L),
                    ),
                tracks = persistentListOf(TrackNode("v1", TrackType.VIDEO)),
                transitions =
                    persistentListOf(
                        CutTransition(
                            transitionId = "t1",
                            outgoingClipId = "clip-a",
                            incomingClipId = "clip-b",
                            durationUs = 500_000L,
                            handlePolicy = HandlePolicy.SYNTHESIZE_OPTICAL_FLOW,
                            progressCurve = ramp,
                        ),
                    ),
            )
        val project =
            ProjectState(
                projectId = "p1",
                settings = settings,
                compositions = persistentMapOf("comp-main" to composition),
                activeCompId = "comp-main",
            )

        val decoded =
            ProtoBuf.decodeFromByteArray(
                ProjectState.serializer(),
                ProtoBuf.encodeToByteArray(ProjectState.serializer(), project),
            )

        assertEquals(project, decoded)
        assertEquals(
            graph.nodes.getValue("node-blur"),
            decoded
                .activeComposition()
                .clips
                .getValue("clip-a")
                .nodeGraph.nodes
                .getValue("node-blur"),
        )
    }

    private fun clip(
        id: String,
        startUs: Long,
        endUs: Long,
    ): ClipNode =
        ClipNode(
            clipId = id,
            trackId = "v1",
            timelineInUs = startUs,
            timelineOutUs = endUs,
            sourceOutUs = endUs - startUs,
            nodeGraph = NodeGraph.passthrough(),
        )
}

class LayerAlgebraTest {
    @Test
    fun `blend mode exposes the 28 spec modes including the add alias`() {
        val expected =
            setOf(
                "NORMAL",
                "DISSOLVE",
                "DARKEN",
                "MULTIPLY",
                "COLOR_BURN",
                "LINEAR_BURN",
                "DARKER_COLOR",
                "LIGHTEN",
                "SCREEN",
                "COLOR_DODGE",
                "LINEAR_DODGE",
                "ADD",
                "LIGHTER_COLOR",
                "OVERLAY",
                "SOFT_LIGHT",
                "HARD_LIGHT",
                "VIVID_LIGHT",
                "LINEAR_LIGHT",
                "PIN_LIGHT",
                "HARD_MIX",
                "DIFFERENCE",
                "EXCLUSION",
                "SUBTRACT",
                "DIVIDE",
                "HUE",
                "SATURATION",
                "COLOR",
                "LUMINOSITY",
            )
        assertEquals(28, BlendMode.entries.size)
        assertEquals(expected, BlendMode.entries.map { it.name }.toSet())
        assertEquals(BlendMode.LINEAR_DODGE, BlendMode.ADD.canonical())
        assertEquals(BlendMode.MULTIPLY, BlendMode.MULTIPLY.canonical())
    }

    @Test
    fun `handle policies match the cut transition spec`() {
        assertEquals(
            listOf("OVERLAP_HANDLES", "RIPPLE_OVERLAP", "SYNTHESIZE_MIRROR_TILE", "SYNTHESIZE_OPTICAL_FLOW"),
            HandlePolicy.entries.map { it.name },
        )
    }

    @Test
    fun `clips carry a first-class reverse flag while rates stay positive`() {
        val clip =
            ClipNode(
                clipId = "clip-reverse",
                trackId = "v1",
                timelineInUs = 0L,
                timelineOutUs = 1_000_000L,
                reversed = true,
            )

        assertTrue(clip.reversed)
        assertEquals(1f, (clip.timeRemap as TimeRemapSpec.ConstantRate).rate)
        assertFailsWith<IllegalArgumentException> { TimeRemapSpec.ConstantRate(-2f) }
    }
}
