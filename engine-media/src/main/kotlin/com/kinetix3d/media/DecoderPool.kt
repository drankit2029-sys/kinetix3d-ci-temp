/*
 * Kinetix3D Studio — engine-media
 * Capacity-bounded hardware-decoder lease pool. The pool enforces the
 * maxHwDecoders ceiling across preview sources and reserves a slot for export.
 */

package com.kinetix3d.media

class DecoderPool(
    capacity: Int,
    reserved: Int = 0,
) : AutoCloseable {
    data class Lease internal constructor(
        internal val id: Long,
        val owner: String,
    )

    data class Stats(
        val capacity: Int,
        val reserved: Int,
        val active: Int,
        val available: Int,
    )

    val capacity: Int = capacity.coerceAtLeast(1)

    private val lock = Any()
    private val active = LinkedHashMap<Long, Lease>()
    private var nextId = 1L
    private var reservedSlots = reserved.coerceIn(0, this.capacity)
    private var closed = false

    val activeCount: Int
        get() = synchronized(lock) { active.size }

    val availableCount: Int
        get() = synchronized(lock) { availableLocked() }

    fun acquire(owner: String): Lease? =
        synchronized(lock) {
            if (closed || availableLocked() <= 0) return null
            val lease = Lease(nextId++, owner)
            active[lease.id] = lease
            lease
        }

    fun release(lease: Lease): Boolean =
        synchronized(lock) {
            active.remove(lease.id) != null
        }

    fun setReserved(count: Int) {
        synchronized(lock) {
            reservedSlots = count.coerceIn(0, capacity)
        }
    }

    fun stats(): Stats =
        synchronized(lock) {
            Stats(
                capacity = capacity,
                reserved = reservedSlots,
                active = active.size,
                available = availableLocked(),
            )
        }

    override fun close() {
        synchronized(lock) {
            closed = true
            active.clear()
        }
    }

    private fun availableLocked(): Int = (capacity - reservedSlots - active.size).coerceAtLeast(0)
}
