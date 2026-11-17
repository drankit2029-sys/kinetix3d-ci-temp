/*
 * Kinetix3D Studio — engine-render
 * Dedicated EGL/GLES render thread with a single-slot, non-blocking mailbox.
 * Phase 2 draws the test-only kernel; the DAG executor consumes the same plan.
 */

package com.kinetix3d.render

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.DirtyRenderLock
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.render.plan.DagRenderPlanner
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class RenderThread(
    filesDir: File,
) : AutoCloseable {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val pendingTicket = AtomicReference<RenderTicket?>(null)
    private val planner = DagRenderPlanner()
    private val renderLock = DirtyRenderLock(File(filesDir, DirtyRenderLock.DIRECTORY_NAME))

    @Volatile
    private var profile: DeviceCapabilityProfile? = null

    private var eglCore: EglCore? = null
    private var renderer: TestPatternRenderer? = null
    private var pool: ReferenceCountedFboPool? = null
    private var surface: Surface? = null
    private var width = 1
    private var height = 1
    private var generation = 0L
    private var lastFrameNanos = 0L

    fun setProfile(profile: DeviceCapabilityProfile) {
        this.profile = profile
        requestRender()
    }

    fun setTicket(ticket: RenderTicket) {
        pendingTicket.set(ticket)
        requestRender()
    }

    fun onSurfaceCreated(surface: Surface) {
        handler.post {
            this.surface = surface
            initializeGl()
        }
    }

    fun onSurfaceChanged(
        width: Int,
        height: Int,
    ) {
        handler.post {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            requestRender()
        }
    }

    fun onSurfaceDestroyed() {
        handler.post { releaseGl() }
    }

    fun requestRender() {
        handler.post { renderFrame() }
    }

    private fun initializeGl() {
        releaseGl()
        val core = EglCore()
        if (!core.initialize()) {
            Log.e(TAG, "EGL initialize failed")
            return
        }
        val window = surface
        if (window == null || !core.createWindowSurface(window)) {
            Log.e(TAG, "EGL window surface failed")
            core.destroy()
            return
        }
        if (!core.makeCurrent()) {
            Log.e(TAG, "EGL makeCurrent failed")
            core.destroy()
            return
        }
        val pattern = TestPatternRenderer()
        if (!pattern.initialize()) {
            Log.e(TAG, "test pattern shader failed")
            core.destroy()
            return
        }
        val budgetMb = profile?.safeVramBudgetMb ?: DeviceCapabilityProfile.TIER2_VRAM_BUDGET_MB
        eglCore = core
        renderer = pattern
        pool = ReferenceCountedFboPool(budgetMb.toLong() * 1024L * 1024L)
        renderFrame()
    }

    private fun renderFrame() {
        val core = eglCore ?: return
        val pattern = renderer ?: return
        val fboPool = pool ?: return
        if (!core.makeCurrent()) return

        val ticket = pendingTicket.getAndSet(null)
        if (ticket != null) {
            val plan =
                runCatching {
                    planner.plan(
                        graph = NodeGraph.passthrough(),
                        comp = ticket.state.activeComposition().settings,
                        previewScale = ticket.previewScale,
                        profile = profile ?: DeviceCapabilityProfile.tier2Default(),
                    )
                }.getOrNull()
            if (plan != null) {
                Log.d(
                    TAG,
                    "plan tiles=${plan.tiles.size} format=${plan.format} " +
                        "reduced=${plan.reducedResolution} budget=${plan.budgetBytes}",
                )
            }
        }

        val now = System.nanoTime()
        val timeSeconds =
            if (lastFrameNanos == 0L) {
                0f
            } else {
                ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(10f)
            }
        lastFrameNanos = now
        generation = (generation + 1L).coerceAtLeast(1L)
        runCatching { renderLock.beginFrame(generation, 0L) }
        val drawn = pattern.drawToWindow(fboPool, width, height, timeSeconds, core.isEs3)
        if (drawn && core.swapBuffers()) {
            runCatching { renderLock.clearAfterSwap() }
        } else {
            Log.w(TAG, "frame draw/swap failed")
        }
    }

    private fun releaseGl() {
        renderer?.release()
        renderer = null
        pool?.close()
        pool = null
        eglCore?.destroy()
        eglCore = null
    }

    override fun close() {
        handler.post {
            releaseGl()
            renderLock.close()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "KinetixRenderThread"
        private const val THREAD_NAME = "KinetixRenderThread"
    }
}
