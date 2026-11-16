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
            TilingCapability.LocalNeighborhood(128),
            KinetixOperatorCatalog.blur.tiling,
        )
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
    fun `profile survives a protobuf roundtrip including enum-keyed codec matrix`() {
        val profile =
            DeviceCapabilityProfile(
                tier = HardwareTier.TIER_3,
                maxHwDecoders = 6,
                supportsRgba16f = true,
                supportsAhbZeroCopy = true,
                maxTextureSize = 8192,
                safeVramBudgetMb = 768,
                codecMatrix =
                    mapOf(
                        VideoMime.H264 to MaxResFps(3840, 2160, 60.0),
                        VideoMime.HEVC to MaxResFps(3840, 2160, 60.0),
                        VideoMime.AV1 to MaxResFps(1920, 1080, 120.0),
                    ),
            )

        val decoded =
            ProtoBuf.decodeFromByteArray(
                DeviceCapabilityProfile.serializer(),
                ProtoBuf.encodeToByteArray(DeviceCapabilityProfile.serializer(), profile),
            )

        assertEquals(profile, decoded)
    }

    @Test
    fun `tier2 fallback is marked approximate and conservative`() {
        val fallback = DeviceCapabilityProfile.tier2Default()
        assertTrue(fallback.approximate)
        assertEquals(HardwareTier.TIER_2, fallback.tier)
        assertEquals(false, fallback.supportsAhbZeroCopy)
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
