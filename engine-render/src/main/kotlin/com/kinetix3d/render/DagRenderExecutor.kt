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
) : AutoCloseable {
    private val passThrough = PassThroughKernel()
    private val colorSuite = ColorSuiteKernel()
    private val blur = BlurKernel()
    private val tiles = TilesKernel()
    private val glow = GlowKernel()

    fun initialize(): Boolean =
        source.initialize() &&
            passThrough.initialize() &&
            colorSuite.initialize() &&
            blur.initialize() &&
            tiles.initialize() &&
            glow.initialize()

    fun render(
        plan: RenderPlan,
        graph: NodeGraph,
        windowWidth: Int,
        windowHeight: Int,
    ): Boolean {
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
                        val out = pool.acquire(roiWidth, roiHeight) ?: return false
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
                        currentHandle?.let { pool.release(it) }
                        currentHandle = out
                        currentFramebuffer = out.framebufferId
                        currentTexture = out.textureId
                        checkGl("group-input")
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

                    else -> {
                        Unit
                    }
                }
            }
            currentHandle?.let { pool.release(it) }
        }
        return true
    }

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
        colorSuite.release()
        blur.release()
        tiles.release()
        glow.release()
        source.release()
    }

    companion object {
        private const val TAG = "KinetixDagExecutor"
    }
}
