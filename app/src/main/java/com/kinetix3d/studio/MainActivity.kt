/*
 * Kinetix3D Studio — thin application shell.
 * All engine/UI work lives in the library modules; this class only boots the
 * studio surface hosted by :ui-studio and forwards the Application-owned live
 * device profile / crash-guard state.
 */

package com.kinetix3d.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kinetix3d.platform.project.ProjectStore
import com.kinetix3d.ui.KinetixStudioApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val kinetix = application as KinetixApplication
        setContent {
            val deviceProfile by kinetix.deviceProfile.collectAsState()
            val renderGuard by kinetix.renderGuard.collectAsState()
            val context = LocalContext.current
            val projectStore = remember { ProjectStore.create(context.applicationContext, "demo") }
            DisposableEffect(projectStore) {
                onDispose { projectStore.close() }
            }
            KinetixStudioApp(
                deviceProfile = deviceProfile,
                renderGuard = renderGuard,
                projectStore = projectStore,
            )
        }
    }
}
