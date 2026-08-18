package com.amaury.pointage

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * Style global des boutons HP Travail.
 *
 * - garde-fou de contraste Clair / Sombre / Automatique ;
 * - relief facette "diamant" avec lumière, profondeur et bordure dorée ;
 * - interaction physique à l'appui ;
 * - les boutons graphiques Entrée / Sortie / Réglages conservent leur visuel.
 */
object ButtonReliefInstaller {
    private const val TAG_KEY = 0x4850524C // "HPRL"

    fun install(activity: Activity) {
        val decor = activity.window.decorView
        refresh(activity, decor)
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                refresh(activity, decor)
            }
        }
    }

    private fun refresh(activity: Activity, decor: View) {
        val dark = isDarkMode(activity)
        applyThemeSafety(activity, decor, dark)
        applyToTree(decor, dark)
    }

    private fun isDarkMode(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
    }

    private fun applyThemeSafety(activity: Activity, root: View, dark: Boolean) {
        val bg = Color.parseColor(if (dark) "#050505" else "#F3F0E8")
        val panel = Color.parseColor(if (dark) "#181818" else "#FFFFFF")
        val bgText = if (dark) Color.parseColor("#F7F3EC") else Color.parseColor("#111111")
        val panelText = if (dark) Color.parseColor("#F7F3EC") else Color.parseColor("#111111")
        val hint = if (dark) Color.parseColor("#C9C1B4") else Color.parseColor("#55514B")
        sanitizeView(root, bg, panel, bgText, panelText, hint, false)
    }

    private fun sanitizeView(
        view: View,
        bg: Int,
        panel: Int,
        bgText: Int,
        panelText: Int,
        hint: Int,
        inheritedPanel: Boolean
    ) {
        val id = resourceName(view)
        val ownPanel = id == "contentPanel" ||
            id == "statusCard" ||
            id == "pointageButtons" ||
            id == "gpsSettingsPanel" ||
            id == "analyticsPdfPanel" ||
            id.contains("panel", ignoreCase = true) ||
            id.contains("card", ignoreCase = true)
        val onPanel = inheritedPanel || ownPanel

        if (ownPanel && view.background != null) {
            view.backgroundTintList = ColorStateList.valueOf(panel)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                sanitizeView(view.getChildAt(i), bg, panel, bgText, panelText, hint, onPanel)
            }
        }

        val textColor = if (onPanel) panelText else bgText
        when (view) {
            is EditText -> {
                view.setTextColor(textColor)
                view.setHintTextColor(hint)
                if (view.background != null) view.backgroundTintList = ColorStateList.valueOf(panel)
            }
            is Button -> {
                val protected = isProtectedButton(id)
                if (!protected) {
                    // Le fond est géré par le style diamant. Ne pas appliquer de tint
                    // ici : un tint plat supprimerait les reflets du dégradé.
                    view.backgroundTintList = null
                    view.setTextColor(panelText)
                }
            }
            is Switch -> view.setTextColor(textColor)
            is TextView -> {
                val tab = id == "tabToday" || id == "tabHistory" || id == "tabAnalytics" || id == "tabSalary" || id == "tabSettings"
                if (!tab) {
                    val current = view.currentTextColor
                    if (!isGold(current) || contrastRatio(current, if (onPanel) panel else bg) < 4.5) {
                        view.setTextColor(textColor)
                    }
                }
            }
        }
    }

    private fun applyToTree(view: View, dark: Boolean) {
        if (view is Button) applyToButton(view, dark)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyToTree(view.getChildAt(i), dark)
        }
    }

    private fun applyToButton(button: Button, dark: Boolean) {
        val id = resourceName(button)
        val protected = isProtectedButton(id)
        val styleKey = if (dark) "diamond_dark" else "diamond_light"
        val alreadyStyled = button.getTag(TAG_KEY) == styleKey

        if (!protected && !alreadyStyled) {
            button.backgroundTintList = null
            button.background = diamondSelector(button, dark)
            button.setTag(TAG_KEY, styleKey)
        } else if (protected && button.getTag(TAG_KEY) == null) {
            // Ne pas toucher à l'image/fond des trois boutons principaux.
            button.setTag(TAG_KEY, "protected")
        }

        // L'interaction reste commune à tous les boutons, y compris les boutons images.
        installPressAnimator(button)
    }

    private fun diamondSelector(button: Button, dark: Boolean): StateListDrawable {
        val normal = diamondDrawable(button, dark, pressed = false)
        val pressed = diamondDrawable(button, dark, pressed = true)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_focused), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun diamondDrawable(button: Button, dark: Boolean, pressed: Boolean): GradientDrawable {
        val density = button.resources.displayMetrics.density
        val radius = 15f * density
        val strokeWidth = (1.2f * density).toInt().coerceAtLeast(1)

        // Les teintes restent dérivées des couleurs de base de l'application.
        // Le gradient ajoute seulement lumière et profondeur.
        val colors = if (dark) {
            if (pressed) intArrayOf(
                Color.parseColor("#24211B"),
                Color.parseColor("#171717"),
                Color.parseColor("#090909")
            ) else intArrayOf(
                Color.parseColor("#343027"),
                Color.parseColor("#1A1A1A"),
                Color.parseColor("#090909")
            )
        } else {
            if (pressed) intArrayOf(
                Color.parseColor("#E9E3D8"),
                Color.parseColor("#F7F3EB"),
                Color.parseColor("#D7CDBB")
            ) else intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#F8F4EC"),
                Color.parseColor("#D8CDBA")
            )
        }

        val stroke = if (dark) {
            if (pressed) Color.parseColor("#9D7B38") else Color.parseColor("#D6A84B")
        } else {
            if (pressed) Color.parseColor("#9A711D") else Color.parseColor("#C4932E")
        }

        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setStroke(strokeWidth, stroke)
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    private fun installPressAnimator(button: Button) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val density = button.resources.displayMetrics.density
        val normalElevation = 7f * density
        val pressedElevation = 2f * density
        val normalTranslation = 1.5f * density

        button.elevation = normalElevation
        button.stateListAnimator = StateListAnimator().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(button, "elevation", pressedElevation),
                        ObjectAnimator.ofFloat(button, "translationZ", 0f),
                        ObjectAnimator.ofFloat(button, "scaleX", 0.975f),
                        ObjectAnimator.ofFloat(button, "scaleY", 0.975f),
                        ObjectAnimator.ofFloat(button, "alpha", 0.92f)
                    )
                    duration = 75L
                }
            )
            addState(
                intArrayOf(),
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(button, "elevation", normalElevation),
                        ObjectAnimator.ofFloat(button, "translationZ", normalTranslation),
                        ObjectAnimator.ofFloat(button, "scaleX", 1f),
                        ObjectAnimator.ofFloat(button, "scaleY", 1f),
                        ObjectAnimator.ofFloat(button, "alpha", 1f)
                    )
                    duration = 150L
                }
            )
        }
    }

    private fun isProtectedButton(id: String): Boolean =
        id == "entryButton" || id == "exitButton" || id == "settingsButton"

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()

    private fun isGold(color: Int): Boolean =
        color == Color.parseColor("#D6A84B") || color == Color.parseColor("#F3D58A") || color == Color.parseColor("#795600")

    private fun contrastRatio(foreground: Int, background: Int): Double {
        fun lum(color: Int): Double {
            fun c(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * c(Color.red(color)) + 0.7152 * c(Color.green(color)) + 0.0722 * c(Color.blue(color))
        }
        val a = lum(foreground)
        val b = lum(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
