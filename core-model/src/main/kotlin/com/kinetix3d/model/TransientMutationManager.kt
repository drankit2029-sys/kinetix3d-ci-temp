/*
 * Kinetix3D Studio — core-model
 * Transient mutation overrides: 120 Hz drags write into a lock-free atomic snapshot
 * read by the RenderThread. Zero disk I/O, zero undo-stack entries; only the commit
 * on ACTION_UP produces a Mutation for the WAL / undo stack.
 */

package com.kinetix3d.model

import java.util.concurrent.atomic.AtomicReference

data class OverrideKey(
    val targetId: String,
    val propertyPath: String,
)

/** Immutable snapshot handed to the RenderThread; never mutated in place. */
class TransientOverrideMap private constructor(
    private val values: Map<OverrideKey, Float>,
) {
    val size: Int get() = values.size

    val isEmpty: Boolean get() = values.isEmpty()

    operator fun get(key: OverrideKey): Float? = values[key]

    fun asMap(): Map<OverrideKey, Float> = values

    internal fun withValue(
        key: OverrideKey,
        value: Float,
    ): TransientOverrideMap = TransientOverrideMap(values + (key to value))

    internal fun withoutValue(key: OverrideKey): TransientOverrideMap = TransientOverrideMap(values - key)

    /** Applies all [updates] in one snapshot (atomic multi-axis overrides). */
    internal fun withValues(updates: Map<OverrideKey, Float>): TransientOverrideMap =
        if (updates.isEmpty()) this else TransientOverrideMap(values + updates)

    companion object {
        val EMPTY = TransientOverrideMap(emptyMap())
    }
}

class TransientMutationManager {
    private val current = AtomicReference(TransientOverrideMap.EMPTY)

    fun set(
        key: OverrideKey,
        value: Float,
    ) {
        while (true) {
            val previous = current.get()
            if (current.compareAndSet(previous, previous.withValue(key, value))) return
        }
    }

    fun set(
        targetId: String,
        propertyPath: String,
        value: Float,
    ) = set(OverrideKey(targetId, propertyPath), value)

    /**
     * Publishes several overrides in a single CAS so the RenderThread never observes
     * a torn multi-axis drag (World-Space Pivot Compensation updates anchor and
     * compensated position together).
     */
    fun setBatch(overrides: Map<OverrideKey, Float>) {
        if (overrides.isEmpty()) return
        while (true) {
            val previous = current.get()
            if (current.compareAndSet(previous, previous.withValues(overrides))) return
        }
    }

    fun clear(key: OverrideKey) {
        while (true) {
            val previous = current.get()
            if (current.compareAndSet(previous, previous.withoutValue(key))) return
        }
    }

    fun clearAll() {
        current.set(TransientOverrideMap.EMPTY)
    }

    /** Lock-free read for the RenderThread. */
    fun snapshot(): TransientOverrideMap = current.get()

    fun valueOf(key: OverrideKey): Float? = current.get()[key]

    /** Called on ACTION_UP: atomically drains the overrides for one UndoCommand + WAL append. */
    fun commit(): Map<OverrideKey, Float> = current.getAndSet(TransientOverrideMap.EMPTY).asMap()
}
