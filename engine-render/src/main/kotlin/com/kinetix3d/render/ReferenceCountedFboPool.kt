/*
 * Kinetix3D Studio — engine-render
 * Reference-counted RGBA8 FBO pool. The budget is the interactive preview pool
 * cap; the planner decides when a pass must be tiled or reduced.
 */

package com.kinetix3d.render

import android.opengl.GLES20
import android.util.Log

class ReferenceCountedFboPool(
    private val budgetBytes: Long,
) : AutoCloseable {
    data class Handle(
        val framebufferId: Int,
        val textureId: Int,
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    private val free = mutableListOf<Handle>()
    private val inUse = mutableSetOf<Handle>()
    private var allocatedBytes = 0L
    private var closed = false

    fun acquire(
        width: Int,
        height: Int,
    ): Handle? {
        if (closed || width <= 0 || height <= 0) return null
        val bytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        free.firstOrNull { it.width == width && it.height == height }?.let { handle ->
            free.remove(handle)
            inUse.add(handle)
            return handle
        }
        if (allocatedBytes + bytes > budgetBytes) {
            trim()
            if (allocatedBytes + bytes > budgetBytes) return null
        }
        val handle = create(width, height, bytes) ?: return null
        allocatedBytes += bytes
        inUse.add(handle)
        return handle
    }

    fun release(handle: Handle) {
        if (closed) return
        if (inUse.remove(handle)) free.add(handle)
    }

    fun trim() {
        free.forEach { delete(it) }
        free.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        free.forEach { delete(it) }
        inUse.forEach { delete(it) }
        free.clear()
        inUse.clear()
        allocatedBytes = 0L
    }

    private fun create(
        width: Int,
        height: Int,
        bytes: Long,
    ): Handle? {
        val textures = IntArray(1)
        val framebuffers = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        if (textures[0] == 0 || framebuffers[0] == 0) {
            if (textures[0] != 0) GLES20.glDeleteTextures(1, textures, 0)
            if (framebuffers[0] != 0) GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            return null
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textures[0],
            0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "FBO incomplete: 0x${Integer.toHexString(status)}")
            GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            GLES20.glDeleteTextures(1, textures, 0)
            return null
        }
        return Handle(
            framebufferId = framebuffers[0],
            textureId = textures[0],
            width = width,
            height = height,
            bytes = bytes,
        )
    }

    private fun delete(handle: Handle) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(handle.framebufferId), 0)
        GLES20.glDeleteTextures(1, intArrayOf(handle.textureId), 0)
        allocatedBytes -= handle.bytes
    }

    companion object {
        private const val TAG = "KinetixFboPool"
        private const val BYTES_PER_PIXEL = 4L
    }
}
