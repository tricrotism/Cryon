package com.tricrotism.cryon.common.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * The Cryon colour palette: named `TextColor`s and the matching MiniMessage tags (`<off_white>`,
 * `<scarlet>`, semantic `<error>`/`<success>`/…). Exposed as a [RESOLVER] and consumed by [Mini].
 * Tune hexes here in one place. To add a project-specific inserting tag (e.g. a currency name),
 * extend [RESOLVER] with `Tag.inserting(...)`.
 */
object CryonPalette {

    // Semantic
    val ERROR: TextColor = TextColor.color(220, 53, 69)
    val WARNING: TextColor = TextColor.color(255, 193, 7)
    val INFO: TextColor = TextColor.color(23, 162, 184)
    val SUCCESS: TextColor = TextColor.color(40, 167, 69)
    val NEUTRAL: TextColor = TextColor.color(108, 117, 125)

    // Reds & oranges
    val DARK_RED: TextColor = TextColor.color(139, 0, 0)
    val RED: TextColor = ERROR
    val LIGHT_RED: TextColor = TextColor.color(255, 102, 102)
    val CRIMSON: TextColor = TextColor.color(230, 57, 70)
    val SCARLET: TextColor = TextColor.color(215, 38, 56)
    val ORANGE: TextColor = TextColor.color(255, 140, 0)
    val TANGERINE: TextColor = TextColor.color(255, 107, 53)
    val WARM_ORANGE: TextColor = TextColor.color(255, 149, 0)
    val FIRE_ORANGE: TextColor = TextColor.color(242, 92, 5)
    val DARK_CHERRY: TextColor = TextColor.color(178, 58, 72)

    // Yellows & golds
    val GOLD: TextColor = TextColor.color(255, 196, 0)
    val YELLOW: TextColor = TextColor.color(255, 235, 59)
    val GOLDEN_YELLOW: TextColor = TextColor.color(255, 214, 10)
    val SUN_GLOW: TextColor = TextColor.color(255, 221, 0)
    val HONEY_GOLD: TextColor = TextColor.color(247, 179, 43)
    val WARM_YELLOW: TextColor = TextColor.color(244, 211, 94)
    val AMBER: TextColor = TextColor.color(255, 180, 0)
    val MUSTARD: TextColor = TextColor.color(238, 198, 67)
    val DARK_GOLD: TextColor = TextColor.color(201, 162, 39)

    // Greens
    val DARK_GREEN: TextColor = TextColor.color(6, 64, 43)
    val GREEN: TextColor = SUCCESS
    val LIGHT_GREEN: TextColor = TextColor.color(102, 187, 106)
    val NEON_GREEN: TextColor = TextColor.color(52, 199, 89)
    val EMERALD: TextColor = TextColor.color(46, 204, 113)
    val FOREST_GREEN: TextColor = TextColor.color(39, 174, 96)
    val MINT: TextColor = TextColor.color(107, 203, 119)
    val TEAL_GREEN: TextColor = TextColor.color(0, 168, 120)
    val JUNGLE_GREEN: TextColor = TextColor.color(0, 127, 95)
    val MOSS: TextColor = TextColor.color(1, 77, 64)

    // Blues & cyans
    val DARK_BLUE: TextColor = TextColor.color(36, 60, 110)
    val BLUE: TextColor = INFO
    val HIGHLIGHT_BLUE: TextColor = TextColor.color(0, 191, 255)
    val HIGHLIGHT: TextColor = HIGHLIGHT_BLUE
    val BRIGHT_BLUE: TextColor = TextColor.color(10, 132, 255)
    val SKY_BLUE: TextColor = TextColor.color(29, 161, 242)
    val IOS_BLUE: TextColor = TextColor.color(0, 122, 255)
    val VIBRANT_CYAN: TextColor = TextColor.color(78, 168, 222)
    val AQUA_GLOW: TextColor = TextColor.color(0, 207, 255)
    val NAVY: TextColor = TextColor.color(0, 78, 137)
    val MIDNIGHT_BLUE: TextColor = TextColor.color(2, 62, 125)

