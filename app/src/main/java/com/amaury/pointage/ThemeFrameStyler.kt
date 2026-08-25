package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * Applique les cadres des thèmes aux contrôles uniquement.
 * Quand une photo de fond personnalisée est active, aucun grand conteneur/panneau
 * ne reçoit de texture opaque : la photo doit rester visible derrière l'interface.
 * Les boutons de pointage diamant restent totalement exclus.
 */
object ThemeFrameStyler {
    private val protectedIds = setOf("entryButton", "pauseButton", "exitButton", "settingsButton")
    private val tabIds = setOf("tabToday", "tabHistory", "tabAnalytics", "tabSalary", "tabSettings")
    private val visualIds = setOf("clockDigital", "heroClockHands", "sunIndicator")

    fun apply(root: View) {
        AutoDayNightPolarity.apply(root)
        val theme = AppThemeCatalog.current(root.context)
        if (theme.id != "natural_carbon" && theme.id != "signature_gold") return
        val dark = AppThemeCatalog.useDarkPalette(root.context)
        val customPhoto = root.context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
            .getBoolean("custom_image_bg", false)
        applyRecursive(root, theme, dark, inheritedDark = effectiveDark(theme, dark), customPhoto = customPhoto)
    }

    private fun applyRecursive(view: View, theme: HpTheme, dark: Boolean, inheritedDark: Boolean, customPhoto: Boolean) {
        val id = resourceName(view)
        if (id in protectedIds || id in visualIds || view is RedDiamondFinalButton) return

        val localDark = when {
            theme.id == "natural_carbon" && hasThemedBackground(view) -> true
            theme.id == "signature_gold" && hasThemedBackground(view) -> dark
            else -> inheritedDark
        }

        when {
            id in tabIds && view is TextView -> styleTab(view, theme, localDark)
            view is Button -> styleControl(view, theme, localDark)
            view is EditText -> styleControl(view, theme, localDark)
            view is Switch -> styleControl(view, theme, localDark)
            view is ViewGroup && isFramedContainer(view, id) -> {
                if (customPhoto) clearContainerBackground(view) else ensureBackground(view, theme)
            }
            view is TextView -> styleText(view, theme, localDark)
        }

        if (view is ViewGroup && view !is True3DButtonHost) {
            val childDark = if (!customPhoto && isFramedContainer(view, id)) effectiveDark(theme, dark) else localDark
            for (i in 0 until view.childCount) applyRecursive(view.getChildAt(i), theme, dark, childDark, customPhoto)
        }
    }

    private fun effectiveDark(theme: HpTheme, dark: Boolean): Boolean =
        if (theme.id == "natural_carbon") true else dark

    private fun hasThemedBackground(view: View): Boolean =
        view.background is CarbonCompositeDrawable || view.background != null

    private fun isFramedContainer(view: ViewGroup, id: String): Boolean {
        if (id == "navigationTabs" || id == "pointageButtons") return false
        if (id == "contentPanel" || id == "gpsSettingsPanel" || id == "analyticsPdfPanel") return true
        if (id.contains("panel", ignoreCase = true) || id.contains("card", ignoreCase = true)) return true
        val tag = view.tag?.toString().orEmpty()
        return tag == "settings_personalization_installed" ||
            tag.contains("panel", ignoreCase = true) || tag.contains("card", ignoreCase = true)
    }

    private fun clearContainerBackground(view: View) {
        // Important : ne jamais mettre une texture Carbone/Céleste sur un grand panneau
        // lorsqu'une photo personnalisée est active. Sinon elle forme le grand rectangle
        // opaque visible sur la capture et masque la photo choisie par l'utilisateur.
        view.backgroundTintList = null
        view.background = null
        view.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun ensureBackground(view: View, theme: HpTheme) {
        view.backgroundTintList = null
        when (theme.id) {
            "natural_carbon" -> if (view.background !is CarbonCompositeDrawable) {
                view.background = CarbonCompositeDrawable(view.context)
            }
            "signature_gold" -> if (view.background == null || view.background is CarbonCompositeDrawable) {
                view.background = view.context.getDrawable(R.drawable.hp_panel)?.mutate()
            }
        }
    }

    private fun styleControl(view: View, theme: HpTheme, dark: Boolean) {
        ensureBackground(view, theme)
        val textColor = if (dark) Color.WHITE else Color.parseColor("#111111")
        val hintColor = if (dark) Color.parseColor("#D8D8D8") else Color.parseColor("#555555")
        when (view) {
            is Button -> if (view.currentTextColor != textColor) view.setTextColor(textColor)
            is EditText -> {
                if (view.currentTextColor != textColor) view.setTextColor(textColor)
                view.setHintTextColor(hintColor)
            }
            is Switch -> if (view.currentTextColor != textColor) view.setTextColor(textColor)
        }
    }

    private fun styleText(view: TextView, theme: HpTheme, dark: Boolean) {
        val textColor = if (dark) Color.WHITE else Color.parseColor("#111111")
        val accent = if (dark) theme.accentLight else theme.accent
        val current = view.currentTextColor
        val shouldKeepAccent = contrastRatio(current, if (dark) Color.BLACK else Color.WHITE) >= 4.5 &&
            (current == theme.accent || current == theme.accentLight)
        val target = if (shouldKeepAccent) accent else textColor
        if (current != target) view.setTextColor(target)
    }

    private fun styleTab(tab: TextView, theme: HpTheme, dark: Boolean) {
        ensureBackground(tab, theme)
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
