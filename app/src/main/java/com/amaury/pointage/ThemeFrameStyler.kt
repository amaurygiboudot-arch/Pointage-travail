package com.amaury.pointage

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * Applique les vrais fonds/cadres des thèmes sans les remplacer par des aplats.
 * Les boutons de pointage diamant restent totalement exclus.
 */
object ThemeFrameStyler {
    private val protectedIds = setOf("entryButton", "pauseButton", "exitButton", "settingsButton")
    private val tabIds = setOf("tabToday", "tabHistory", "tabAnalytics", "tabSalary", "tabSettings")

    fun apply(root: View) {
        AutoDayNightPolarity.apply(root)
        val theme = AppThemeCatalog.current(root.context)
        if (theme.id != "natural_carbon" && theme.id != "signature_gold") return
        val dark = AppThemeCatalog.useDarkPalette(root.context)
        applyRecursive(root, theme, dark)
    }

    private fun applyRecursive(view: View, theme: HpTheme, dark: Boolean) {
        val id = resourceName(view)
        if (id in protectedIds) return

        when {
            id in tabIds && view is TextView -> styleTab(view, theme, dark)
            view is Button -> styleControl(view, theme, dark)
            view is EditText -> styleControl(view, theme, dark)
            view is Switch -> styleControl(view, theme, dark)
            view is ViewGroup && isFramedContainer(view, id) -> styleContainer(view, theme)
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
        return tag == "settings_personalization_installed" ||
            tag.contains("panel", ignoreCase = true) || tag.contains("card", ignoreCase = true)
    }

    private fun styleContainer(view: View, theme: HpTheme) {
        view.backgroundTintList = null
        view.background = when (theme.id) {
            "natural_carbon" -> CarbonCompositeDrawable(view.context)
            else -> view.context.getDrawable(R.drawable.hp_panel)?.mutate()
        }
    }

    private fun styleControl(view: View, theme: HpTheme, dark: Boolean) {
        view.backgroundTintList = null
        view.background = when (theme.id) {
            "natural_carbon" -> CarbonCompositeDrawable(view.context)
            else -> view.context.getDrawable(R.drawable.hp_panel)?.mutate()
        }
        when (view) {
            is Button -> view.setTextColor(if (dark) theme.darkText else theme.lightText)
            is EditText -> {
                view.setTextColor(if (dark) theme.darkText else theme.lightText)
                view.setHintTextColor(if (dark) theme.darkHint else theme.lightHint)
            }
            is Switch -> view.setTextColor(if (dark) theme.darkText else theme.lightText)
        }
    }

    private fun styleTab(tab: TextView, theme: HpTheme, dark: Boolean) {
        tab.backgroundTintList = null
        tab.background = when (theme.id) {
            "natural_carbon" -> CarbonCompositeDrawable(tab.context)
            else -> tab.context.getDrawable(R.drawable.hp_panel)?.mutate()
        }
        val active = tab.isSelected
        tab.alpha = if (active) 1f else 0.78f
        tab.elevation = if (active) 3f * tab.resources.displayMetrics.density else 0f
        tab.setTextColor(
            if (active) {
                if (dark) theme.darkText else theme.lightText
            } else {
                if (dark) theme.darkHint else theme.lightHint
            }
        )
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
}
