package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.number.PackedDecimal
import com.tricrotism.cryon.paper.api.item.ItemBuilder
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataType
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.KClass

/**
 * Start an [ItemBuilder] from a material, `Material.DIAMOND.toItem().name("…")`.
 */
fun Material.toItem(amount: Int = 1): ItemBuilder = ItemBuilder(this, amount)

/**
 * Wrap an existing stack in an [ItemBuilder] (works on a clone, the original is untouched).
 */
fun ItemStack.toBuilder(): ItemBuilder = ItemBuilder(this)

/**
 * Apply builder edits and return the new stack, `item.modify { name("<gold>X"); glow() }`.
 */
fun ItemStack.modify(block: ItemBuilder.() -> Unit): ItemStack = toBuilder().apply(block).build()

/**
 * True for a null, air, or zero-amount stack. The usual "empty slot" check.
 */
fun ItemStack?.isEmpty(): Boolean = this == null || type.isAir || amount <= 0

/**
 * A clone of this stack with a different amount.
 */
fun ItemStack.withAmount(amount: Int): ItemStack = clone().also { it.amount = amount }

// Persistent-data (PDC) tag helpers. Read straight off the stack without unpacking meta yourself.
fun <P : Any, C : Any> ItemStack.getTag(key: NamespacedKey, type: PersistentDataType<P, C>): C? =
    itemMeta?.persistentDataContainer?.get(key, type)

fun ItemStack.hasTag(key: NamespacedKey): Boolean =
    itemMeta?.persistentDataContainer?.has(key) ?: false

fun <P : Any, C : Any> ItemStack.setTag(key: NamespacedKey, type: PersistentDataType<P, C>, value: C) {
    editMeta { it.persistentDataContainer.set(key, type, value) }
}

fun ItemStack.removeTag(key: NamespacedKey) {
    editMeta { it.persistentDataContainer.remove(key) }
}

inline fun <reified C : Any> ItemStack.getTag(key: NamespacedKey): C? = getTag(key, pdcType(C::class))

inline fun <reified C : Any> ItemStack.setTag(key: NamespacedKey, value: C) {
    setTag(key, pdcType(C::class), value)
}

private val pdcTypes: Map<KClass<*>, PersistentDataType<*, *>> = mapOf(
    String::class to PersistentDataType.STRING,
    Byte::class to PersistentDataType.BYTE,
    Short::class to PersistentDataType.SHORT,
    Int::class to PersistentDataType.INTEGER,
    Long::class to PersistentDataType.LONG,
    Float::class to PersistentDataType.FLOAT,
    Double::class to PersistentDataType.DOUBLE,
    Boolean::class to PersistentDataType.BOOLEAN,
    ByteArray::class to PersistentDataType.BYTE_ARRAY,
    IntArray::class to PersistentDataType.INTEGER_ARRAY,
    LongArray::class to PersistentDataType.LONG_ARRAY,
    UUID::class to UuidTagType,
    PackedDecimal::class to PackedDecimalTagType,
)

/**
 * UUID packed into a 16-byte array; Bukkit ships no UUID type of its own.
 */
private object UuidTagType : PersistentDataType<ByteArray, UUID> {
    override fun getPrimitiveType(): Class<ByteArray> = ByteArray::class.java
    override fun getComplexType(): Class<UUID> = UUID::class.java

    override fun toPrimitive(complex: UUID, context: PersistentDataAdapterContext): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(complex.mostSignificantBits)
            .putLong(complex.leastSignificantBits)
            .array()

    override fun fromPrimitive(primitive: ByteArray, context: PersistentDataAdapterContext): UUID =
        ByteBuffer.wrap(primitive).let { UUID(it.long, it.long) }
}

/**
 * [PackedDecimal] stored as its packed long bit pattern.
 */
private object PackedDecimalTagType : PersistentDataType<Long, PackedDecimal> {
    override fun getPrimitiveType(): Class<Long> = Long::class.javaObjectType
    override fun getComplexType(): Class<PackedDecimal> = PackedDecimal::class.java

    override fun toPrimitive(complex: PackedDecimal, context: PersistentDataAdapterContext): Long =
        complex.raw()

    override fun fromPrimitive(primitive: Long, context: PersistentDataAdapterContext): PackedDecimal =
        PackedDecimal.fromRaw(primitive)
}

/**
 * The built-in [PersistentDataType] for [type]; anything not in the lookup passes theirs explicitly.
 */
@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun <C : Any> pdcType(type: KClass<C>): PersistentDataType<Any, C> =
    (pdcTypes[type] ?: error("No PersistentDataType for ${type.simpleName}, pass the PersistentDataType explicitly"))
            as PersistentDataType<Any, C>
