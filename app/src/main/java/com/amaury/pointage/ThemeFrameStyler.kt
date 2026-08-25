package com.amaury.pointage

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch

/**
 * Source de vérité visuelle pour les cadres de l'application principale.
 * Les boutons de pointage diamant restent volontairement exclus : leur moteur
 * Canvas dessine déjà son propre contour et ne doit jamais être recouvert.
 */
object ThemeFrameStyler {
    private val protectedIds = setOf("entryButton", "pauseButton", "exitButton", "settingsButton")
    private val tabIds = setOf("tabToday", "tabHistory", "tabAnalytics", "tabSalary", "tabSettings")

    fun apply(root: View) {
        val theme = AppThemeCatalog.current(root.context)
        if (theme.id != "natural_carbon" && theme.id != "signature_gold") return
        val dark = AppThemeCatalog.useDarkPalette(root.context)
        applyRecursive(root, theme, dark)
    }

    private fun applyRecursive(view: View, theme: HpTheme, dark: Boolean) {
        val id = resourceName(view)
        if (id in protectedIds || id in tabIds) return

        when (view) {
            is Button -> styleControl(view, theme, dark, emphasized = true)
            is EditText -> styleControl(view, theme, dark, emphasized = false)
            is Switch -> styleControl(view, theme, dark, emphasized = false)
            is ViewGroup -> if (isFramedContainer(view, id)) styleContainer(view, theme, dark)
        }

        if (view is ViewGroup && view !is True3DButtonHost) {
            for (i in 0 until view.childCount) applyRecursive(view.getChildAt(i), theme, dark)
        }
    }

    private fun isFramedContainer(view: ViewGroup, id: String): Boolean {
        if (id == "navigationTabs" || id == "pointageButtons") return false
        if (id == "contentPanel" || id == "gpsSettingsPanel" || id == "analyticsPdfPanel") return true
        if (id.contains("panel", ignoreCase = true) || id.contains("card", ignoreCase = true)) return true
        val tag = view.tag?.toString().orEmpty()
        return tag == "settings_personalization_installed" || tag.contains("panel", ignoreCase = true) || tag.contains("card", ignoreCase = true)
    }

    private fun styleContainer(view: View, theme: HpTheme, dark: Boolean) {
        val density = view.resources.displayMetrics.density
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            if (theme.id == "natural_carbon") {
                setColor(if (dark) Color.parseColor("#101213") else Color.parseColor("#D8DADB"))
                setStroke((1.5f * density).toInt().coerceAtLeast(1), if (dark) Color.parseColor("#666E72") else Color.parseColor("#6F777B"))
            } else {
                setColor(if (dark) Color.parseColor("#15130F") else Color.parseColor("#FFFDF7"))
                val gold = if (dark) theme.accentLight else theme.accent
                setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.argb(190, Color.red(gold), Color.green(gold), Color.blue(gold)))
            }
        }
        view.background = drawable
    }

    private fun styleControl(view: View, theme: HpTheme, dark: Boolean, emphasized: Boolean) {
        val density = view.resources.displayMetrics.density
        view.backgroundTintList = null
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            if (theme.id == "natural_carbon") {
                setColor(if (dark) Color.parseColor("#171A1C") else Color.parseColor("#CFD2D3"))
                val stroke = if (emphasized) Color.parseColor("#AEB5B8") else Color.parseColor("#747C80")
                setStroke(((if (emphasized) 1.8f else 1.2f) * density).toInt().coerceAtLeast(1), stroke)
            } else {
                setColor(if (dark) Color.parseColor("#1C1810") else Color.parseColor("#FFFBF0"))
                val gold = if (dark) theme.accentLight else theme.accent
                setStroke(((if (emphasized) 1.8f else 1.2f) * density).toInt().coerceAtLeast(1), gold)
            }
        }
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
}
