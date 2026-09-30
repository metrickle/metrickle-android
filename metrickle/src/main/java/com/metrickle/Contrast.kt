package com.metrickle

import kotlin.math.pow

/** WCAG 2.x colour helpers, ported from `packages/schema/src/constants.ts`. Colours are `#rrggbb`. */
public object Contrast {
    /** Contrast ratio between two `#rrggbb` colours (1 to 21). */
    @JvmStatic
    public fun ratio(a: String, b: String): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /**
     * Text colour for a brand fill: white when it reaches 4.5:1, otherwise black. Every colour
     * reaches at least ~4.58:1 against one of the two.
     */
    @JvmStatic
    public fun textOn(fill: String): String = if (ratio(fill, "#ffffff") >= 4.5) "#ffffff" else "#000000"

    /** `#rrggbb` for an ARGB colour int (alpha ignored). */
    @JvmStatic
    public fun hex(argb: Int): String = String.format(java.util.Locale.ROOT, "#%06x", argb and 0xffffff)

    private fun luminance(hex: String): Double {
        val (r, g, b) = listOf(1, 3, 5).map { i ->
            val c = hex.substring(i, i + 2).toInt(16) / 255.0
            if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
