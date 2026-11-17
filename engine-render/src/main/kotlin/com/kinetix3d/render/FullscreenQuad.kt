/*
 * Kinetix3D Studio — engine-render
 * Shared full-screen quad + shader program used by the Phase 2 kernels.
 */

package com.kinetix3d.render

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class FullscreenQuad {
    private var program = 0
    private var vbo = 0
    private var positionLocation = -1
    private var tileOffsetLocation = -1
    private var tileScaleLocation = -1

    private val vertexBuffer: FloatBuffer =
        ByteBuffer
            .allocateDirect(VERTICES.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(VERTICES)
                position(0)
            }

    val programId: Int get() = program

    fun create(fragmentShaderSource: String): Boolean {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
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
        tileOffsetLocation = GLES20.glGetUniformLocation(program, "uTileOffset")
        tileScaleLocation = GLES20.glGetUniformLocation(program, "uTileScale")
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            VERTICES.size * Float.SIZE_BYTES,
            vertexBuffer,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return positionLocation >= 0 && vbo != 0
    }

    fun use(
        tileOffsetX: Float,
        tileOffsetY: Float,
        tileScaleX: Float,
        tileScaleY: Float,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(tileOffsetLocation, tileOffsetX, tileOffsetY)
        GLES20.glUniform2f(tileScaleLocation, tileScaleX, tileScaleY)
    }

    fun draw() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (vbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vbo = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
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
        private const val TAG = "KinetixFullscreenQuad"
        private val VERTICES = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            uniform vec2 uTileOffset;
            uniform vec2 uTileScale;
            void main() {
                vUv = uTileOffset + (aPosition * 0.5 + 0.5) * uTileScale;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """
    }
}
