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
import com.kinetix3d.media.DecoderPool
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.DirtyRenderLock
import com.kinetix3d.render.plan.DagRenderPlanner
import com.kinetix3d.render.plan.RenderPlan
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RenderThread(
    filesDir: File,
) : AutoCloseable {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val pendingTicket = AtomicReference<RenderTicket?>(null)
    private val frameRequested = AtomicBoolean(false)
    private val planner = DagRenderPlanner()
    private val renderLock = DirtyRenderLock(File(filesDir, DirtyRenderLock.DIRECTORY_NAME))

    @Volatile
    private var profile: DeviceCapabilityProfile? = null

    @Volatile
    private var sourcePath: String? = null

    private var lastTicket: RenderTicket? = null

    private var eglCore: EglCore? = null
    private var renderer: TestPatternRenderer? = null
    private var executor: DagRenderExecutor? = null
    private var pool: ReferenceCountedFboPool? = null
    private var decoderPool: DecoderPool? = null
    private var surface: Surface? = null
    private var width = 1
    private var height = 1
    private var generation = 0L
    private var lastFrameNanos = 0L
    private var hasRenderedTicket = false
    private var lastRenderNanos = 0L
    private var emaFrameNanos = 0.0
    private var emaFps = 0.0
    private var lastStatsPostNanos = 0L

    @Volatile
    private var statsListener: ((RenderStats) -> Unit)? = null

    fun setStatsListener(listener: ((RenderStats) -> Unit)?) {
        statsListener = listener
    }

    fun setProfile(profile: DeviceCapabilityProfile) {
        this.profile = profile
        requestRender()
    }

    fun setSourcePath(path: String?) {
        handler.post {
            if (sourcePath == path) return@post
            sourcePath = path
            runCatching { eglCore?.makeCurrent() }
            executor?.close()
            executor = null
            decoderPool?.close()
            decoderPool = null
            createSource()
            lastTicket?.let { pendingTicket.set(it) }
            renderFrame()
        }
    }

    fun setTicket(ticket: RenderTicket) {
        lastTicket = ticket
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
            lastTicket?.let { pendingTicket.set(it) }
            requestRender()
        }
    }

    fun onSurfaceDestroyed() {
        handler.post { releaseGl() }
    }

    fun requestRender() {
        handler.post { renderFrame() }
    }

    /** Coalesced re-render triggered by a decoded video frame. */
    private fun requestFrame() {
        if (!frameRequested.compareAndSet(false, true)) return
        handler.post {
            frameRequested.set(false)
            lastTicket?.let { pendingTicket.compareAndSet(null, it) }
            renderFrame()
        }
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
        val fboPool = ReferenceCountedFboPool(budgetMb.toLong() * 1024L * 1024L)
        eglCore = core
        renderer = pattern
        pool = fboPool
        hasRenderedTicket = false
        Log.d(TAG, "EGL ready es3=${core.isEs3}")
        createSource()
        renderFrame()
    }

    private fun createSource() {
        val core = eglCore ?: return
        val fboPool = pool ?: return
        if (!core.isEs3) {
            Log.w(TAG, "ES2 context; DAG executor disabled")
            return
        }
        val path = sourcePath
        val decoders = (profile?.maxHwDecoders ?: DEFAULT_HW_DECODERS).coerceAtLeast(1)
        val decoderLeases = DecoderPool(capacity = decoders, reserved = if (decoders > 1) 1 else 0)
        decoderPool = decoderLeases
        Log.d(TAG, "decoder pool capacity=${decoderLeases.capacity} reserved=${decoderLeases.stats().reserved}")
        val source =
            when {
                path == null -> {
                    ProceduralSource()
                }

                path.endsWith(".mp4", true) || path.endsWith(".mov", true) -> {
                    MediaCodecSource(path, decoderLeases) { requestFrame() }
                }

                else -> {
                    MediaSource(path)
                }
            }
        val dag = DagRenderExecutor(fboPool, source)
        if (runCatching { dag.initialize() }.getOrDefault(false)) {
            executor = dag
        } else {
            Log.e(TAG, "DAG executor initialization failed; using test pattern")
            dag.close()
        }
    }

    private fun renderFrame() {
        val core = eglCore ?: return
        val pattern = renderer ?: return
        val fboPool = pool ?: return
        if (!core.makeCurrent()) return
        val startNanos = System.nanoTime()

        val ticket = pendingTicket.getAndSet(null)
        if (ticket != null) {
            val plan =
                runCatching {
                    planner.plan(
                        graph = ticket.graph,
                        comp = ticket.state.activeComposition().settings,
                        previewScale = ticket.previewScale,
                        profile = profile ?: DeviceCapabilityProfile.tier2Default(),
                    )
                }.getOrNull()
            if (plan != null) {
                val rendered =
                    executor?.render(
                        plan = plan,
                        graph = ticket.graph,
                        windowWidth = width,
                        windowHeight = height,
                        ptsUs = ticket.ptsUs,
                        playing = ticket.playing,
                    ) ?: false
                if (rendered && core.swapBuffers()) {
                    hasRenderedTicket = true
                    runCatching { renderLock.clearAfterSwap() }
                    recordFrame(startNanos, plan)
                    return
                }
            }
        }

        if (hasRenderedTicket) return

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
            recordFrame(startNanos, null)
        } else {
            Log.w(TAG, "frame draw/swap failed")
        }
    }

    private fun recordFrame(
        startNanos: Long,
        plan: RenderPlan?,
    ) {
        val now = System.nanoTime()
        val frameNanos = (now - startNanos).coerceAtLeast(0L)
        emaFrameNanos =
            if (emaFrameNanos == 0.0) {
                frameNanos.toDouble()
            } else {
                emaFrameNanos * 0.9 + frameNanos * 0.1
            }
        if (lastRenderNanos != 0L) {
            val interval = (now - lastRenderNanos).coerceAtLeast(1L)
            val fps = 1_000_000_000.0 / interval
            emaFps = if (emaFps == 0.0) fps else emaFps * 0.9 + fps * 0.1
        }
        lastRenderNanos = now
        if (now - lastStatsPostNanos < STATS_INTERVAL_NANOS) return
        lastStatsPostNanos = now
        statsListener?.invoke(
            RenderStats(
                fps = emaFps.toFloat(),
                frameMillis = (emaFrameNanos / 1_000_000.0).toFloat(),
                tiles = plan?.tiles?.size ?: 0,
                format = plan?.format?.name.orEmpty(),
            ),
        )
    }

    private fun releaseGl() {
        runCatching { eglCore?.makeCurrent() }
        executor?.close()
        executor = null
        decoderPool?.close()
        decoderPool = null
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
        private const val DEFAULT_HW_DECODERS = 4
        private const val STATS_INTERVAL_NANOS = 250_000_000L
    }
}
