/*
 * Kinetix3D Studio — engine-render
 * Phase 2 DAG executor: walks the planner order, runs the ColorSuite/Blur
 * kernels through the FBO pool, and blits each tile to the window.
 */

package com.kinetix3d.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.NodeSchemaIds
import com.kinetix3d.render.plan.RenderPlan
import com.kinetix3d.render.plan.TilePlan

class DagRenderExecutor(
    private val pool: ReferenceCountedFboPool,
    private val source: SourceTexture = ProceduralSource(),
    private val overlaySource: SourceTexture? = null,
) : AutoCloseable {
    private val passThrough = PassThroughKernel()
    private val externalPassThrough = ExternalPassThroughKernel()
    private val colorSuite = ColorSuiteKernel()
    private val blur = BlurKernel()
    private val tiles = TilesKernel()
    private val glow = GlowKernel()
    private val warp = WarpKernel()
    private val glitch = GlitchKernel()
    private val vectorify = VectorifyKernel()
    private val keyer = KeyerKernel()
    private val composite = CompositeKernel()

    fun initialize(): Boolean =
        source.initialize() &&
            (overlaySource?.initialize() ?: true) &&
            passThrough.initialize() &&
            externalPassThrough.initialize() &&
            colorSuite.initialize() &&
            blur.initialize() &&
            tiles.initialize() &&
            glow.initialize() &&
            warp.initialize() &&
            glitch.initialize() &&
            vectorify.initialize() &&
            keyer.initialize() &&
            composite.initialize()

    fun render(
        plan: RenderPlan,
        graph: NodeGraph,
        windowWidth: Int,
        windowHeight: Int,
        ptsUs: Long,
        playing: Boolean = false,
        overlayPtsUs: Long = ptsUs,
    ): Boolean {
        source.setPlaying(playing)
        source.update(ptsUs)
        overlaySource?.setPlaying(playing)
        overlaySource?.update(overlayPtsUs)
        val viewportWidth =
            plan.tiles
                .maxOf { it.output.right }
                .toInt()
                .coerceAtLeast(1)
        val viewportHeight =
            plan.tiles
                .maxOf { it.output.bottom }
                .toInt()
                .coerceAtLeast(1)
        for (tile in plan.tiles) {
            val roi = tile.requiredRoI
            val roiWidth =
                roi.width
                    .toInt()
                    .coerceAtLeast(1)
            val roiHeight =
                roi.height
                    .toInt()
                    .coerceAtLeast(1)
            val offsetX = roi.left / viewportWidth
            val offsetY = roi.top / viewportHeight
            val scaleX = roiWidth.toFloat() / viewportWidth
            val scaleY = roiHeight.toFloat() / viewportHeight
            var currentFramebuffer = 0
            var currentTexture = source.textureId
            var currentHandle: ReferenceCountedFboPool.Handle? = null

            for (pass in plan.passes) {
                when (pass.schemaId) {
                    NodeSchemaIds.GROUP_INPUT -> {
                        if (pass.nodeId == OVERLAY_INPUT_ID) {
                            // The composite node samples the overlay source directly.
                            Unit
                        } else {
                            val out = pool.acquire(roiWidth, roiHeight) ?: return false
                            if (source.isExternal) {
                                externalPassThrough.render(
                                    inputTexture = source.textureId,
                                    outputFramebuffer = out.framebufferId,
                                    width = roiWidth,
                                    height = roiHeight,
                                    tileOffsetX = offsetX,
                                    tileOffsetY = offsetY,
                                    tileScaleX = scaleX,
                                    tileScaleY = scaleY,
                                    textureMatrix = source.textureMatrix(),
                                )
                            } else {
                                passThrough.render(
                                    inputTexture = source.textureId,
                                    outputFramebuffer = out.framebufferId,
                                    width = roiWidth,
                                    height = roiHeight,
                                    tileOffsetX = offsetX,
                                    tileOffsetY = offsetY,
                                    tileScaleX = scaleX,
                                    tileScaleY = scaleY,
                                )
                            }
                            currentHandle?.let { pool.release(it) }
                            currentHandle = out
                            currentFramebuffer = out.framebufferId
                            currentTexture = out.textureId
                            checkGl("group-input")
                        }
                    }

                    NodeSchemaIds.GROUP_OUTPUT -> {
                        if (currentFramebuffer != 0) {
                            blitToWindow(
                                framebuffer = currentFramebuffer,
                                tile = tile,
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                                windowWidth = windowWidth,
                                windowHeight = windowHeight,
                            )
                            checkGl("group-output")
                        }
                    }

                    "kinetix.color-suite" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        colorSuite.render(
                            inputTexture = currentTexture,
                            outputFramebuffer = out.framebufferId,
                            width = roiWidth,
                            height = roiHeight,
                            tileOffsetX = 0f,
                            tileOffsetY = 0f,
                            tileScaleX = 1f,
                            tileScaleY = 1f,
                            contrast = param(graph, pass.nodeId, "contrast", 1f),
                            saturation = param(graph, pass.nodeId, "saturation", 1f),
                            temperature = param(graph, pass.nodeId, "temperature", 0f),
                        )
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("color-suite")
                    }

                    "kinetix.tiles" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        tiles.render(
                            inputTexture = currentTexture,
                            outputFramebuffer = out.framebufferId,
                            width = roiWidth,
                            height = roiHeight,
                            gridX = param(graph, pass.nodeId, "gridX", 3f),
                            gridY = param(graph, pass.nodeId, "gridY", 3f),
                        )
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("tiles")
                    }

                    "kinetix.blur" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        val radiusPx = param(graph, pass.nodeId, "radius", 0.02f) * viewportHeight
                        val ok =
                            blur.render(
                                inputTexture = currentTexture,
                                outputFramebuffer = out.framebufferId,
                                pool = pool,
                                width = roiWidth,
                                height = roiHeight,
                                radiusPx = radiusPx,
                            )
                        if (!ok) {
                            pool.release(out)
                            currentHandle?.let { pool.release(it) }
                            return false
                        }
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("blur")
                    }

                    "kinetix.glow" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        val ok =
                            glow.render(
                                inputTexture = currentTexture,
                                outputFramebuffer = out.framebufferId,
                                pool = pool,
                                width = roiWidth,
                                height = roiHeight,
                                threshold = param(graph, pass.nodeId, "threshold", 0.8f),
                                intensity = param(graph, pass.nodeId, "intensity", 1f),
                                radiusPx = param(graph, pass.nodeId, "radius", 0.03f) * viewportHeight,
                            )
                        if (!ok) {
                            pool.release(out)
                            currentHandle?.let { pool.release(it) }
                            return false
                        }
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("glow")
                    }

                    "kinetix.warp" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        warp.render(
                            inputTexture = currentTexture,
                            outputFramebuffer = out.framebufferId,
                            width = roiWidth,
                            height = roiHeight,
                            amplitude = param(graph, pass.nodeId, "amplitude", 0.02f),
                            frequency = param(graph, pass.nodeId, "frequency", 4f),
                            seed = param(graph, pass.nodeId, "seed", 0f),
                            timeSeconds = ptsUs / 1_000_000f,
                            mode =
                                modeIndex(
                                    pass.modeId,
                                    "turbulent",
                                    "displace_map",
                                    "wave",
                                    "swirl",
                                    "bulge",
                                    "bezier_mesh",
                                    "liquify",
                                ),
                        )
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("warp")
                    }

                    "kinetix.glitch" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        glitch.render(
                            inputTexture = currentTexture,
                            outputFramebuffer = out.framebufferId,
                            width = roiWidth,
                            height = roiHeight,
                            intensity = param(graph, pass.nodeId, "intensity", 0.5f),
                            seed = param(graph, pass.nodeId, "seed", 0f),
                            blockSize = param(graph, pass.nodeId, "blockSize", 0.015f),
                            timeSeconds = ptsUs / 1_000_000f,
                            mode =
                                modeIndex(
                                    pass.modeId,
                                    "rgb_split",
                                    "block",
                                    "scanline",
                                    "datamosh",
                                    "pixel_sort",
                                    "vhs_crt",
                                    "wave_slice_tear",
                                ),
                        )
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("glitch")
                    }

                    "kinetix.vectorify" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        vectorify.render(
                            inputTexture = currentTexture,
                            outputFramebuffer = out.framebufferId,
                            width = roiWidth,
                            height = roiHeight,
                            levels = param(graph, pass.nodeId, "levels", 6f).toInt().coerceIn(2, 32),
                            mode = modeIndex(pass.modeId, "stylize", "posterize"),
                        )
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("vectorify")
                    }

                    "kinetix.keyer" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        keyer.render(
                            inputTexture = currentTexture,
                            outputFramebuffer = out.framebufferId,
                            width = roiWidth,
                            height = roiHeight,
                            tolerance = param(graph, pass.nodeId, "tolerance", 0.1f),
                            softness = param(graph, pass.nodeId, "softness", 0.05f),
                            despill = param(graph, pass.nodeId, "despill", 0.5f),
                            mode = modeIndex(pass.modeId, "chroma", "polyhedral", "luma"),
                        )
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("keyer")
                    }

                    "kinetix.composite" -> {
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
                        val overlay = overlaySource
                        if (overlay == null) {
                            passThrough.render(
                                inputTexture = currentTexture,
                                outputFramebuffer = out.framebufferId,
                                width = roiWidth,
                                height = roiHeight,
                                tileOffsetX = 0f,
                                tileOffsetY = 0f,
                                tileScaleX = 1f,
                                tileScaleY = 1f,
                            )
                        } else {
                            composite.render(
                                baseTexture = currentTexture,
                                overlayTexture = overlay.textureId,
                                outputFramebuffer = out.framebufferId,
                                width = roiWidth,
                                height = roiHeight,
                                opacity = param(graph, pass.nodeId, "opacity", 1f),
                                overlayExternal = overlay.isExternal,
                                overlayMatrix = overlay.textureMatrix(),
                            )
                        }
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("composite")
                    }

                    else -> {
                        Unit
                    }
                }
            }
            currentHandle?.let { pool.release(it) }
        }
        return true
    }

    private fun modeIndex(
        modeId: String?,
        vararg modes: String,
    ): Int = modes.indexOf(modeId).coerceAtLeast(0)

    private fun param(
        graph: NodeGraph,
        nodeId: String,
        key: String,
        default: Float,
    ): Float =
        graph.nodes[nodeId]
            ?.params
            ?.get(key)
            ?.value ?: default

    private fun checkGl(stage: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "$stage GL error 0x${Integer.toHexString(error)}")
        }
    }

    private fun blitToWindow(
        framebuffer: Int,
        tile: TilePlan,
        viewportWidth: Int,
        viewportHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
    ) {
        val scaleX = windowWidth.toFloat() / viewportWidth
        val scaleY = windowHeight.toFloat() / viewportHeight
        val dstX0 = (tile.output.left * scaleX).toInt()
        val dstY0 = (tile.output.top * scaleY).toInt()
        val dstX1 = (tile.output.right * scaleX).toInt()
        val dstY1 = (tile.output.bottom * scaleY).toInt()
        val roi = tile.requiredRoI
        val sourceX0 = (tile.output.left - roi.left).toInt().coerceAtLeast(0)
        val sourceY0 = (tile.output.top - roi.top).toInt().coerceAtLeast(0)
        val sourceX1 = (sourceX0 + tile.output.width.toInt()).coerceAtMost(roi.width.toInt())
        val sourceY1 = (sourceY0 + tile.output.height.toInt()).coerceAtMost(roi.height.toInt())
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(
            sourceX0,
            sourceY0,
            sourceX1,
            sourceY1,
            dstX0,
            dstY0,
            dstX1,
            dstY1,
            GLES20.GL_COLOR_BUFFER_BIT,
            GLES20.GL_LINEAR,
        )
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
    }

    override fun close() {
        passThrough.release()
        externalPassThrough.release()
        colorSuite.release()
        blur.release()
        tiles.release()
        glow.release()
        warp.release()
        glitch.release()
        vectorify.release()
        keyer.release()
        composite.release()
        overlaySource?.release()
        source.release()
    }

    companion object {
        private const val TAG = "KinetixDagExecutor"
        private const val OVERLAY_INPUT_ID = "overlay-input"
    }
}
