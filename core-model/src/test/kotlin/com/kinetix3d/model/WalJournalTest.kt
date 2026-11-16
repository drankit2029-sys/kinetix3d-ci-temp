package com.kinetix3d.model

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalJournalTest {
    private fun mutation(
        id: String,
        op: MutationOp = MutationOp.GENERIC,
    ) = Mutation(
        op = op,
        targetId = id,
        timestampMs = 1_700_000_000_000L,
        payload = byteArrayOf(1, 2, 3),
    )

    @Test
    fun `append assigns strictly increasing sequence ids and readFrom is exclusive`() =
        runBlocking {
            val journal = InMemoryWalJournal()
            assertEquals(1L, journal.append(mutation("a")))
            assertEquals(2L, journal.append(mutation("b")))
            assertEquals(3L, journal.append(mutation("c")))

            assertEquals(listOf("a", "b", "c"), journal.readFrom(0).map { it.targetId })
            assertEquals(listOf("b", "c"), journal.readFrom(1).map { it.targetId })
            assertEquals(listOf("c"), journal.readFrom(2).map { it.targetId })
            assertTrue(journal.readFrom(3).isEmpty())
        }

    @Test
    fun `checkpoint keeps bounded retention without renumbering surviving records`() =
        runBlocking {
            val journal = InMemoryWalJournal()
            repeat(10) { journal.append(mutation("m$it")) }

            journal.checkpoint(keepLast = 3)

            assertEquals(listOf("m7", "m8", "m9"), journal.readFrom(0).map { it.targetId })
            // Sequence ids are preserved, not compacted: replay filters by seq, never by index.
            assertEquals(11L, journal.append(mutation("m10")))
            assertEquals(listOf("m10"), journal.readFrom(10).map { it.targetId })
        }

    @Test
    fun `replay is idempotent when resumed from last applied seq`() =
        runBlocking {
            val journal = InMemoryWalJournal()
            journal.append(mutation("clips/1", MutationOp.UPDATE_CLIP))
            journal.append(mutation("clips/2", MutationOp.UPDATE_CLIP))
            journal.append(mutation("clips/3", MutationOp.UPDATE_CLIP))

            val firstReplay = journal.readFrom(journal.lastAppliedSeq())
            assertEquals(3, firstReplay.size)

            // Simulate a crash after the first two mutations were applied.
            journal.markApplied(2)
            val resumed = journal.readFrom(journal.lastAppliedSeq())
            assertEquals(listOf("clips/3"), resumed.map { it.targetId })

            journal.markApplied(3L)
            // Re-running the crashed replay after the crash applies nothing twice.
            assertTrue(journal.readFrom(journal.lastAppliedSeq()).isEmpty())
            assertEquals(3L, journal.lastAppliedSeq())
        }

    @Test
    fun `markApplied never moves backwards`() =
        runBlocking {
            val journal = InMemoryWalJournal()
            journal.markApplied(5)
            journal.markApplied(2)
            assertEquals(5L, journal.lastAppliedSeq())
        }

    @Test
    fun `mutation protobuf roundtrip preserves payload bytes and equality`() {
        val original = mutation("clips/42", MutationOp.UPDATE_CLIP)
        val decoded = MutationCodec.decode(MutationCodec.encode(original))

        assertEquals(original, decoded)
        assertContentEquals(original.payload, decoded.payload)
        assertEquals(original.hashCode(), decoded.hashCode())
    }
}
