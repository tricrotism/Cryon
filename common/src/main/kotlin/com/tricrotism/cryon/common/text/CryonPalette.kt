package com.tricrotism.cryon.common.text

import com.tricrotism.cryon.common.text.CryonPalette.RESOLVER
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

    /** Every palette colour as a MiniMessage styling tag. */
    val RESOLVER: TagResolver = TagResolver.builder().apply {
        fun add(name: String, color: TextColor) = resolver(TagResolver.resolver(name, Tag.styling(color)))

        add("success", SUCCESS); add("warning", WARNING); add("error", ERROR)
        add("info", INFO); add("neutral", NEUTRAL)

        add("dark_red", DARK_RED); add("red", RED); add("light_red", LIGHT_RED)
        add("crimson", CRIMSON); add("scarlet", SCARLET); add("orange", ORANGE)
        add("tangerine", TANGERINE); add("warm_orange", WARM_ORANGE); add("fire_orange", FIRE_ORANGE)
        add("dark_cherry", DARK_CHERRY)

        add("gold", GOLD); add("yellow", YELLOW); add("golden_yellow", GOLDEN_YELLOW)
        add("sun_glow", SUN_GLOW); add("honey_gold", HONEY_GOLD); add("warm_yellow", WARM_YELLOW)
        add("amber", AMBER); add("mustard", MUSTARD); add("dark_gold", DARK_GOLD)

        add("dark_green", DARK_GREEN); add("green", GREEN); add("light_green", LIGHT_GREEN)
        add("neon_green", NEON_GREEN); add("emerald", EMERALD); add("forest_green", FOREST_GREEN)
        add("mint", MINT); add("teal_green", TEAL_GREEN); add("jungle_green", JUNGLE_GREEN)
        add("moss", MOSS)

        add("dark_blue", DARK_BLUE); add("blue", BLUE); add("highlight_blue", HIGHLIGHT_BLUE)
        add("highlight", HIGHLIGHT); add("bright_blue", BRIGHT_BLUE); add("sky_blue", SKY_BLUE)
        add("ios_blue", IOS_BLUE); add("vibrant_cyan", VIBRANT_CYAN); add("aqua_glow", AQUA_GLOW)
        add("navy", NAVY); add("midnight_blue", MIDNIGHT_BLUE)

        add("darkish_purple", DARKISH_PURPLE); add("light_purple", LIGHT_PURPLE); add("blurple", BLURPLE)
        add("neon_purple", NEON_PURPLE); add("royal_violet", ROYAL_VIOLET); add("deep_purple", DEEP_PURPLE)
        add("indigo", INDIGO); add("lavender", LAVENDER); add("electric_violet", ELECTRIC_VIOLET)
        add("dark_plum", DARK_PLUM)

        add("hot_pink", HOT_PINK); add("rose_pink", ROSE_PINK); add("bubblegum", BUBBLEGUM)
        add("deep_magenta", DEEP_MAGENTA); add("crimson_pink", CRIMSON_PINK); add("neon_fuchsia", NEON_FUCHSIA)
        add("wine_rose", WINE_ROSE)

        add("white", WHITE); add("off_white", OFF_WHITE); add("dark_off_white", DARK_OFF_WHITE)
        add("alabaster", ALABASTER); add("ivory", IVORY)

        add("light_gray", LIGHT_GRAY); add("soft_gray", SOFT_GRAY); add("silver", SILVER)
        add("medium_gray", MEDIUM_GRAY); add("slate_gray", SLATE_GRAY); add("charcoal", CHARCOAL)
        add("dark_gray", DARK_GRAY); add("graphite", GRAPHITE)

        add("black", BLACK); add("rich_black", RICH_BLACK); add("jet_black", JET_BLACK)
        add("midnight_indigo", MIDNIGHT_INDIGO)
    }.build()
}
