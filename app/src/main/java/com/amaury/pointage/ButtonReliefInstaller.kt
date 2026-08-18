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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Style global des boutons et garde-fou de lisibilité HP Travail.
 *
 * Les conteneurs visuels sans id (notamment les cartes Salaire créées
 * dynamiquement) sont également reconnus comme surfaces de panneau.
 */
object ButtonReliefInstaller {
    private const val TAG_KEY = 0x4850524C
    private val dynamicDrawables = WeakHashMap<Button, DynamicDiamondDrawable>()
    private var currentLightAngle = -55f

    fun install(activity: Activity) {
        val decor = activity.window.decorView
        refresh(activity, decor)

        LightDirectionController.attach(activity) { angle ->
            currentLightAngle = angle
            dynamicDrawables.entries.toList().forEach { (button, drawable) ->
                if (button.rootView === decor) drawable.setLightAngle(angle)
            }
        }

        decor.viewTreeObserver.addOnGlobalLayoutListener {
            if (!activity.isFinishing && !activity.isDestroyed) refresh(activity, decor)
        }
    }

    private fun refresh(activity: Activity, decor: View) {
        val dark = isDarkMode(activity)
        applyThemeSafety(decor, dark)
        applyToTree(decor, dark)
    }

    private fun isDarkMode(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (mode) { "light" -> false; "dark" -> true; else -> systemDark }
    }

    private fun applyThemeSafety(root: View, dark: Boolean) {
        val bg = Color.parseColor(if (dark) "#050505" else "#F3F0E8")
        val panel = Color.parseColor(if (dark) "#181818" else "#FFFDF9")
        val bgText = Color.parseColor(if (dark) "#F7F3EC" else "#111111")
        val panelText = Color.parseColor(if (dark) "#F7F3EC" else "#111111")
        val hint = Color.parseColor(if (dark) "#C9C1B4" else "#55514B")
        sanitizeView(root, bg, panel, bgText, panelText, hint, false, true)
    }

    private fun sanitizeView(
        view: View,
        bg: Int,
        panel: Int,
        bgText: Int,
        panelText: Int,
        hint: Int,
        inheritedPanel: Boolean,
        isRoot: Boolean = false
    ) {
        val id = resourceName(view)
        val namedPanel = id == "contentPanel" || id == "statusCard" || id == "pointageButtons" ||
            id == "gpsSettingsPanel" || id == "analyticsPdfPanel" ||
            id.contains("panel", ignoreCase = true) || id.contains("card", ignoreCase = true)

        // Les cartes Salaire et plusieurs blocs ajoutés dynamiquement n'ont aucun id.
        // Un ViewGroup avec son propre fond est donc une surface visuelle, sauf racine/scroll.
        val anonymousSurface = !isRoot && view is ViewGroup && view !is ScrollView &&
            view.background != null && !isProtectedContainer(id)
        val ownPanel = namedPanel || anonymousSurface
        val onPanel = inheritedPanel || ownPanel

        if (ownPanel && view.background != null) {
            view.backgroundTintList = ColorStateList.valueOf(panel)
            // Avec une photo de fond, garder une opacité suffisante pour lire.
            view.background.mutate().alpha = if (dark) 232 else 244
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                sanitizeView(view.getChildAt(i), bg, panel, bgText, panelText, hint, onPanel, false)
            }
        }

        val textColor = if (onPanel) panelText else bgText
        when (view) {
            is EditText -> {
                view.setTextColor(textColor)
                view.setHintTextColor(hint)
                if (view.background != null) {
                    view.backgroundTintList = ColorStateList.valueOf(panel)
                    view.background.mutate().alpha = if (dark) 240 else 250
                }
            }
            is Button -> {
                if (!isProtectedButton(id)) {
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
        if (view is ViewGroup) for (i in 0 until view.childCount) applyToTree(view.getChildAt(i), dark)
    }

    private fun applyToButton(button: Button, dark: Boolean) {
        val id = resourceName(button)
        val protected = isProtectedButton(id)
        val styleKey = if (dark) "diamond_dynamic_dark_v2" else "diamond_dynamic_light_v2"
        val alreadyStyled = button.getTag(TAG_KEY) == styleKey

        if (!protected && !alreadyStyled) {
            button.backgroundTintList = null
            val drawable = DynamicDiamondDrawable(dark, button.resources.displayMetrics.density).apply {
                setLightAngle(currentLightAngle)
            }
            button.background = drawable
            dynamicDrawables[button] = drawable
            button.setTag(TAG_KEY, styleKey)
        } else if (protected && button.getTag(TAG_KEY) == null) {
            button.setTag(TAG_KEY, "protected")
        }

        installPressAnimator(button)
    }

    private fun installPressAnimator(button: Button) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val density = button.resources.displayMetrics.density
        val normalElevation = 8f * density
        val pressedElevation = 2f * density
        val normalTranslation = 2f * density

        button.elevation = normalElevation
        button.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(button, "elevation", pressedElevation),
                    ObjectAnimator.ofFloat(button, "translationZ", 0f),
                    ObjectAnimator.ofFloat(button, "scaleX", 0.97f),
                    ObjectAnimator.ofFloat(button, "scaleY", 0.97f),
                    ObjectAnimator.ofFloat(button, "alpha", 0.94f)
                )
                duration = 70L
            })
            addState(intArrayOf(), AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(button, "elevation", normalElevation),
                    ObjectAnimator.ofFloat(button, "translationZ", normalTranslation),
                    ObjectAnimator.ofFloat(button, "scaleX", 1f),
                    ObjectAnimator.ofFloat(button, "scaleY", 1f),
                    ObjectAnimator.ofFloat(button, "alpha", 1f)
                )
                duration = 160L
            })
        }
    }

    private fun isProtectedButton(id: String): Boolean =
        id == "entryButton" || id == "exitButton" || id == "settingsButton"

    private fun isProtectedContainer(id: String): Boolean =
        id == "heroPanel" || id == "heroClock" || id == "headerImage"

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
