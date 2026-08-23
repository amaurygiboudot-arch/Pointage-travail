package com.amaury.pointage

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Gravity
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
    private const val TAG_DIAMOND_LAB = "diamond_lab_button"
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
                installDiamondLabIfPossible(activity)
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
                AppThemeCatalog.setCelestialNight(activity, state.night)
                updateDynamicLight(decor, state.lightAngle, state.night)
                indicator?.setNightMode(state.night)
                state.celestialAngle?.let {
                    indicator?.updateLightAngle(it)
                    indicator?.setSunVisible(true)
                } ?: indicator?.setSunVisible(false)
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
        dynamicDrawables.entries.toList().forEach { (b, d) -> if (b.rootView === decor) d.setLightAngle(angle) }
        True3DButtonInstaller.updateLight(decor, angle)
        updateJewelLights(decor, angle, night)
    }

    private fun updateJewelLights(view: View, angle: Float, night: Boolean) {
        if (view is LightReactiveJewelButton) {
            view.setLightAngle(angle)
            view.setNightLight(night)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) updateJewelLights(view.getChildAt(i), angle, night)
    }

    private fun isSolarEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SOLAR, false)

    private fun setSolarEnabled(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SOLAR, enabled).apply()
        configureSolarLighting(activity, activity.window.decorView)
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
            gravity = Gravity.CENTER
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
            setOnClickListener {
                val themes = AppThemeCatalog.themes
                val selected = themes.indexOfFirst { it.id == AppThemeCatalog.current(activity).id }.coerceAtLeast(0)
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Choisir le thème")
                    .setSingleChoiceItems(themes.map { it.label }.toTypedArray(), selected) { d, which ->
                        val targetTheme = themes[which]
                        if (targetTheme.id == "diamond_crystal" && AppThemeCatalog.current(activity).id != "diamond_crystal") {
                            d.dismiss()
                            showDiamondEngineWarning(activity, targetTheme)
                        } else {
                            AppThemeCatalog.set(activity, targetTheme)
                            d.dismiss()
                            activity.window.decorView.post { activity.recreate() }
                        }
                    }
                    .setNegativeButton("Annuler", null)
                    .create()
                dialog.setOnShowListener {
                    if (AppThemeCatalog.current(activity).id == "diamond_crystal") {
                        True3DButtonInstaller.install(dialog.window?.decorView ?: return@setOnShowListener, currentLightAngle)
                    }
                }
                dialog.show()
            }
        }
        section.addView(button, if (section.childCount >= 2) 2 else section.childCount,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46)).apply {
                topMargin = dp(activity, 4); bottomMargin = dp(activity, 4)
            })
    }

    private fun showDiamondEngineWarning(activity: Activity, targetTheme: HpTheme) {
        AlertDialog.Builder(activity)
            .setTitle("💎 Activer le moteur Diamant 3D ?")
            .setMessage(
                "Le thème Diamant utilise un moteur de simulation 3D dédié pour calculer les facettes, " +
                    "la profondeur, les reflets et la réaction à l’inclinaison du téléphone.\n\n" +
                    "Ce moteur demande plus de ressources graphiques et peut consommer davantage de batterie " +
                    "que les autres thèmes.\n\n" +
                    "En continuant, l’application bascule sur le moteur Diamant 3D uniquement tant que ce thème est actif."
            )
            .setPositiveButton("ACTIVER LE DIAMANT 3D") { _, _ ->
                AppThemeCatalog.set(activity, targetTheme)
                activity.window.decorView.post { activity.recreate() }
            }
            .setNegativeButton("ANNULER", null)
            .show()
    }

    private fun installDiamondLabIfPossible(activity: Activity) {
        if (activity !is MainActivity) return
        if (AppThemeCatalog.current(activity).id != "diamond_crystal") return
        val section = activity.window.decorView.findViewWithTag<LinearLayout>("settings_personalization_installed") ?: return
        if (section.findViewWithTag<View>(TAG_DIAMOND_LAB) != null) return
        val button = Button(activity).apply {
            tag = TAG_DIAMOND_LAB
            text = "💎 LABORATOIRE DIAMANT"
            isAllCaps = false
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            setOnClickListener { activity.startActivity(Intent(activity, DiamondLabActivity::class.java)) }
        }
        val themeIndex = (0 until section.childCount).firstOrNull { section.getChildAt(it).tag == TAG_THEME_BUTTON }
        val insertAt = if (themeIndex != null) themeIndex + 1 else section.childCount
        section.addView(button, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 4); bottomMargin = dp(activity, 4)
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
            setOnCheckedChangeListener { _, checked -> setSolarEnabled(activity, checked) }
        }
        styleSwitch(activity, toggle)
        val labIndex = (0 until section.childCount).firstOrNull { section.getChildAt(it).tag == TAG_DIAMOND_LAB }
        val themeIndex = (0 until section.childCount).firstOrNull { section.getChildAt(it).tag == TAG_THEME_BUTTON }
        val insertAt = labIndex?.plus(1) ?: themeIndex?.plus(1) ?: if (section.childCount >= 2) 2 else section.childCount
        section.addView(toggle, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun styleSwitch(context: Context, view: Switch) {
        view.gravity = Gravity.CENTER_VERTICAL
        view.textAlignment = View.TEXT_ALIGNMENT_CENTER
        view.minHeight = dp(context, 64)
        view.minimumHeight = dp(context, 64)
        view.isSingleLine = false
        view.maxLines = 2
        view.setPadding(dp(context, 18), dp(context, 8), dp(context, 8), dp(context, 8))
    }

    private fun refresh(activity: Activity, decor: View) {
        val theme = AppThemeCatalog.current(activity)
        val dark = if (theme.id == "diamond_crystal") true else isDarkMode(activity)
        applyThemeSafety(activity, decor, dark, theme)
        applyToTree(decor, dark, theme)
        installThemeSelectorIfPossible(activity)
        installDiamondLabIfPossible(activity)
        installSolarToggleIfPossible(activity)
        if (theme.id == "diamond_crystal") {
            True3DButtonInstaller.install(decor, currentLightAngle)
        }
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
        val text = if (dark) theme.darkText else theme.lightText
        val hint = if (dark) theme.darkHint else theme.lightHint
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val custom = prefs.getBoolean("custom_bg", false) || prefs.getBoolean("custom_image_bg", false)
        if (!custom) {
            activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)?.setBackgroundColor(bg)
            activity.window.statusBarColor = bg
            activity.window.navigationBarColor = bg
        }
        sanitizeView(root, bg, panel, text, text, hint, false, true, dark, theme)
    }

    private fun sanitizeView(view: View, bg: Int, panel: Int, bgText: Int, panelText: Int, hint: Int, inheritedPanel: Boolean, isRoot: Boolean = false, dark: Boolean, theme: HpTheme) {
        val id = resourceName(view)
        val named = id == "contentPanel" || id == "statusCard" || id == "pointageButtons" || id == "gpsSettingsPanel" || id == "analyticsPdfPanel" || id.contains("panel", true) || id.contains("card", true)
        val anonymous = !isRoot && view is ViewGroup && view !is ScrollView && view.background != null && !isProtectedContainer(id)
        val own = named || anonymous
        val onPanel = inheritedPanel || own
        if (own && view.background != null && view.background.alpha > 0 && !isProtectedContainer(id)) {
            view.backgroundTintList = ColorStateList.valueOf(panel)
            view.background.mutate().alpha = if (theme.id == "diamond_crystal") 205 else if (dark) 232 else 244
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) sanitizeView(view.getChildAt(i), bg, panel, bgText, panelText, hint, onPanel, false, dark, theme)
        val tc = if (onPanel) panelText else bgText
        when (view) {
            is EditText -> { view.setTextColor(tc); view.setHintTextColor(hint) }
            is LightReactiveJewelButton -> view.setJewelAccent(theme.accent, theme.accentLight)
            is Button -> if (!isProtectedButton(id)) { view.backgroundTintList = null; view.setTextColor(panelText) }
            is Switch -> {
                view.setTextColor(tc)
                styleSwitch(view.context, view)
            }
            is TextView -> {
                val tab = id == "tabToday" || id == "tabHistory" || id == "tabAnalytics" || id == "tabSalary" || id == "tabSettings"
                if (!tab) {
                    val color = view.currentTextColor
                    if (isKnownAccent(color)) view.setTextColor(if (dark) theme.accentLight else theme.accent)
                    else if (contrastRatio(color, if (onPanel) panel else bg) < 4.5) view.setTextColor(tc)
                }
            }
        }
    }

    private fun applyToTree(view: View, dark: Boolean, theme: HpTheme) {
        if (view is Button) applyToButton(view, dark, theme)
        if (view is ViewGroup && view !is True3DButtonHost) for (i in 0 until view.childCount) applyToTree(view.getChildAt(i), dark, theme)
    }

    private fun applyToButton(button: Button, dark: Boolean, theme: HpTheme) {
        val id = resourceName(button)
        val protected = isProtectedButton(id)
        val styleKey = "material_${theme.id}_${if (dark) "dark" else "light"}_v8"

        if (theme.id == "diamond_crystal") {
            button.backgroundTintList = null
            button.background = null
            button.setTextColor(Color.parseColor("#F7FCFF"))
            dynamicDrawables.remove(button)
            if (button is LightReactiveJewelButton) {
                button.alpha = 0f
            }
            button.setTag(TAG_KEY, styleKey)
            return
        }

        if (button is LightReactiveJewelButton) {
            button.alpha = 1f
            button.setJewelAccent(theme.accent, theme.accentLight)
            button.setLightAngle(currentLightAngle)
            button.setNightLight(currentNight)
            button.setTag(TAG_KEY, "jewel_${theme.id}")
        } else if (!protected && button.getTag(TAG_KEY) != styleKey) {
            button.alpha = 1f
            button.backgroundTintList = null
            val d = DynamicDiamondDrawable(dark, button.resources.displayMetrics.density, theme.accent, theme.accentLight).apply { setLightAngle(currentLightAngle) }
            button.background = d
            dynamicDrawables[button] = d
            button.setTag(TAG_KEY, styleKey)
        } else if (protected && button.getTag(TAG_KEY) == null) {
            button.setTag(TAG_KEY, "protected")
        }
        installPressAnimator(button)
    }

    private fun installPressAnimator(button: Button) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val d = button.resources.displayMetrics.density
        button.elevation = 8f * d
        button.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(button, "elevation", 2f*d), ObjectAnimator.ofFloat(button, "scaleX", .965f), ObjectAnimator.ofFloat(button, "scaleY", .965f)); duration = 70
            })
            addState(intArrayOf(), AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(button, "elevation", 8f*d), ObjectAnimator.ofFloat(button, "scaleX", 1f), ObjectAnimator.ofFloat(button, "scaleY", 1f)); duration = 160
            })
        }
    }

    private fun isProtectedButton(id: String) = id == "entryButton" || id == "pauseButton" || id == "exitButton" || id == "settingsButton"
    private fun isProtectedContainer(id: String) = id == "heroPanel" || id == "heroClock" || id == "headerImage" || id == "navigationTabs"
    private fun resourceName(view: View) = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
    private fun isKnownAccent(color: Int) = AppThemeCatalog.themes.any { color == it.accent || color == it.accentLight } || color == Color.parseColor("#795600")
    private fun contrastRatio(f: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun x(v: Int): Double { val s = v / 255.0; return if (s <= .03928) s / 12.92 else Math.pow((s + .055) / 1.055, 2.4) }
            return .2126*x(Color.red(c)) + .7152*x(Color.green(c)) + .0722*x(Color.blue(c))
        }
        val a = lum(f); val z = lum(b)
        return (maxOf(a,z)+.05)/(minOf(a,z)+.05)
    }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
