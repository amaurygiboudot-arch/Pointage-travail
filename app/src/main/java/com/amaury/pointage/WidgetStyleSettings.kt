package com.amaury.pointage

import android.content.Context
import android.graphics.Color

/** Propriétaire unique des préférences visuelles du widget. */
object WidgetStyleSettings {
    const val PREFS = "widget_style"
    const val KEY_BACKGROUND = "widget_bg"
    const val KEY_ACCENT = "widget_accent"
    const val KEY_SHOW_POSITION = "show_position"

    fun customBackground(context: Context): Int? =
        parseStoredColor(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND, null))

    fun customAccent(context: Context): Int? =
        parseStoredColor(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCENT, null))

    fun showPosition(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_POSITION, true)

    internal fun parseStoredColor(value: String?): Int? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Color.parseColor(normalized) }.getOrNull()
    }

    internal fun readableTextColors(background: Int): Pair<Int, Int> {
        val luma = (
            0.2126 * Color.red(background) +
            0.7152 * Color.green(background) +
            0.0722 * Color.blue(background)
        ) / 255.0
        return if (luma >= 0.56) {
            Color.rgb(8, 8, 8) to Color.rgb(48, 48, 48)
        } else {
            Color.WHITE to Color.rgb(235, 235, 235)
        }
    }
}
