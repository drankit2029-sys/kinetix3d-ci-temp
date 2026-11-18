/*
 * Kinetix3D Studio — engine-render
 * Source texture contract for the Phase 2 DAG executor.
 */

package com.kinetix3d.render

interface SourceTexture {
    val textureId: Int

    /** True for `GL_TEXTURE_EXTERNAL_OES` sources (MediaCodec/SurfaceTexture). */
    val isExternal: Boolean get() = false

    fun initialize(): Boolean

    /** Tells a video source whether the transport is running (no-op for stills). */
    fun setPlaying(playing: Boolean) = Unit

    /** Updates the source to [ptsUs] (no-op for still/procedural sources). */
    fun update(ptsUs: Long) = Unit

    /** Column-major texture matrix for external sources (null = identity). */
    fun textureMatrix(): FloatArray? = null

    fun release()
}
