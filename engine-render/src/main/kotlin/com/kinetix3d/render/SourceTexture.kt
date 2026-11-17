/*
 * Kinetix3D Studio — engine-render
 * Source texture contract for the Phase 2 DAG executor.
 */

package com.kinetix3d.render

interface SourceTexture {
    val textureId: Int

    fun initialize(): Boolean

    fun release()
}
