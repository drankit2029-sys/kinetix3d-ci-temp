/*
 * Kinetix3D Studio — engine-render
 * Hardware export: render the DAG into a MediaCodec input surface, drain the
 * encoder into a MediaMuxer, and hand the temp file to the caller for atomic
 * MediaStore publication. Runs on a dedicated thread with its own EGL context.
 */

package com.kinetix3d.render.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.render.DagRenderExecutor
import com.kinetix3d.render.EglCore
import com.kinetix3d.render.ReferenceCountedFboPool
import com.kinetix3d.render.SynchronousVideoSource
import com.kinetix3d.render.plan.DagRenderPlanner
import com.kinetix3d.render.plan.FboBudget
import java.io.File
import java.util.concurrent.CancellationException

data class ExportResult(
    val file: File,
    val frames: Int,
    val durationUs: Long,
    val mimeType: String,
)

class HardwareExportSession(
    private val sourcePath: String,
    private val graph: NodeGraph,
    private val comp: CompSettings,
    private val profile: DeviceCapabilityProfile,
    private val outputFile: File,
    private val durationUs: Long,
    private val startUs: Long = 0L,
    private val overlayPath: String? = null,
    private val overlayPtsOffsetUs: Long = 0L,
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val bitRate: Int = DEFAULT_BIT_RATE,
) {
    fun export(
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ExportResult {
        val width = comp.width
        val height = comp.height
        val fps = comp.fps
        val rangeUs = (durationUs - startUs).coerceAtLeast(1L)
        val frameCount = ((rangeUs / 1_000_000.0) * fps).toInt().coerceAtLeast(1)
        val format =
            MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        val encoder = MediaCodec.createEncoderByType(mimeType)
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var core: EglCore? = null
        var pool: ReferenceCountedFboPool? = null
        var source: SynchronousVideoSource? = null
        var overlaySource: SynchronousVideoSource? = null
        var executor: DagRenderExecutor? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            outputFile.parentFile?.mkdirs()
            val mediaMuxer =
                MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            val egl = EglCore()
            check(egl.initialize()) { "EGL initialize failed" }
            core = egl
            check(egl.createWindowSurface(inputSurface)) { "EGL encoder surface failed" }
            check(egl.makeCurrent()) { "EGL makeCurrent failed" }
            val fboPool = ReferenceCountedFboPool(FboBudget.forProfile(profile).exportBudgetBytes)
            pool = fboPool
            val videoSource = SynchronousVideoSource(sourcePath)
            source = videoSource
            val overlay = overlayPath?.let { SynchronousVideoSource(it) }
            overlaySource = overlay
            val dag = DagRenderExecutor(fboPool, videoSource, overlay)
            executor = dag
            check(dag.initialize()) { "DAG executor initialization failed" }
            val plan =
                DagRenderPlanner().plan(
                    graph = graph,
                    comp = comp,
                    previewScale = 1f,
                    profile = profile,
                    export = true,
                )
            val muxerState = MuxerState()
            val info = MediaCodec.BufferInfo()
            for (frameIndex in 0 until frameCount) {
                if (isCancelled()) throw CancellationException("Export cancelled")
                val ptsUs = startUs + (frameIndex * 1_000_000.0 / fps).toLong()
                videoSource.update(ptsUs)
                check(
                    dag.render(
                        plan = plan,
                        graph = graph,
                        windowWidth = width,
                        windowHeight = height,
                        ptsUs = ptsUs,
                        playing = false,
                        overlayPtsUs = ptsUs + overlayPtsOffsetUs,
                    ),
                ) {
                    "DAG render failed at frame $frameIndex"
                }
                egl.setPresentationTime(ptsUs * 1_000L)
                check(egl.swapBuffers()) { "eglSwapBuffers failed at frame $frameIndex" }
                drainEncoder(encoder, mediaMuxer, info, muxerState, endOfStream = false)
                onProgress((frameIndex + 1).toFloat() / frameCount)
            }
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, mediaMuxer, info, muxerState, endOfStream = true)
            return ExportResult(outputFile, frameCount, rangeUs, mimeType)
        } finally {
            runCatching { executor?.close() }
            runCatching { overlaySource?.release() }
            runCatching { source?.release() }
            runCatching { pool?.close() }
            runCatching { core?.destroy() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { inputSurface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        state: MuxerState,
        endOfStream: Boolean,
    ) {
        val timeoutUs = if (endOfStream) EOS_TIMEOUT_US else 0L
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!state.started) {
                        state.trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        state.started = true
                    }
                }

                outputIndex >= 0 -> {
                    val buffer = encoder.getOutputBuffer(outputIndex)
                    if (buffer != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && state.started) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(state.trackIndex, buffer, info)
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private class MuxerState {
        var trackIndex = -1
        var started = false
    }

    companion object {
        private const val TAG = "KinetixExport"
        private const val DEFAULT_BIT_RATE = 12_000_000
        private const val EOS_TIMEOUT_US = 10_000L
    }
}
