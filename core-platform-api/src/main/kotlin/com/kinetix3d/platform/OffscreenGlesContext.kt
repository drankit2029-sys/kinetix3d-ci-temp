/*
 * Kinetix3D Studio — core-platform-api
 * Offscreen GLES probe context. EGL contexts are thread-affine, so callers must
 * create, query and close this object on the same thread (the profiler's probe
 * thread, never Dispatchers.Default).
 */

package com.kinetix3d.platform

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30

internal class OffscreenGlesContext private constructor(
    private val display: EGLDisplay,
    private val surface: EGLSurface,
    private val eglContext: EGLContext,
    val renderer: String?,
    val version: String?,
    val extensions: Set<String>,
    val maxTextureSize: Int,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, eglContext)
        // Never call eglTerminate(display): EGL_DEFAULT_DISPLAY is the process-wide
        // singleton shared with Compose's hardware renderer and the Phase 2
        // RenderThread. Terminating it would crash those threads with EGL_BAD_DISPLAY.
    }

    companion object {
        // Not exposed by android.opengl.EGL14; khronos value for EGL_OPENGL_ES3_BIT_KHR.
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val PBUFFER_SIZE = 1

        fun create(): OffscreenGlesContext? {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == null || display == EGL14.EGL_NO_DISPLAY) return null
            val eglVersion = IntArray(2)
            if (!EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1)) return null

            var createdSurface: EGLSurface? = null
            var createdContext: EGLContext? = null
            var success = false
            try {
                val es3Config = chooseConfig(display, EGL_OPENGL_ES3_BIT_KHR)
                val config = es3Config ?: chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT) ?: return null
                val clientVersion = if (es3Config != null) 3 else 2
                val pbufferAttributes =
                    intArrayOf(
                        EGL14.EGL_WIDTH,
                        PBUFFER_SIZE,
                        EGL14.EGL_HEIGHT,
                        PBUFFER_SIZE,
                        EGL14.EGL_NONE,
                    )
                val surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttributes, 0)
                if (surface == null || surface == EGL14.EGL_NO_SURFACE) return null
                createdSurface = surface
                val contextAttributes =
                    intArrayOf(
                        EGL14.EGL_CONTEXT_CLIENT_VERSION,
                        clientVersion,
                        EGL14.EGL_NONE,
                    )
                val eglContext =
                    EGL14.eglCreateContext(
                        display,
                        config,
                        EGL14.EGL_NO_CONTEXT,
                        contextAttributes,
                        0,
                    )
                if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) return null
                createdContext = eglContext
                if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return null

                val extensions = queryExtensions(indexed = clientVersion >= 3)
                val textureSize = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureSize, 0)
                val result =
                    OffscreenGlesContext(
                        display = display,
                        surface = surface,
                        eglContext = eglContext,
                        renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                        version = GLES20.glGetString(GLES20.GL_VERSION),
                        extensions = extensions,
                        maxTextureSize = textureSize[0],
                    )
                success = true
                return result
            } catch (throwable: Throwable) {
                return null
            } finally {
                if (!success) {
                    createdSurface?.let { pending -> EGL14.eglDestroySurface(display, pending) }
                    createdContext?.let { pending -> EGL14.eglDestroyContext(display, pending) }
                    // Do NOT eglTerminate here either: the display may already be
                    // in use by Compose/Phase 2 on this process.
                }
            }
        }

        private fun chooseConfig(
            display: EGLDisplay,
            renderableType: Int,
        ): EGLConfig? {
            val attributes =
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    renderableType,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_PBUFFER_BIT,
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
            val chosen = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            if (!chosen || count[0] == 0) return null
            return configs[0]
        }

        private fun queryExtensions(indexed: Boolean): Set<String> {
            if (!indexed) {
                // GLES 2.0 fallback for devices without an ES 3.x config.
                return GLES20
                    .glGetString(GLES20.GL_EXTENSIONS)
                    ?.split(' ')
                    ?.filter { extension -> extension.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet()
            }
            val count = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
            val extensions = LinkedHashSet<String>(count[0].coerceAtLeast(0))
            for (index in 0 until count[0]) {
                val extension = GLES30.glGetStringi(GLES30.GL_EXTENSIONS, index) ?: continue
                extensions.add(extension)
            }
            return extensions
        }
    }
}
