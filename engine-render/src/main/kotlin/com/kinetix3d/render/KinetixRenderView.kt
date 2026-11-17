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
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.TransientOverrideMap
import kotlinx.collections.immutable.persistentMapOf

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
            val settings = CompSettings(width = 1920, height = 1080, fps = 60.0)
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
                    previewScale = 0.5f,
                    generation = 1L,
                ),
            )
        }
    }
