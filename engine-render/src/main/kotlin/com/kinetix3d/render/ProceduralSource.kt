/*
 * Kinetix3D Studio — engine-render
 * Procedural source texture for the Phase 2 DAG executor (until Phase 3 decode).
 */

package com.kinetix3d.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProceduralSource {
    var textureId = 0
        private set

    fun initialize(): Boolean {
        val size = SIZE
        val pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until size) {
            for (x in 0 until size) {
                val checker = ((x / 64) + (y / 64)) % 2
                val gradient = (x * 255 / (size - 1))
                val r = if (checker == 0) 32 else 190
                val g = if (checker == 0) 70 else 120
                val b = if (checker == 0) 150 else 60
                pixels.put((r + gradient / 8).coerceAtMost(255).toByte())
                pixels.put((g + gradient / 10).coerceAtMost(255).toByte())
                pixels.put((b + gradient / 12).coerceAtMost(255).toByte())
                pixels.put(255.toByte())
            }
        }
        pixels.position(0)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        if (textureId == 0) return false
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            size,
            size,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixels,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return true
    }

    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    companion object {
        private const val SIZE = 512
    }
}