    // Purples & violets
    val DARKISH_PURPLE: TextColor = TextColor.color(106, 27, 154)
    val LIGHT_PURPLE: TextColor = TextColor.color(149, 117, 205)
    val BLURPLE: TextColor = TextColor.color(69, 79, 191)
    val NEON_PURPLE: TextColor = TextColor.color(191, 90, 242)
    val ROYAL_VIOLET: TextColor = TextColor.color(157, 78, 221)
    val DEEP_PURPLE: TextColor = TextColor.color(123, 44, 191)
    val INDIGO: TextColor = TextColor.color(106, 76, 147)
    val LAVENDER: TextColor = TextColor.color(199, 125, 255)
    val ELECTRIC_VIOLET: TextColor = TextColor.color(147, 54, 253)
    val DARK_PLUM: TextColor = TextColor.color(60, 9, 108)

    // Pinks & magentas
    val HOT_PINK: TextColor = TextColor.color(255, 45, 85)
    val ROSE_PINK: TextColor = TextColor.color(255, 111, 145)
    val BUBBLEGUM: TextColor = TextColor.color(241, 91, 181)
    val DEEP_MAGENTA: TextColor = TextColor.color(216, 17, 89)
    val CRIMSON_PINK: TextColor = TextColor.color(233, 64, 87)
    val NEON_FUCHSIA: TextColor = TextColor.color(255, 60, 172)
    val WINE_ROSE: TextColor = TextColor.color(164, 19, 60)

    // Whites
    val DARK_OFF_WHITE: TextColor = TextColor.color(189, 189, 189)
    val OFF_WHITE: TextColor = TextColor.color(224, 224, 224)
    val ALABASTER: TextColor = TextColor.color(250, 250, 250)
    val IVORY: TextColor = TextColor.color(255, 253, 240)
    val WHITE: TextColor = OFF_WHITE

    // Grays
    val LIGHT_GRAY: TextColor = TextColor.color(222, 226, 230)
    val SOFT_GRAY: TextColor = TextColor.color(200, 200, 200)
    val SILVER: TextColor = TextColor.color(192, 192, 192)
    val MEDIUM_GRAY: TextColor = TextColor.color(160, 160, 160)
    val SLATE_GRAY: TextColor = TextColor.color(112, 128, 144)
    val CHARCOAL: TextColor = TextColor.color(73, 80, 87)
    val DARK_GRAY: TextColor = TextColor.color(44, 47, 51)
    val GRAPHITE: TextColor = TextColor.color(33, 37, 41)

    // Blacks
    val BLACK: TextColor = TextColor.color(0, 0, 0)
    val RICH_BLACK: TextColor = TextColor.color(28, 28, 30)
    val JET_BLACK: TextColor = TextColor.color(20, 20, 20)
    val MIDNIGHT_INDIGO: TextColor = TextColor.color(44, 44, 84)
    val ONYX: TextColor = TextColor.color(53, 56, 57)
    val OBSIDIAN: TextColor = TextColor.color(26, 26, 36)

    // Neons. Fully saturated and meant to be used sparingly: a rarity announcement, a jackpot, the one
    // word in a line that has to be read first. A menu built out of these is unreadable
    val NEON_RED: TextColor = TextColor.color(255, 49, 49)
    val NEON_ORANGE: TextColor = TextColor.color(255, 95, 31)
    val NEON_YELLOW: TextColor = TextColor.color(255, 255, 51)
    val NEON_LIME: TextColor = TextColor.color(174, 255, 0)
    val NEON_MINT: TextColor = TextColor.color(0, 255, 178)
    val NEON_CYAN: TextColor = TextColor.color(0, 255, 255)
    val NEON_BLUE: TextColor = TextColor.color(31, 81, 255)
    val NEON_PINK: TextColor = TextColor.color(255, 16, 240)
    val NEON_MAGENTA: TextColor = TextColor.color(255, 0, 255)
    val ELECTRIC_LIME: TextColor = TextColor.color(204, 255, 0)
    val ELECTRIC_BLUE: TextColor = TextColor.color(125, 249, 255)
    val ELECTRIC_TEAL: TextColor = TextColor.color(0, 245, 212)
    val LASER_LEMON: TextColor = TextColor.color(255, 255, 102)
    val PLASMA: TextColor = TextColor.color(255, 0, 127)

