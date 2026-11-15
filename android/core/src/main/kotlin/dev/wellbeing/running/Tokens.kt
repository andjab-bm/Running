package dev.wellbeing.running

/**
 * The approved design tokens, as `0xRRGGBB` values keyed by their CSS custom-property name.
 *
 * They mirror `web/src/lib/tokens.css`; `TokensTest` fails when the two differ, so the web app,
 * the phone and the watch cannot drift apart.
 */
object Tokens {
    val light: Map<String, Int> = mapOf(
        "bg" to 0xF4F3F9, "surface" to 0xFFFFFF, "surface-2" to 0xEFEEF7, "surface-3" to 0xE6E4F2,
        "ink" to 0x1E1B2E, "ink-2" to 0x54506B, "ink-3" to 0x6A6682,
        "accent" to 0x6252DE, "accent-soft" to 0xE7E3FC, "on-accent" to 0xFFFFFF,
        "p-lav" to 0xE7E3FC, "p-mint" to 0xD6F3E5, "p-peach" to 0xFFE4D3, "p-sky" to 0xD8EAFB,
        "p-butter" to 0xFBF1C9, "p-rose" to 0xFCDFE7,
        "i-lav" to 0x5446C9, "i-mint" to 0x1B7550, "i-peach" to 0xA94F1C, "i-sky" to 0x1D639F,
        "i-butter" to 0x7D6000, "i-rose" to 0xA82C5A,
        "c-rose" to 0xE46F90, "c-mint" to 0x35AF80, "c-sky" to 0x4A97D8, "c-peach" to 0xEE8A4C,
        "z1" to 0xA3ABC8, "z2" to 0x7DB7F0, "z3" to 0x6FD0A3, "z4" to 0xFFAE70, "z5" to 0xF58AA5,
        "pace-fast" to 0x6252DE, "pace-mid" to 0x4FC3A8, "pace-slow" to 0xFFB26B,
        "map-land" to 0xECEBF4, "map-park" to 0xD9F0E2, "map-water" to 0xD3E6FA,
        "map-road" to 0xFFFFFF, "map-label" to 0x6A6682,
    )

    /** Dark values; tokens absent here (the zone colours) are shared with the light theme. */
    val dark: Map<String, Int> = mapOf(
        "bg" to 0x121118, "surface" to 0x1B1A25, "surface-2" to 0x252431, "surface-3" to 0x302F3F,
        "ink" to 0xF2F0FA, "ink-2" to 0xBDB9D1, "ink-3" to 0x9F9BB6,
        "accent" to 0xA99BFF, "accent-soft" to 0x2D2A4B, "on-accent" to 0x15122B,
        "p-lav" to 0x2C2949, "p-mint" to 0x1C372E, "p-peach" to 0x3F2D20, "p-sky" to 0x1C2F44,
        "p-butter" to 0x38321A, "p-rose" to 0x40222E,
        "i-lav" to 0xBDB2FF, "i-mint" to 0x8FE0BB, "i-peach" to 0xFFC39C, "i-sky" to 0x9CCBF7,
        "i-butter" to 0xEFD877, "i-rose" to 0xF7AFC3,
        "c-rose" to 0xF58AA5, "c-mint" to 0x6FD0A3, "c-sky" to 0x7DB7F0, "c-peach" to 0xFFAE70,
        "pace-fast" to 0xA99BFF, "pace-mid" to 0x5FD6BB, "pace-slow" to 0xFFC08A,
        "map-land" to 0x191822, "map-park" to 0x1B2E29, "map-water" to 0x1B2A40,
        "map-road" to 0x292836, "map-label" to 0x9F9BB6,
    )

    fun color(name: String, isDark: Boolean): Int =
        (if (isDark) dark[name] else null) ?: light.getValue(name)

    /** Text on a zone colour, in both themes (mobile-specific token `--zone-ink`). */
    const val ZONE_INK = 0x1E1B2E

    fun zone(zone: Int): Int = light.getValue("z${zone.coerceIn(1, 5)}")

    /** Watch palette: always dark, on true black. */
    object Watch {
        const val GROUND = 0x000000
        const val BUTTON = 0x1F1E29
        const val PRIMARY = 0xA99BFF
        const val ON_PRIMARY = 0x15122B
        const val TIME = 0x8FE0BB
        const val PACE = 0xBDB2FF
        const val DISTANCE = 0x9CCBF7
        const val MUTED = 0x9F9BB6
        const val AMBIENT = 0x8C8A96
        const val WARNING = 0xF7AFC3
        const val PAUSED = 0xEFD877
    }
}

/** Five heart-rate zones given by six increasing bounds: lower bound of zones 1–5, then the maximum. */
data class Zones(val bounds: List<Double>) {
    init {
        require(bounds.size == 6) { "six bounds expected" }
    }

    /** Zone 1..5; values under zone 1 count as zone 1 and values over the maximum as zone 5, as on the server. */
    fun of(bpm: Int): Int {
        var z = 1
        for (i in 1..4) if (bpm >= bounds[i]) z = i + 1
        return z
    }

    companion object {
        /** The server's `DEFAULT_HR_MAX`, used until a profile has been retrieved. */
        const val DEFAULT_HR_MAX = 190.0

        fun fromMax(hrMax: Double) = Zones(listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0).map { Math.round(it * hrMax).toDouble() })

        val default = fromMax(DEFAULT_HR_MAX)
    }
}
