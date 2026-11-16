/*
 * Kinetix3D Studio — core-model
 * Write-ahead-log contract. The journal must not drag Android into :core-model,
 * so the interface and its in-memory double live here; the SQLite implementation
 * (AndroidWalJournal) lives in :core-platform-api.
 */

package com.kinetix3d.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf

@Serializable
enum class MutationOp {
    ADD_CLIP,
    UPDATE_CLIP,
    REMOVE_CLIP,
    MOVE_CLIP,
    ADD_TRACK,
    UPDATE_TRACK,
    REMOVE_TRACK,
    ADD_NODE,
    UPDATE_NODE,
    REMOVE_NODE,
    UPDATE_KEYFRAME,
    ADD_TRANSITION,
    UPDATE_TRANSITION,
    REMOVE_TRANSITION,
    UPDATE_COMP_SETTINGS,
    UPDATE_PROJECT_SETTINGS,
    RENAME_ASSET,
    RELINK_ASSET,
    GENERIC,
}

/**
 * One undoable logical mutation. [payload] is the domain-specific protobuf encoding
 * (op + targetId are intentionally duplicated at the envelope level so crash replay
 * can filter without decoding every payload).
 *
 * ByteArray gets structural equality so WAL round-trip tests are meaningful.
 */
@Serializable
data class Mutation(
    val op: MutationOp,
    val targetId: String = "",
    val timestampMs: Long = 0L,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Mutation) return false
        return op == other.op &&
            targetId == other.targetId &&
            timestampMs == other.timestampMs &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = op.hashCode()
        result = 31 * result + targetId.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object MutationCodec {
    private val proto = ProtoBuf

    fun encode(mutation: Mutation): ByteArray = proto.encodeToByteArray(Mutation.serializer(), mutation)

    fun decode(bytes: ByteArray): Mutation = proto.decodeFromByteArray(Mutation.serializer(), bytes)
}

/**
 * Crash-recovery journal contract.
 *
 * Replay is idempotent: callers resume from [lastAppliedSeq] and only apply newer
 * records, so re-running a partially completed replay after a crash is safe.
 */
interface WalJournal {
    /** Appends [mutation] and returns its assigned, strictly increasing sequence id. */
    suspend fun append(mutation: Mutation): Long

    /** Returns every mutation with sequence id strictly greater than [seqId], oldest first. */
    suspend fun readFrom(seqId: Long): List<Mutation>

    /** Bounded retention: keeps at most [keepLast] newest records, prunes older applied rows. */
    suspend fun checkpoint(keepLast: Int)

    suspend fun lastAppliedSeq(): Long

    suspend fun markApplied(seqId: Long)
}

/** JVM unit-test double; also the reference semantics for AndroidWalJournal. */
class InMemoryWalJournal(
    initialMutations: List<Mutation> = emptyList(),
) : WalJournal {
    private val mutex = Mutex()
    private val records = ArrayList<Pair<Long, Mutation>>()
    private var nextSeq = 1L
    private var appliedSeq = 0L

    init {
        initialMutations.forEach { mutation ->
            records.add(nextSeq to mutation)
            nextSeq++
        }
    }

    override suspend fun append(mutation: Mutation): Long =
        mutex.withLock {
            val seq = nextSeq
            nextSeq++
            records.add(seq to mutation)
            seq
        }

    override suspend fun readFrom(seqId: Long): List<Mutation> =
        mutex.withLock {
            records.filter { it.first > seqId }.map { it.second }
        }

    override suspend fun checkpoint(keepLast: Int): Unit =
        mutex.withLock {
            require(keepLast >= 0) { "keepLast must be non-negative" }
            if (records.size > keepLast) {
                records.subList(0, records.size - keepLast).clear()
            }
        }

    override suspend fun lastAppliedSeq(): Long = mutex.withLock { appliedSeq }

    override suspend fun markApplied(seqId: Long): Unit =
        mutex.withLock {
            if (seqId > appliedSeq) appliedSeq = seqId
        }
}
