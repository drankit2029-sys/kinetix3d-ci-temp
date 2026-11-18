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

    /** Updates the source to [ptsUs] (no-op for still/procedural sources). */
    fun update(ptsUs: Long) = Unit

    fun release()
}
