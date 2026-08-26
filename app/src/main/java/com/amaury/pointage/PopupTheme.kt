package com.amaury.pointage

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Couleurs communes des fenêtres et popups personnalisés.
 *  Aucun popup ne doit imposer le mode sombre quand l'application est en mode jour.
 */
object PopupTheme {
    data class Colors(
        val background: Int,
        val panel: Int,
        val text: Int,
        val secondary: Int,
        val accent: Int
    )

    fun isDark(context: Context): Boolean {
        val prefs = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return mode == "dark" || (mode == "auto" && systemDark)
    }

    fun colors(context: Context): Colors {
        val dark = isDark(context)
        val theme = AppThemeCatalog.current(context)
        return Colors(
            background = if (dark) Color.rgb(8, 10, 14) else Color.rgb(247, 243, 234),
            panel = if (dark) Color.rgb(24, 24, 24) else Color.WHITE,
            text = if (dark) Color.rgb(245, 241, 232) else Color.rgb(28, 25, 20),
            secondary = if (dark) Color.rgb(190, 194, 202) else Color.rgb(92, 86, 76),
            accent = if (dark) theme.accentLight else theme.accent
        )
    }

    fun panelDrawable(context: Context, radiusDp: Float = 20f, stroke: Boolean = false): GradientDrawable {
        val c = colors(context)
        val d = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusDp * d
            setColor(c.panel)
            if (stroke) setStroke((2f * d).toInt().coerceAtLeast(1), c.accent)
        }
    }

    fun applyTextTree(context: Context, root: View) {
        val c = colors(context)
        when (root) {
            is EditText -> {
                root.setTextColor(c.text)
                root.setHintTextColor(c.secondary)
            }
            is Button -> root.setTextColor(c.text)
            is TextView -> root.setTextColor(c.text)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyTextTree(context, root.getChildAt(i))
        }
    }
}
