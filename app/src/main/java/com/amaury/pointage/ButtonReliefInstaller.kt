package com.amaury.pointage

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.WeakHashMap

object ButtonReliefInstaller {
    private const val TAG_KEY = 0x4850524C
    private const val PREFS = "appearance_settings"
    private const val PREF_SOLAR = "solar_lighting_enabled"
    private const val TAG_SOLAR_SWITCH = "solar_lighting_switch"
    private const val TAG_THEME_BUTTON = "visual_theme_button"
    private const val FIXED_LIGHT_ANGLE = -55f

    private val dynamicDrawables = WeakHashMap<Button, DynamicDiamondDrawable>()
    private var currentLightAngle = FIXED_LIGHT_ANGLE
    private var currentNight = false

    fun install(activity: Activity) {
        val decor = activity.window.decorView
        refresh(activity, decor)
        configureSolarLighting(activity, decor)

        decor.viewTreeObserver.addOnGlobalLayoutListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                refresh(activity, decor)
                installThemeSelectorIfPossible(activity)
                installSolarToggleIfPossible(activity)
            }
        }
    }

    private fun configureSolarLighting(activity: Activity, decor: View) {
        val indicator = activity.findViewById<SunIndicatorView>(R.id.sunIndicator)
        if (isSolarEnabled(activity)) {
            indicator?.setSunVisible(false)
            LightDirectionController.attach(activity) { state ->
                if (!isSolarEnabled(activity)) return@attach
                currentLightAngle = state.lightAngle
                currentNight = state.night
                updateDynamicLight(decor, state.lightAngle, state.night)

                indicator?.setNightMode(state.night)
                val celestial = state.celestialAngle
                if (celestial != null) {
                    indicator?.updateLightAngle(celestial)
                    indicator?.setSunVisible(true)
                } else {
                    indicator?.setSunVisible(false)
                }
            }
        } else {
            LightDirectionController.detach(activity)
            indicator?.setSunVisible(false)
            currentNight = false
            currentLightAngle = FIXED_LIGHT_ANGLE
            updateDynamicLight(decor, FIXED_LIGHT_ANGLE, false)
        }
    }

    private fun updateDynamicLight(decor: View, angle: Float, night: Boolean) {
        dynamicDrawables.entries.toList().forEach { (button, drawable) ->
            if (button.rootView === decor) drawable.setLightAngle(angle)
        }
        updateJewelLights(decor, angle, night)
    }

    private fun updateJewelLights(view: View, angle: Float, night: Boolean) {
        if (view is LightReactiveJewelButton) {
            view.setLightAngle(angle)
            view.setNightLight(night)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) updateJewelLights(view.getChildAt(i), angle, night)
        }
    }

    private fun isSolarEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SOLAR, false)

    private fun setSolarEnabled(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SOLAR, enabled).apply()
        val decor = activity.window.decorView
        val indicator = activity.findViewById<SunIndicatorView>(R.id.sunIndicator)
        if (enabled) {
            indicator?.setSunVisible(false)
            LightDirectionController.attach(activity) { state ->
                if (!isSolarEnabled(activity)) return@attach
                currentLightAngle = state.lightAngle
                currentNight = state.night
                updateDynamicLight(decor, state.lightAngle, state.night)
                indicator?.setNightMode(state.night)
                val celestial = state.celestialAngle
                if (celestial != null) {
                    indicator?.updateLightAngle(celestial)
                    indicator?.setSunVisible(true)
                } else indicator?.setSunVisible(false)
            }
        } else {
            LightDirectionController.detach(activity)
            indicator?.setSunVisible(false)
            currentNight = false
            currentLightAngle = FIXED_LIGHT_ANGLE
            updateDynamicLight(decor, FIXED_LIGHT_ANGLE, false)
        }
    }

    private fun installThemeSelectorIfPossible(activity: Activity) {
        if (activity !is MainActivity) return
        val section = activity.window.decorView.findViewWithTag<LinearLayout>("settings_personalization_installed") ?: return
        if (section.findViewWithTag<View>(TAG_THEME_BUTTON) != null) return

        val current = AppThemeCatalog.current(activity)
        val button = Button(activity).apply {
            tag = TAG_THEME_BUTTON
            text = "THÈME : ${current.label.uppercase()}"
            isAllCaps = false
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            gravity = android.view.Gravity.CENTER
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
            setOnClickListener {
                val themes = AppThemeCatalog.themes
                val selected = themes.indexOfFirst { it.id == AppThemeCatalog.current(activity).id }.coerceAtLeast(0)
                AlertDialog.Builder(activity)
                    .setTitle("Choisir le thème")
                    .setSingleChoiceItems(themes.map { it.label }.toTypedArray(), selected) { dialog, which ->
                        AppThemeCatalog.set(activity, themes[which])
                        dialog.dismiss()
                        activity.window.decorView.post { activity.recreate() }
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }

        val insertAt = if (section.childCount >= 2) 2 else section.childCount
        section.addView(button, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46)).apply {
            topMargin = dp(activity, 4)
            bottomMargin = dp(activity, 4)
        })
    }

    private fun installSolarToggleIfPossible(activity: Activity) {
        if (activity !is MainActivity) return
        val section = activity.window.decorView.findViewWithTag<LinearLayout>("settings_personalization_installed") ?: return
        if (section.findViewWithTag<View>(TAG_SOLAR_SWITCH) != null) return

        val toggle = Switch(activity).apply {
            tag = TAG_SOLAR_SWITCH
            text = "Éclairage soleil / lune dynamique"
            textSize = 14f
            isChecked = isSolarEnabled(activity)
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
            setOnCheckedChangeListener { _, checked -> setSolarEnabled(activity, checked) }
        }

        val themeIndex = (0 until section.childCount).firstOrNull { section.getChildAt(it).tag == TAG_THEME_BUTTON }
        val insertAt = if (themeIndex != null) themeIndex + 1 else if (section.childCount >= 2) 2 else section.childCount
        section.addView(toggle, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refresh(activity: Activity, decor: View) {
        val dark = isDarkMode(activity)
        val theme = AppThemeCatalog.current(activity)
        applyThemeSafety(activity, decor, dark, theme)
        applyToTree(decor, dark, theme)
        installThemeSelectorIfPossible(activity)
        installSolarToggleIfPossible(activity)
    }

    private fun isDarkMode(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (mode) { "light" -> false; "dark" -> true; else -> systemDark }
    }

    private fun applyThemeSafety(activity: Activity, root: View, dark: Boolean, theme: HpTheme) {
        val bg = if (dark) theme.darkBackground else theme.lightBackground
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val bgText = if (dark) theme.darkText else theme.lightText
        val panelText = bgText
        val hint = if (dark) theme.darkHint else theme.lightHint

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasCustomBg = prefs.getBoolean("custom_bg", false) || prefs.getBoolean("custom_image_bg", false)
        if (!hasCustomBg) {
            activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)?.setBackgroundColor(bg)
            activity.window.statusBarColor = bg
            activity.window.navigationBarColor = bg
        }

        sanitizeView(root, bg, panel, bgText, panelText, hint, false, true, dark, theme)
    }

    private fun sanitizeView(view: View, bg: Int, panel: Int, bgText: Int, panelText: Int, hint: Int, inheritedPanel: Boolean, isRoot: Boolean = false, dark: Boolean, theme: HpTheme) {
        val id = resourceName(view)
        val namedPanel = id == "contentPanel" || id == "statusCard" || id == "pointageButtons" || id == "gpsSettingsPanel" || id == "analyticsPdfPanel" || id.contains("panel", ignoreCase = true) || id.contains("card", ignoreCase = true)
        val anonymousSurface = !isRoot && view is ViewGroup && view !is ScrollView && view.background != null && !isProtectedContainer(id)
        val ownPanel = namedPanel || anonymousSurface
        val onPanel = inheritedPanel || ownPanel

        if (ownPanel && view.background != null && view.background.alpha > 0) {
            view.backgroundTintList = ColorStateList.valueOf(panel)
            view.background.mutate().alpha = if (dark) 232 else 244
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) sanitizeView(view.getChildAt(i), bg, panel, bgText, panelText, hint, onPanel, false, dark, theme)
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
            is LightReactiveJewelButton -> view.setJewelAccent(theme.accent, theme.accentLight)
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
                    if (isKnownAccent(current)) view.setTextColor(if (dark) theme.accentLight else theme.accent)
                    else if (contrastRatio(current, if (onPanel) panel else bg) < 4.5) view.setTextColor(textColor)
                }
            }
        }
    }

    private fun applyToTree(view: View, dark: Boolean, theme: HpTheme) {
        if (view is Button) applyToButton(view, dark, theme)
        if (view is ViewGroup) for (i in 0 until view.childCount) applyToTree(view.getChildAt(i), dark, theme)
    }

    private fun applyToButton(button: Button, dark: Boolean, theme: HpTheme) {
        val id = resourceName(button)
        val protected = isProtectedButton(id)
        val styleKey = "diamond_${theme.id}_${if (dark) "dark" else "light"}_v5"
        val alreadyStyled = button.getTag(TAG_KEY) == styleKey

        if (button is LightReactiveJewelButton) {
            button.setJewelAccent(theme.accent, theme.accentLight)
            button.setLightAngle(currentLightAngle)
            button.setNightLight(currentNight)
            button.setTag(TAG_KEY, "jewel_${theme.id}")
        } else if (!protected && !alreadyStyled) {
            button.backgroundTintList = null
            val drawable = DynamicDiamondDrawable(
                dark = dark,
                density = button.resources.displayMetrics.density,
                accent = theme.accent,
                accentLight = theme.accentLight
            ).apply { setLightAngle(currentLightAngle) }
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
                    ObjectAnimator.ofFloat(button, "scaleX", 0.965f),
                    ObjectAnimator.ofFloat(button, "scaleY", 0.965f),
                    ObjectAnimator.ofFloat(button, "alpha", 0.95f)
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

    private fun isProtectedButton(id: String): Boolean = id == "entryButton" || id == "pauseButton" || id == "exitButton" || id == "settingsButton"
    private fun isProtectedContainer(id: String): Boolean = id == "heroPanel" || id == "heroClock" || id == "headerImage"
    private fun resourceName(view: View): String = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
    private fun isKnownAccent(color: Int): Boolean = AppThemeCatalog.themes.any { color == it.accent || color == it.accentLight } || color == Color.parseColor("#795600")

    private fun contrastRatio(foreground: Int, background: Int): Double {
        fun lum(color: Int): Double {
            fun c(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * c(Color.red(color)) + 0.7152 * c(Color.green(color)) + 0.0722 * c(Color.blue(color))
        }
        val a = lum(foreground); val b = lum(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
