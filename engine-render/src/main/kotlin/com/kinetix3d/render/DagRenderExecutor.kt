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
) : AutoCloseable {
    private val source = ProceduralSource()
    private val passThrough = PassThroughKernel()
    private val colorSuite = ColorSuiteKernel()
    private val blur = BlurKernel()

    fun initialize(): Boolean =
        source.initialize() &&
            passThrough.initialize() &&
            colorSuite.initialize() &&
            blur.initialize()

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
            val tileWidth =
                tile.output.width
                    .toInt()
                    .coerceAtLeast(1)
            val tileHeight =
                tile.output.height
                    .toInt()
                    .coerceAtLeast(1)
            val offsetX = tile.output.left / viewportWidth
            val offsetY = tile.output.top / viewportHeight
            val scaleX = tileWidth.toFloat() / viewportWidth
            val scaleY = tileHeight.toFloat() / viewportHeight
            var currentFramebuffer = 0
            var currentTexture = source.textureId
            var currentHandle: ReferenceCountedFboPool.Handle? = null

            for (pass in plan.passes) {
                when (pass.schemaId) {
                    NodeSchemaIds.GROUP_INPUT -> {
                        val out = pool.acquire(tileWidth, tileHeight) ?: return false
                        passThrough.render(
                            inputTexture = source.textureId,
                            outputFramebuffer = out.framebufferId,
                            width = tileWidth,
                            height = tileHeight,
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
                        val out = pool.acquire(tileWidth, tileHeight) ?: return false
                        colorSuite.render(
                            inputTexture = currentTexture,
                            outputFramebuffer = out.framebufferId,
                            width = tileWidth,
                            height = tileHeight,
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

                    "kinetix.blur" -> {
                        val out = pool.acquire(tileWidth, tileHeight) ?: return false
                        val radiusPx = param(graph, pass.nodeId, "radius", 0.02f) * viewportHeight
                        val ok =
                            blur.render(
                                inputTexture = currentTexture,
                                outputFramebuffer = out.framebufferId,
                                pool = pool,
                                width = tileWidth,
                                height = tileHeight,
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
        val tileWidth =
            tile.output.width
                .toInt()
                .coerceAtLeast(1)
        val tileHeight =
            tile.output.height
                .toInt()
                .coerceAtLeast(1)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(
            0,
            0,
            tileWidth,
            tileHeight,
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
        source.release()
    }

    companion object {
        private const val TAG = "KinetixDagExecutor"
    }
}
