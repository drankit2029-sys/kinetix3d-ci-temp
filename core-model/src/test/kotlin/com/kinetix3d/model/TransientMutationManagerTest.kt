package com.kinetix3d.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransientMutationManagerTest {
    private val key = OverrideKey("clip/1", "transform.position.x")

    @Test
    fun `overrides are visible to a lock-free snapshot`() {
        val manager = TransientMutationManager()
        manager.set(key, 12.5f)

        assertEquals(12.5f, manager.snapshot()[key])
        assertEquals(12.5f, manager.valueOf(key))
        assertEquals(1, manager.snapshot().size)
    }

    @Test
    fun `snapshots are immutable`() {
        val manager = TransientMutationManager()
        manager.set(key, 1f)
        val before = manager.snapshot()

        manager.set(key, 2f)

        assertEquals(1f, before[key])
        assertEquals(2f, manager.snapshot()[key])
    }

    @Test
    fun `clearing a single override leaves the others intact`() {
        val manager = TransientMutationManager()
        manager.set(key, 3f)
        manager.set("clip/1", "opacity", 0.5f)

        manager.clear(key)

        assertNull(manager.valueOf(key))
        assertEquals(0.5f, manager.valueOf(OverrideKey("clip/1", "opacity")))
    }

    @Test
    fun `commit drains the map for one undo command`() {
        val manager = TransientMutationManager()
        manager.set(key, 4f)
        manager.set("clip/2", "rotation.z", 30f)

        val committed = manager.commit()

        assertEquals(2, committed.size)
        assertEquals(4f, committed[key])
        assertTrue(manager.snapshot().isEmpty)
    }

    @Test
    fun `batch overrides publish as one atomic snapshot`() {
        val manager = TransientMutationManager()
        manager.set(key, 1f)
        val before = manager.snapshot()

        manager.setBatch(
            mapOf(
                OverrideKey("clip/1", "transform.anchor.x") to 10f,
                OverrideKey("clip/1", "transform.anchor.y") to 20f,
                OverrideKey("clip/1", "transform.anchor.z") to 30f,
                key to 2f,
            ),
        )

        val after = manager.snapshot()
        assertEquals(1f, before[key])
        assertEquals(2f, after[key])
        assertEquals(
            listOf(10f, 20f, 30f),
            listOf("x", "y", "z").map { after[OverrideKey("clip/1", "transform.anchor.$it")] },
        )
    }

    @Test
    fun `clearAll drops every transient override`() {
        val manager = TransientMutationManager()
        manager.set(key, 1f)
        manager.clearAll()
        assertTrue(manager.snapshot().isEmpty)
    }
}
