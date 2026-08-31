package com.amaury.pointage

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * Source de vérité unique pour l'apparence des contrôles standards HoraTrack.
 *
 * Référence visuelle exacte : bouton "Saisie manuelle d'une pause" :
 * - drawable hp_panel brut, sans teinte ajoutée par un ancien thème ;
 * - texte orange #F3A64A ;
 * - pas de transformation automatique en majuscules ;
 * - animation tactile laissée au moteur de relief, mais pas son ancien fond.
 *
 * Les trois boutons de pointage diamant et le bouton paramètres restent protégés.
 */
object ThemeFrameStyler {
    private val protectedIds = setOf("entryButton", "pauseButton", "exitButton", "settingsButton")
    private val tabIds = setOf("tabToday", "tabHistory", "tabAnalytics", "tabSalary", "tabSettings")
    private val visualIds = setOf("clockDigital", "heroClockHands", "sunIndicator")
    private val referenceOrange = Color.parseColor("#F3A64A")

    fun apply(root: View) {
        val actualRoot = root.rootView ?: root
        AutoDayNightPolarity.apply(actualRoot)
        val theme = AppThemeCatalog.current(actualRoot.context)
        val dark = AppThemeCatalog.useDarkPalette(actualRoot.context)
        applyRecursive(actualRoot, theme, dark, inheritedDark = dark)
    }

    private fun applyRecursive(view: View, theme: HpTheme, dark: Boolean, inheritedDark: Boolean) {
        val id = resourceName(view)
        if (id in protectedIds || id in visualIds || view is RedDiamondFinalButton) return

        val framedContainer = view is ViewGroup && isFramedContainer(view, id)
        val localDark = when {
            framedContainer -> inheritedDark
            hasThemedBackground(view) -> dark
            else -> inheritedDark
        }

        when {
            framedContainer -> clearContainerBackground(view)
            id in tabIds && view is TextView -> styleTab(view, theme, localDark)
            view is Button -> styleButton(view)
            view is EditText -> styleInput(view, localDark)
            view is Switch -> styleSwitch(view, localDark)
            view is TextView -> styleText(view, theme, localDark)
        }

        if (view is ViewGroup && view !is True3DButtonHost) {
            for (i in 0 until view.childCount) applyRecursive(view.getChildAt(i), theme, dark, localDark)
        }
    }

    /**
     * Important : aucun CarbonCompositeDrawable, DynamicDiamondDrawable ou tint de thème
     * n'est conservé ici. Le fond est remplacé par la référence hp_panel à chaque passage.
     */
    private fun styleButton(button: Button) {
        button.backgroundTintList = null
        button.setBackgroundResource(R.drawable.hp_panel)
        button.backgroundTintList = null
        button.setTextColor(referenceOrange)
        button.isAllCaps = false
        button.alpha = 1f
    }

    private fun styleInput(view: EditText, dark: Boolean) {
        val textColor = if (dark) Color.WHITE else Color.parseColor("#111111")
        val hintColor = if (dark) Color.parseColor("#D8D8D8") else Color.parseColor("#555555")
        view.setTextColor(textColor)
        view.setHintTextColor(hintColor)
    }

    private fun styleSwitch(view: Switch, dark: Boolean) {
        val textColor = if (dark) Color.WHITE else Color.parseColor("#111111")
        view.setTextColor(textColor)
    }

    private fun hasThemedBackground(view: View): Boolean =
        view.background is CarbonCompositeDrawable || view.background is DynamicDiamondDrawable || view.background != null

    private fun isFramedContainer(view: ViewGroup, id: String): Boolean {
        if (id == "navigationTabs" || id == "pointageButtons") return false
        if (id == "contentPanel" || id == "gpsSettingsPanel" || id == "analyticsPdfPanel") return true
        if (id.contains("panel", ignoreCase = true) || id.contains("card", ignoreCase = true)) return true
        val tag = view.tag?.toString().orEmpty()
        return tag == "settings_personalization_installed" ||
            tag.contains("panel", ignoreCase = true) || tag.contains("card", ignoreCase = true)
    }

    private fun clearContainerBackground(view: View) {
        view.backgroundTintList = null
        view.background = null
        view.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun styleText(view: TextView, theme: HpTheme, dark: Boolean) {
        val textColor = if (dark) Color.WHITE else Color.parseColor("#111111")
        val accent = if (dark) theme.accentLight else theme.accent
        val current = view.currentTextColor
        val shouldKeepAccent = contrastRatio(current, if (dark) Color.BLACK else Color.WHITE) >= 4.5 &&
            (current == theme.accent || current == theme.accentLight || current == referenceOrange)
        val target = if (shouldKeepAccent) {
            if (current == referenceOrange) referenceOrange else accent
        } else textColor
        if (current != target) view.setTextColor(target)
    }

    private fun styleTab(tab: TextView, theme: HpTheme, dark: Boolean) {
        val active = tab.isSelected
        val alpha = if (active) 1f else 0.78f
        if (tab.alpha != alpha) tab.alpha = alpha
        val elevation = if (active) 3f * tab.resources.displayMetrics.density else 0f
        if (tab.elevation != elevation) tab.elevation = elevation
        val target = if (dark) {
            if (active) Color.WHITE else Color.parseColor("#D0D0D0")
        } else {
            if (active) Color.parseColor("#111111") else Color.parseColor("#555555")
        }
        if (tab.currentTextColor != target) tab.setTextColor(target)
    }

    private fun contrastRatio(foreground: Int, background: Int): Double {
        fun lum(c: Int): Double {
            fun channel(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
        }
        val l1 = lum(foreground)
        val l2 = lum(background)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
}
