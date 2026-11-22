/*
 * Kinetix3D Studio — app
 * Foreground export service: long renders keep running while the studio is
 * backgrounded, with a progress notification and atomic MediaStore publish.
 */

package com.kinetix3d.studio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.platform.HardwareCapabilityProfiler
import com.kinetix3d.platform.PlatformApiProvider
import com.kinetix3d.render.demoClipGraph
import com.kinetix3d.render.demoEffectsGraph
import com.kinetix3d.render.demoKernelGraph
import com.kinetix3d.render.export.HardwareExportSession
import com.kinetix3d.ui.ExportRequest
import com.kinetix3d.ui.ExportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ExportForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val request = intent?.toExportRequest()
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification(0f, "Preparing export…"))
        mutableState.value = ExportState.Running(0f)
        job?.cancel()
        job =
            scope.launch {
                try {
                    val profile =
                        HardwareCapabilityProfiler(this@ExportForegroundService).loadCachedProfile()
                            ?: DeviceCapabilityProfile.tier2Default()
                    val output = File(cacheDir, "kinetix-export-${System.currentTimeMillis()}.mp4")
                    val result =
                        HardwareExportSession(
                            sourcePath = request.sourcePath,
                            graph = graphFor(request.effects),
                            comp =
                                CompSettings(
                                    width = request.width,
                                    height = request.height,
                                    fps = request.fps,
                                ),
                            profile = profile,
                            outputFile = output,
                            durationUs = request.durationUs,
                            startUs = request.startUs,
                        ).export { progress ->
                            mutableState.value = ExportState.Running(progress)
                            updateNotification(progress)
                        }
                    val api = PlatformApiProvider.create(this@ExportForegroundService, profile)
                    val uri =
                        api.storageAndExport.publishExportAtomically(
                            result.file,
                            result.file.name,
                            result.mimeType,
                        )
                    output.delete()
                    mutableState.value = ExportState.Success(uri.toString(), result.frames)
                    updateNotification(1f, "Export complete")
                } catch (throwable: Throwable) {
                    mutableState.value = ExportState.Failure(throwable.message ?: "Export failed")
                    updateNotification(0f, "Export failed")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun graphFor(effects: List<String>) =
        when {
            effects.isEmpty() -> demoClipGraph()

            effects == listOf("color") -> demoEffectsGraph()

            // The export session currently renders the base source; the composite
            // overlay path is a preview-only feature until the export compositor lands.
            effects == listOf("composite") -> demoClipGraph()

            else -> demoKernelGraph(effects)
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Export", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(
        progress: Float,
        text: String,
    ) = NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Kinetix3D export")
        .setContentText(text)
        .setProgress(100, (progress * 100f).toInt(), false)
        .setOngoing(true)
        .build()

    private fun updateNotification(
        progress: Float,
        text: String = "Exporting…",
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(progress, text))
    }

    companion object {
        private const val CHANNEL_ID = "kinetix-export"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_START_US = "startUs"
        private const val EXTRA_DURATION_US = "durationUs"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_EFFECTS = "effects"

        private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
        val state: StateFlow<ExportState> = mutableState.asStateFlow()

        fun start(
            context: Context,
            request: ExportRequest,
        ) {
            val intent =
                Intent(context, ExportForegroundService::class.java).apply {
                    putExtra(EXTRA_SOURCE, request.sourcePath)
                    putExtra(EXTRA_START_US, request.startUs)
                    putExtra(EXTRA_DURATION_US, request.durationUs)
                    putExtra(EXTRA_WIDTH, request.width)
                    putExtra(EXTRA_HEIGHT, request.height)
                    putExtra(EXTRA_FPS, request.fps)
                    putStringArrayListExtra(EXTRA_EFFECTS, ArrayList(request.effects))
                }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun Intent.toExportRequest(): ExportRequest? {
            val source = getStringExtra(EXTRA_SOURCE) ?: return null
            return ExportRequest(
                sourcePath = source,
                startUs = getLongExtra(EXTRA_START_US, 0L),
                durationUs = getLongExtra(EXTRA_DURATION_US, 1_000_000L),
                width = getIntExtra(EXTRA_WIDTH, 1920),
                height = getIntExtra(EXTRA_HEIGHT, 1080),
                fps = getDoubleExtra(EXTRA_FPS, 30.0),
                effects = getStringArrayListExtra(EXTRA_EFFECTS).orEmpty(),
            )
        }
    }
}
