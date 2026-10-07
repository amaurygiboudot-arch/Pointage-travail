package com.amaury.pointage

/** Contrast of opaque sRGB colors. Unknown/translucent surfaces need an opaque support. */
object VisualContrastV2 {
    fun ratio(foreground: Int, background: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color ushr shift) and 255) / 255.0
                return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }
    fun bestText(background: Int): Int = if (ratio(BLACK, background) >= ratio(WHITE, background)) BLACK else WHITE
    const val BLACK: Int = -16777216
    const val WHITE: Int = -1
}
