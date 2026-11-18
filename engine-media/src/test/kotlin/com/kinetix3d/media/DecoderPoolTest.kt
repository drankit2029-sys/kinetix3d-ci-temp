package com.kinetix3d.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecoderPoolTest {
    @Test
    fun acquiresUpToCapacity() {
        val pool = DecoderPool(capacity = 2)
        assertNotNull(pool.acquire("a"))
        assertNotNull(pool.acquire("b"))
        assertNull(pool.acquire("c"))
        assertEquals(2, pool.activeCount)
        assertEquals(0, pool.availableCount)
    }

    @Test
    fun releaseReturnsTheSlot() {
        val pool = DecoderPool(capacity = 1)
        val lease = assertNotNull(pool.acquire("a"))
        assertTrue(pool.release(lease))
        assertFalse(pool.release(lease))
        assertNotNull(pool.acquire("b"))
    }

    @Test
    fun reserveShrinksThePreviewCeiling() {
        val pool = DecoderPool(capacity = 3, reserved = 1)
        assertNotNull(pool.acquire("a"))
        assertNotNull(pool.acquire("b"))
        assertNull(pool.acquire("c"))
        pool.setReserved(0)
        assertNotNull(pool.acquire("c"))
    }

    @Test
    fun reserveIsClampedToCapacity() {
        val pool = DecoderPool(capacity = 2)
        pool.setReserved(99)
        assertEquals(2, pool.stats().reserved)
        assertNull(pool.acquire("a"))
        pool.setReserved(-4)
        assertEquals(0, pool.stats().reserved)
        assertNotNull(pool.acquire("a"))
    }

    @Test
    fun statsTrackActiveAndAvailable() {
        val pool = DecoderPool(capacity = 4, reserved = 1)
        val lease = assertNotNull(pool.acquire("preview"))
        val stats = pool.stats()
        assertEquals(4, stats.capacity)
        assertEquals(1, stats.reserved)
        assertEquals(1, stats.active)
        assertEquals(2, stats.available)
        pool.release(lease)
        assertEquals(3, pool.stats().available)
    }

    @Test
    fun closeRejectsNewLeases() {
        val pool = DecoderPool(capacity = 2)
        pool.close()
        assertNull(pool.acquire("a"))
        assertEquals(0, pool.activeCount)
    }

    @Test
    fun capacityIsAtLeastOne() {
        val pool = DecoderPool(capacity = 0)
        assertEquals(1, pool.capacity)
        assertNotNull(pool.acquire("a"))
    }
}
