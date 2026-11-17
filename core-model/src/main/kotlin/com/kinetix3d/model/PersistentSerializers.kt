/*
 * Kinetix3D Studio — core-model
 * kotlinx.serialization adapters for kotlinx.collections.immutable containers.
 *
 * They are generic serializer classes so the plugin can instantiate them at
 * property sites: `@Serializable(with = PersistentMapSerializer::class)` on a
 * `PersistentMap<K, V>` property supplies `KSerializer<K>` / `KSerializer<V>`.
 * The wire shape is a plain map/list, so protobuf and JSON both work.
 */

package com.kinetix3d.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes a [PersistentMap] as a regular map; decodes back into an immutable map. */
class PersistentMapSerializer<K, V>(
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
) : KSerializer<PersistentMap<K, V>> {
    private val delegate = MapSerializer(keySerializer, valueSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: PersistentMap<K, V>,
    ) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): PersistentMap<K, V> = delegate.deserialize(decoder).toPersistentMap()
}

/** Serializes a [PersistentList] as a regular list; decodes back into an immutable list. */
class PersistentListSerializer<T>(
    elementSerializer: KSerializer<T>,
) : KSerializer<PersistentList<T>> {
    private val delegate = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: PersistentList<T>,
    ) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): PersistentList<T> = delegate.deserialize(decoder).toPersistentList()
}