    // Corals, peaches and salmons. Warm mid-tones that read well on dark backgrounds where a pure red
    // is aggressive
    val CORAL: TextColor = TextColor.color(255, 127, 80)
    val LIGHT_CORAL: TextColor = TextColor.color(240, 128, 128)
    val SALMON: TextColor = TextColor.color(250, 128, 114)
    val PEACH: TextColor = TextColor.color(255, 203, 164)
    val APRICOT: TextColor = TextColor.color(255, 180, 128)
    val PAPAYA: TextColor = TextColor.color(255, 145, 77)
    val BLUSH: TextColor = TextColor.color(255, 145, 164)
    val WATERMELON: TextColor = TextColor.color(252, 108, 133)

    // Limes and spring greens
    val LIME: TextColor = TextColor.color(50, 205, 50)
    val BRIGHT_LIME: TextColor = TextColor.color(128, 255, 0)
    val CHARTREUSE: TextColor = TextColor.color(127, 255, 0)
    val SPRING_GREEN: TextColor = TextColor.color(0, 255, 127)
    val SEA_GREEN: TextColor = TextColor.color(46, 139, 87)
    val MALACHITE: TextColor = TextColor.color(11, 218, 81)
    val SHAMROCK: TextColor = TextColor.color(0, 222, 129)

    // Teals, aquas and the cool pales
    val TEAL: TextColor = TextColor.color(0, 128, 128)
    val TURQUOISE: TextColor = TextColor.color(64, 224, 208)
    val AQUAMARINE: TextColor = TextColor.color(127, 255, 212)
    val CERULEAN: TextColor = TextColor.color(0, 123, 167)
    val AZURE: TextColor = TextColor.color(0, 127, 255)
    val CORNFLOWER: TextColor = TextColor.color(100, 149, 237)
    val PERIWINKLE: TextColor = TextColor.color(156, 166, 255)
    val ICE_BLUE: TextColor = TextColor.color(173, 232, 244)
    val ARCTIC: TextColor = TextColor.color(190, 240, 255)

    // More violets
    val ORCHID: TextColor = TextColor.color(218, 112, 214)
    val AMETHYST: TextColor = TextColor.color(153, 102, 204)
    val MAUVE: TextColor = TextColor.color(224, 176, 255)
    val IRIS: TextColor = TextColor.color(90, 74, 222)
    val ULTRAVIOLET: TextColor = TextColor.color(139, 0, 255)
    val GRAPE: TextColor = TextColor.color(111, 45, 168)

    // More pinks
    val FLAMINGO: TextColor = TextColor.color(252, 142, 172)
    val CANDY_PINK: TextColor = TextColor.color(255, 102, 178)
    val RUBY: TextColor = TextColor.color(224, 17, 95)
    val CERISE: TextColor = TextColor.color(222, 49, 99)

    // Metallics and earths. What a tier, a rank or a material wants when a flat hue would read as a
    // status colour instead
    val BRONZE: TextColor = TextColor.color(205, 127, 50)
    val COPPER: TextColor = TextColor.color(184, 115, 51)
    val BRASS: TextColor = TextColor.color(225, 193, 110)
    val RUST: TextColor = TextColor.color(183, 65, 14)
    val TERRACOTTA: TextColor = TextColor.color(204, 102, 68)
    val SAND: TextColor = TextColor.color(226, 202, 146)
    val CARAMEL: TextColor = TextColor.color(200, 132, 58)

    // Jewel tones, for the deep end of a gradient
    val SAPPHIRE: TextColor = TextColor.color(15, 82, 186)
    val TOPAZ: TextColor = TextColor.color(255, 200, 124)
    val GARNET: TextColor = TextColor.color(115, 54, 53)
    val PLUM: TextColor = TextColor.color(142, 69, 133)
    val BURGUNDY: TextColor = TextColor.color(128, 0, 32)
    val MAROON: TextColor = TextColor.color(128, 0, 0)

