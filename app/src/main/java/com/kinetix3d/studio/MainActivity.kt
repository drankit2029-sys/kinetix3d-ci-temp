/*
 * Kinetix3D Studio — thin application shell.
 * All engine/UI work lives in the library modules; this class only boots the
 * studio surface hosted by :ui-studio and forwards the Application-owned live
 * device profile / crash-guard state.
 */

package com.kinetix3d.studio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.kinetix3d.platform.project.ProjectCatalog
import com.kinetix3d.platform.project.ProjectStore
import com.kinetix3d.ui.KinetixStudioApp
import com.kinetix3d.ui.demoMediaPath
import com.kinetix3d.ui.demoProjectState
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val kinetix = application as KinetixApplication
        setContent {
            val deviceProfile by kinetix.deviceProfile.collectAsState()
            val renderGuard by kinetix.renderGuard.collectAsState()
            val exportState by ExportForegroundService.state.collectAsState()
            val context = LocalContext.current
            var activeProjectId by remember { mutableStateOf("demo") }
            val projectStore =
                remember(activeProjectId) {
                    ProjectStore.create(context.applicationContext, activeProjectId)
                }
            val catalog = remember { ProjectCatalog(File(context.filesDir, "projects")) }
            var projects by remember { mutableStateOf(catalog.list()) }
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            DisposableEffect(projectStore) {
                onDispose { projectStore.close() }
            }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            KinetixStudioApp(
                deviceProfile = deviceProfile,
                renderGuard = renderGuard,
                projectStore = projectStore,
                platformApi = kinetix.platformApi,
                mediaPath = demoMediaPath(context),
                exportState = exportState,
                onExport = { request -> ExportForegroundService.start(context, request) },
                projects = projects,
                onOpenProject = { projectId ->
                    activeProjectId = projectId
                    projects = catalog.list()
                },
                onNewProject = {
                    val projectId = "project-${System.currentTimeMillis()}"
                    catalog.create(projectId, demoProjectState().copy(projectId = projectId))
                    projects = catalog.list()
                    activeProjectId = projectId
                },
            )
        }
    }
}
