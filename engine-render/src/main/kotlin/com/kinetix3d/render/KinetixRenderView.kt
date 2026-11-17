/*
 * Kinetix3D Studio — engine-render
 * SurfaceView host for the Phase 2 RenderThread. The demo ticket exercises the
 * planner and the test kernel until the timeline/UI project state lands.
 */

package com.kinetix3d.render

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.CompositionGraph
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.GraphNode
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.NodeInput
import com.kinetix3d.model.NodeSchemaIds
import com.kinetix3d.model.ParamChannel
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.TransientOverrideMap
import kotlinx.collections.immutable.persistentMapOf
import java.io.File

class KinetixRenderView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SurfaceView(context, attrs),
        SurfaceHolder.Callback {
        private val renderThread = RenderThread(context.filesDir)
        private var profile = DeviceCapabilityProfile.tier2Default()
        private var ticketPosted = false

        init {
            renderThread.setSourcePath(findTestMedia())
            holder.addCallback(this)
            holder.setFormat(PixelFormat.RGBA_8888)
        }

        fun setDeviceProfile(profile: DeviceCapabilityProfile) {
            this.profile = profile
            renderThread.setProfile(profile)
            if (ticketPosted) postDemoTicket()
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            renderThread.onSurfaceCreated(holder.surface)
            postDemoTicket()
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            renderThread.onSurfaceChanged(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            renderThread.onSurfaceDestroyed()
        }

        override fun onDetachedFromWindow() {
            renderThread.close()
            super.onDetachedFromWindow()
        }

        private fun postDemoTicket() {
            ticketPosted = true
            val settings = CompSettings(width = 3840, height = 2160, fps = 60.0)
            val state =
                ProjectState(
                    projectId = "demo",
                    settings = settings,
                    compositions =
                        persistentMapOf(
                            "main" to CompositionGraph("main", "Main", settings),
                        ),
                    activeCompId = "main",
                )
            renderThread.setTicket(
                RenderTicket(
                    state = state,
                    overrides = TransientOverrideMap.EMPTY,
                    ptsUs = 0L,
                    previewScale = 1.0f,
                    generation = 1L,
                    graph = demoGraph(),
                ),
            )
        }

        private fun findTestMedia(): String? {
            val directory = context.getExternalFilesDir(null) ?: return null
            return listOf(
                "kinetix-test.png",
                "kinetix-test.jpg",
                "kinetix-test.jpeg",
                "kinetix-test.mp4",
                "kinetix-test.mov",
            ).map { File(directory, it) }
                .firstOrNull { it.isFile }
                ?.absolutePath
        }

        private fun demoGraph(): NodeGraph {
            val base = NodeGraph.passthrough()
            val input = base.nodes.getValue(NodeSchemaIds.GROUP_INPUT)
            val output = base.nodes.getValue(NodeSchemaIds.GROUP_OUTPUT)
            val color =
                GraphNode(
                    nodeId = "color",
                    schemaId = "kinetix.color-suite",
                    inputs = persistentMapOf("clipSource" to NodeInput.FromNode(input.nodeId)),
                    params =
                        persistentMapOf(
                            "contrast" to ParamChannel.constant(1.15f),
                            "saturation" to ParamChannel.constant(1.3f),
                            "temperature" to ParamChannel.constant(0.1f),
                        ),
                )
            val blur =
                GraphNode(
                    nodeId = "blur",
                    schemaId = "kinetix.blur",
                    inputs = persistentMapOf("clipSource" to NodeInput.FromNode(color.nodeId)),
                    params = persistentMapOf("radius" to ParamChannel.constant(0.02f)),
                )
            val glow =
                GraphNode(
                    nodeId = "glow",
                    schemaId = "kinetix.glow",
                    inputs = persistentMapOf("clipSource" to NodeInput.FromNode(blur.nodeId)),
                    params =
                        persistentMapOf(
                            "threshold" to ParamChannel.constant(0.8f),
                            "intensity" to ParamChannel.constant(0.4f),
                            "radius" to ParamChannel.constant(0.03f),
                        ),
                )
            return base.copy(
                nodes =
                    base.nodes
                        .putting(color.nodeId, color)
                        .putting(blur.nodeId, blur)
                        .putting(glow.nodeId, glow)
                        .putting(
                            output.nodeId,
                            output.copy(inputs = persistentMapOf("clipSource" to NodeInput.FromNode(glow.nodeId))),
                        ),
            )
        }
    }