    /**
     * Tag name to colour, in declaration order. **Both directions are built from this one map.**
     *
     * [RESOLVER] turns it into MiniMessage styling tags; [tag] inverts it, so a colour on a rendered
     * component can be named again. Deriving both from one list rather than writing the pairs out
     * twice is the whole point: a colour added to one hand-written copy and missed in the other fails
     * nowhere, it simply stops being attributable, and whatever compares colours quietly loses a
     * series.
     *
     * **Order is meaningful.** Five names are aliases of another colour, and [tag] answers with the
     * *first* one declared for a value, so `<error>` and `<red>` - the same decision by whoever wrote
     * the message - land in one bucket rather than two when they are counted:
     *
     * | colour | names | [tag] answers |
     * |---|---|---|
     * | 220,53,69 | `error`, `red` | `error` |
     * | 40,167,69 | `success`, `green` | `success` |
     * | 23,162,184 | `info`, `blue` | `info` |
     * | 0,191,255 | `highlight_blue`, `highlight` | `highlight_blue` |
     * | 224,224,224 | `white`, `off_white` | **`white`** |
     *
     * The last one is the odd one out - the alias is declared before the colour it aliases, so the
     * literal name wins where the other four give the semantic one. That is how the tag list has
     * always been ordered and it is left alone deliberately: reordering would be a cosmetic
     * preference that silently renames a bucket everything already counted sits in.
     */
    val TAGS: Map<String, TextColor> = linkedMapOf(
        "success" to SUCCESS, "warning" to WARNING, "error" to ERROR,
        "info" to INFO, "neutral" to NEUTRAL,

        "dark_red" to DARK_RED, "red" to RED, "light_red" to LIGHT_RED,
        "crimson" to CRIMSON, "scarlet" to SCARLET, "orange" to ORANGE,
        "tangerine" to TANGERINE, "warm_orange" to WARM_ORANGE, "fire_orange" to FIRE_ORANGE,
        "dark_cherry" to DARK_CHERRY,

        "gold" to GOLD, "yellow" to YELLOW, "golden_yellow" to GOLDEN_YELLOW,
        "sun_glow" to SUN_GLOW, "honey_gold" to HONEY_GOLD, "warm_yellow" to WARM_YELLOW,
        "amber" to AMBER, "mustard" to MUSTARD, "dark_gold" to DARK_GOLD,

        "dark_green" to DARK_GREEN, "green" to GREEN, "light_green" to LIGHT_GREEN,
        "neon_green" to NEON_GREEN, "emerald" to EMERALD, "forest_green" to FOREST_GREEN,
        "mint" to MINT, "teal_green" to TEAL_GREEN, "jungle_green" to JUNGLE_GREEN,
        "moss" to MOSS,

        "dark_blue" to DARK_BLUE, "blue" to BLUE, "highlight_blue" to HIGHLIGHT_BLUE,
        "highlight" to HIGHLIGHT, "bright_blue" to BRIGHT_BLUE, "sky_blue" to SKY_BLUE,
        "ios_blue" to IOS_BLUE, "vibrant_cyan" to VIBRANT_CYAN, "aqua_glow" to AQUA_GLOW,
        "navy" to NAVY, "midnight_blue" to MIDNIGHT_BLUE,

        "darkish_purple" to DARKISH_PURPLE, "light_purple" to LIGHT_PURPLE, "blurple" to BLURPLE,
        "neon_purple" to NEON_PURPLE, "royal_violet" to ROYAL_VIOLET, "deep_purple" to DEEP_PURPLE,
        "indigo" to INDIGO, "lavender" to LAVENDER, "electric_violet" to ELECTRIC_VIOLET,
        "dark_plum" to DARK_PLUM,

        "hot_pink" to HOT_PINK, "rose_pink" to ROSE_PINK, "bubblegum" to BUBBLEGUM,
        "deep_magenta" to DEEP_MAGENTA, "crimson_pink" to CRIMSON_PINK, "neon_fuchsia" to NEON_FUCHSIA,
        "wine_rose" to WINE_ROSE,

        "white" to WHITE, "off_white" to OFF_WHITE, "dark_off_white" to DARK_OFF_WHITE,
        "alabaster" to ALABASTER, "ivory" to IVORY,

        "light_gray" to LIGHT_GRAY, "soft_gray" to SOFT_GRAY, "silver" to SILVER,
        "medium_gray" to MEDIUM_GRAY, "slate_gray" to SLATE_GRAY, "charcoal" to CHARCOAL,
        "dark_gray" to DARK_GRAY, "graphite" to GRAPHITE,

        "black" to BLACK, "rich_black" to RICH_BLACK, "jet_black" to JET_BLACK,
        "midnight_indigo" to MIDNIGHT_INDIGO, "onyx" to ONYX, "obsidian" to OBSIDIAN,

        "neon_red" to NEON_RED, "neon_orange" to NEON_ORANGE, "neon_yellow" to NEON_YELLOW,
        "neon_lime" to NEON_LIME, "neon_mint" to NEON_MINT, "neon_cyan" to NEON_CYAN,
        "neon_blue" to NEON_BLUE, "neon_pink" to NEON_PINK, "neon_magenta" to NEON_MAGENTA,
        "electric_lime" to ELECTRIC_LIME, "electric_blue" to ELECTRIC_BLUE,
        "electric_teal" to ELECTRIC_TEAL, "laser_lemon" to LASER_LEMON, "plasma" to PLASMA,

        "coral" to CORAL, "light_coral" to LIGHT_CORAL, "salmon" to SALMON,
        "peach" to PEACH, "apricot" to APRICOT, "papaya" to PAPAYA,
        "blush" to BLUSH, "watermelon" to WATERMELON,

        "lime" to LIME, "bright_lime" to BRIGHT_LIME, "chartreuse" to CHARTREUSE,
        "spring_green" to SPRING_GREEN, "sea_green" to SEA_GREEN, "malachite" to MALACHITE,
        "shamrock" to SHAMROCK,

        "teal" to TEAL, "turquoise" to TURQUOISE, "aquamarine" to AQUAMARINE,
        "cerulean" to CERULEAN, "azure" to AZURE, "cornflower" to CORNFLOWER,
        "periwinkle" to PERIWINKLE, "ice_blue" to ICE_BLUE, "arctic" to ARCTIC,

        "orchid" to ORCHID, "amethyst" to AMETHYST, "mauve" to MAUVE,
        "iris" to IRIS, "ultraviolet" to ULTRAVIOLET, "grape" to GRAPE,

        "flamingo" to FLAMINGO, "candy_pink" to CANDY_PINK, "ruby" to RUBY, "cerise" to CERISE,

        "bronze" to BRONZE, "copper" to COPPER, "brass" to BRASS,
        "rust" to RUST, "terracotta" to TERRACOTTA, "sand" to SAND, "caramel" to CARAMEL,

        "sapphire" to SAPPHIRE, "topaz" to TOPAZ, "garnet" to GARNET,
        "plum" to PLUM, "burgundy" to BURGUNDY, "maroon" to MAROON,
    )

