/*
 * Kinetix3D Studio — engine-render
 * Phase 2 test-only kernel: a procedural pattern rendered through the FBO pool
 * and blitted to the window. Production operator shaders land in Phase 5.
 */

package com.kinetix3d.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class TestPatternRenderer {
    private var program = 0
    private var vertexBufferId = 0
    private var positionLocation = -1
    private var timeLocation = -1
    private var resolutionLocation = -1

    private val vertexBuffer: FloatBuffer =
        ByteBuffer
            .allocateDirect(VERTICES.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(VERTICES)
                position(0)
            }

    fun initialize(): Boolean {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertexShader == 0 || fragmentShader == 0) return false
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (status[0] == 0) {
            Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            program = 0
            return false
        }
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        timeLocation = GLES20.glGetUniformLocation(program, "uTime")
        resolutionLocation = GLES20.glGetUniformLocation(program, "uResolution")
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vertexBufferId = buffers[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            VERTICES.size * Float.SIZE_BYTES,
            vertexBuffer,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return positionLocation >= 0 && vertexBufferId != 0
    }

    fun drawToWindow(
        pool: ReferenceCountedFboPool,
        width: Int,
        height: Int,
        timeSeconds: Float,
        es3: Boolean,
    ): Boolean {
        if (!es3) {
            GLES20.glViewport(0, 0, width, height)
            draw(width, height, timeSeconds)
            return true
        }
        val handle = pool.acquire(width, height)
        if (handle == null) {
            GLES20.glViewport(0, 0, width, height)
            draw(width, height, timeSeconds)
            return true
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, handle.framebufferId)
        GLES20.glViewport(0, 0, width, height)
        draw(width, height, timeSeconds)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, handle.framebufferId)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES30.glBlitFramebuffer(
            0,
            0,
            width,
            height,
            0,
            0,
            width,
            height,
            GLES20.GL_COLOR_BUFFER_BIT,
            GLES20.GL_NEAREST,
        )
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "GL error after blit: 0x${Integer.toHexString(error)}")
        }
        pool.release(handle)
        return true
    }

    fun release() {
        if (vertexBufferId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
            vertexBufferId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun draw(
        width: Int,
        height: Int,
        timeSeconds: Float,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(timeLocation, timeSeconds)
        GLES20.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun compile(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "KinetixTestPattern"
        private val VERTICES = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform float uTime;
            uniform vec2 uResolution;
            void main() {
                vec2 p = vUv * uResolution / max(uResolution.y, 1.0);
                float checker = mod(floor(p.x * 8.0) + floor(p.y * 8.0), 2.0);
                vec3 base = mix(vec3(0.05, 0.07, 0.10), vec3(0.10, 0.16, 0.22), checker);
                float bar = smoothstep(0.02, 0.0, abs(vUv.x - fract(uTime * 0.15)));
                vec3 color = base + vec3(0.22, 0.88, 0.78) * bar;
                color += vec3(0.05, 0.02, 0.12) * (1.0 - vUv.y);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
