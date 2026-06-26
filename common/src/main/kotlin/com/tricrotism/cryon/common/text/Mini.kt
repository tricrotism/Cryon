package com.tricrotism.cryon.common.text

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tricrotism.cryon.common.text.Mini.format
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.time.Duration

/**
 * The project's MiniMessage entry point — a non-strict instance preloaded with the [CryonPalette]
 * tags, a short-lived deserialize cache, and legacy (`§`) interop. Prefer [format] over building a
 * `MiniMessage` yourself so palette tags resolve and repeated strings stay cheap.
 */
object Mini {

    val mm: MiniMessage = MiniMessage.builder()
        .strict(false)
        .tags(TagResolver.resolver(StandardTags.defaults(), CryonPalette.RESOLVER))
        .build()

    private val legacy: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .character('§')
        .build()

    private val cache: Cache<String, Component> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(15))
        .maximumSize(500_000)
        .build()

    /** Deserialize [text] with the palette (cached ~15s — for static, resolver-free strings). */
    fun format(text: String): Component = cache.get(text) { mm.deserialize(it) }

    /** Deserialize [text] with extra [resolvers] (not cached — resolvers vary per call). */
    fun format(text: String, vararg resolvers: TagResolver): Component =
        mm.deserialize(text, TagResolver.resolver(*resolvers))

    fun toLegacy(component: Component): String = legacy.serialize(component)
    fun toComponent(legacyText: String): Component = legacy.deserialize(legacyText)
    fun toMiniMessage(legacyText: String): String = mm.serialize(legacy.deserialize(legacyText))
    fun stripFormatting(text: String): String = mm.stripTags(text)
}