    // Every palette colour as a MiniMessage styling tag
    val RESOLVER: TagResolver = TagResolver.builder().apply {
        TAGS.forEach { (name, color) -> resolver(TagResolver.resolver(name, Tag.styling(color))) }
    }.build()

    // Colour value to its canonical tag name. See [TAGS] for why the first declaration wins
    private val NAMES_BY_VALUE: Map<Int, String> = HashMap<Int, String>(TAGS.size * 2).apply {
        TAGS.forEach { (name, color) -> putIfAbsent(color.value(), name) }
    }

    /**
     * MiniMessage compiles a tag away at parse time - a rendered `Component` carries a resolved RGB
     * value and no memory of which tag produced it - so this is the only route back from what a player
     * was shown to the decision that coloured it.
     *
     * @return the palette name for [color], or null when it is not one of ours
     */
    fun tag(color: TextColor): String? = NAMES_BY_VALUE[color.value()]

    /**
     * What this is for: telling whether text performs differently depending on how it was coloured.
     * That question is only askable because the palette is a fixed, named set - a codebase writing raw
     * hex everywhere could not ask it at all.
     *
     * **Colours outside the palette are ignored rather than reported as hex.** An unbounded set of
     * colour values is the cardinality mistake every label rule exists to prevent, and a one-off hex
     * is not a design decision worth comparing anything against.
     *
     * @return every palette name used anywhere in [component], parents before children
     */
    fun tags(component: Component): Set<String> {
        val found = LinkedHashSet<String>(4)
        collect(component, found)

        return found
    }

    private fun collect(component: Component, into: MutableSet<String>) {
        component.color()?.let { color -> tag(color)?.let(into::add) }

        val children = component.children()
        for (index in children.indices) collect(children[index], into)
    }
}
