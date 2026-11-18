/*
 * Kinetix3D Studio — core-model
 * `.kproj` container: magic + version + payload length + CRC32 + protobuf
 * ProjectState. Pure JVM so the format is unit-testable without Android.
 */

package com.kinetix3d.model

import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

object ProjectFileCodec {
    /** "K3DP" in little-endian byte order. */
    private const val MAGIC = 0x4B334450

    const val VERSION: Int = 1

    /** magic(4) + version(2) + payloadLength(4) + crc32(4). */
    const val HEADER_BYTES: Int = 14

    fun encode(state: ProjectState): ByteArray {
        val payload = ProtoBuf.encodeToByteArray(ProjectState.serializer(), state)
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val buffer =
            ByteBuffer
                .allocate(HEADER_BYTES + payload.size)
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putInt(payload.size)
        buffer.putInt(crc)
        buffer.put(payload)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): ProjectState {
        require(bytes.size >= HEADER_BYTES) { "Project file is truncated" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "Not a Kinetix3D project file" }
        val version = buffer.short.toInt()
        require(version == VERSION) { "Unsupported project file version $version" }
        val payloadLength = buffer.int
        require(payloadLength >= 0 && payloadLength == bytes.size - HEADER_BYTES) {
            "Project payload length mismatch"
        }
        val expectedCrc = buffer.int
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        val actualCrc = CRC32().apply { update(payload) }.value.toInt()
        require(actualCrc == expectedCrc) { "Project checksum mismatch" }
        return ProtoBuf.decodeFromByteArray(ProjectState.serializer(), payload)
    }
}
