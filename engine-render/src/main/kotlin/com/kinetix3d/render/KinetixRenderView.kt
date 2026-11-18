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
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.TransientOverrideMap
import kotlinx.collections.immutable.persistentMapOf
import java.io.File
import kotlin.math.abs

class KinetixRenderView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SurfaceView(context, attrs),
        SurfaceHolder.Callback {
        private val renderThread = RenderThread(context.filesDir)
        private var profile = DeviceCapabilityProfile.tier2Default()
        private var projectState: ProjectState? = null
        private var projectGraph: NodeGraph? = null
        private var playheadUs = 0L
        private var lastPostedPlayheadUs = -1L
        private var ticketPosted = false

        init {
            renderThread.setSourcePath(findTestMedia())
            holder.addCallback(this)
            holder.setFormat(PixelFormat.RGBA_8888)
        }

        fun setDeviceProfile(profile: DeviceCapabilityProfile) {
            if (this.profile == profile) return
            this.profile = profile
            renderThread.setProfile(profile)
            if (ticketPosted) postDemoTicket()
        }

        fun setProjectState(
            state: ProjectState,
            graph: NodeGraph,
        ) {
            if (projectState == state && projectGraph == graph) return
            projectState = state
            projectGraph = graph
            if (ticketPosted) postDemoTicket()
        }

        fun setPlayheadUs(ptsUs: Long) {
            playheadUs = ptsUs
            if (abs(ptsUs - lastPostedPlayheadUs) < PLAYHEAD_THROTTLE_US) return
            lastPostedPlayheadUs = ptsUs
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
            val state = projectState ?: demoState()
            renderThread.setTicket(
                RenderTicket(
                    state = state,
                    overrides = TransientOverrideMap.EMPTY,
                    ptsUs = playheadUs,
                    previewScale = 1.0f,
                    generation = 1L,
                    graph = projectGraph ?: demoClipGraph(),
                ),
            )
        }

        private fun demoState(): ProjectState {
            val settings = CompSettings(width = 3840, height = 2160, fps = 60.0)
            return ProjectState(
                projectId = "demo",
                settings = settings,
                compositions =
                    persistentMapOf(
                        "main" to CompositionGraph("main", "Main", settings),
                    ),
                activeCompId = "main",
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

        private companion object {
            private const val PLAYHEAD_THROTTLE_US = 250_000L
        }
    }
