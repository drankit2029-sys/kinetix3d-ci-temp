/*
 * Kinetix3D Studio — engine-render
 * Minimal EGL 3.x core for the RenderThread. The default display is a
 * process-wide singleton: never call eglTerminate on it.
 */

package com.kinetix3d.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    var isEs3: Boolean = false
        private set

    fun initialize(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

        val es3Config = chooseConfig(EGL_OPENGL_ES3_BIT_KHR)
        val chosen = es3Config ?: chooseConfig(EGL_OPENGL_ES2_BIT) ?: return false
        val clientVersion = if (es3Config != null) 3 else 2
        val contextAttributes =
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                clientVersion,
                EGL14.EGL_NONE,
            )
        context = EGL14.eglCreateContext(display, chosen, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        config = chosen
        isEs3 = es3Config != null
        return true
    }

    fun createWindowSurface(window: Surface): Boolean {
        val chosen = config ?: return false
        if (display == EGL14.EGL_NO_DISPLAY) return false
        releaseSurface()
        val attributes = intArrayOf(EGL14.EGL_NONE)
        surface = EGL14.eglCreateWindowSurface(display, chosen, window, attributes, 0)
        return surface != EGL14.EGL_NO_SURFACE
    }

    fun makeCurrent(): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return false
        if (context == EGL14.EGL_NO_CONTEXT) return false
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun swapBuffers(): Boolean {
        if (surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun releaseSurface() {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            surface = EGL14.EGL_NO_SURFACE
        }
    }

    fun destroy() {
        releaseSurface()
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, context)
            context = EGL14.EGL_NO_CONTEXT
        }
        // Never eglTerminate(display): EGL_DEFAULT_DISPLAY is shared with Compose
        // and any other EGL consumer in the process.
    }

    private fun chooseConfig(renderableType: Int): EGLConfig? {
        val attributes =
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                renderableType,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok =
            EGL14.eglChooseConfig(
                display,
                attributes,
                0,
                configs,
                0,
                1,
                count,
                0,
            )
        return if (ok && count[0] > 0) configs[0] else null
    }

    companion object {
        private const val TAG = "KinetixEglCore"
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val EGL_OPENGL_ES2_BIT = 0x0004
    }
}
