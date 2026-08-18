package com.amaury.pointage

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * Donne un relief discret à tous les boutons sans modifier leur identité visuelle.
 * Sert aussi de garde-fou de contraste : les vues créées dynamiquement sont
 * remises dans une combinaison lisible Clair / Sombre / Automatique.
 */
object ButtonReliefInstaller {
    private const val TAG_KEY = 0x4850524C // "HPRL"

    fun install(activity: Activity) {
        val decor = activity.window.decorView
        applyThemeSafety(activity, decor)
        applyToTree(decor)
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                applyThemeSafety(activity, decor)
                applyToTree(decor)
            }
        }
    }

    private fun applyThemeSafety(activity: Activity, root: View) {
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }

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
                // Les trois boutons graphiques principaux gardent leurs images/couleurs propres.
                val protected = id == "entryButton" || id == "exitButton" || id == "settingsButton"
                if (!protected) {
                    view.backgroundTintList = ColorStateList.valueOf(panel)
                    view.setTextColor(panelText)
                }
            }
            is Switch -> {
                view.setTextColor(textColor)
            }
            is TextView -> {
                // La barre d'onglets est gérée par LuxuryUiInstaller afin de conserver
                // la distinction actif/inactif.
                val tab = id == "tabToday" || id == "tabHistory" || id == "tabAnalytics" || id == "tabSalary" || id == "tabSettings"
                if (!tab) {
                    val current = view.currentTextColor
                    // Conserver l'or uniquement lorsqu'il reste réellement lisible.
                    if (!isGold(current) || contrastRatio(current, if (onPanel) panel else bg) < 4.5) {
                        view.setTextColor(textColor)
                    }
                }
            }
        }
    }

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

    private fun applyToTree(view: View) {
        if (view is Button) applyToButton(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyToTree(view.getChildAt(i))
        }
    }

    private fun applyToButton(button: Button) {
        if (button.getTag(TAG_KEY) == true) return
        button.setTag(TAG_KEY, true)

        val density = button.resources.displayMetrics.density
        val normalElevation = 4f * density
        val pressedElevation = 1.5f * density
        val normalTranslation = 1f * density

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.elevation = normalElevation
            button.stateListAnimator = StateListAnimator().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(button, "elevation", pressedElevation),
                            ObjectAnimator.ofFloat(button, "translationZ", 0f),
                            ObjectAnimator.ofFloat(button, "scaleX", 0.985f),
                            ObjectAnimator.ofFloat(button, "scaleY", 0.985f)
                        )
                        duration = 80L
                    }
                )
                addState(
                    intArrayOf(),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(button, "elevation", normalElevation),
                            ObjectAnimator.ofFloat(button, "translationZ", normalTranslation),
                            ObjectAnimator.ofFloat(button, "scaleX", 1f),
                            ObjectAnimator.ofFloat(button, "scaleY", 1f)
                        )
                        duration = 120L
                    }
                )
            }
        }
    }
}
