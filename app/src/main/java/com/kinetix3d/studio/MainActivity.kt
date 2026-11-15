/*
 * Kinetix3D Studio — thin application shell.
 * All engine/UI work lives in the library modules; this class only boots the
 * studio surface hosted by :ui-studio.
 */

package com.kinetix3d.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kinetix3d.ui.KinetixStudioApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KinetixStudioApp()
        }
    }
}
