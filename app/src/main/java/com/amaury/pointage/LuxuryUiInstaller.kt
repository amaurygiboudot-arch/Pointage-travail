package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import java.util.WeakHashMap

object LuxuryUiInstaller {
    private const val TAG_TRANSPARENCY = "hp_ui_transparency_control"
    private const val TAG_FONT_CONTROL = "hp_font_size_control"
    private const val PREF_TRANSPARENCY = "ui_transparency"
    private const val PREF_FONT_SCALE = "ui_font_scale"
    private val appearanceListeners = WeakHashMap<MainActivity, SharedPreferences.OnSharedPreferenceChangeListener>()

    fun install(activity: MainActivity) {
        val digital = activity.findViewById<TextClock>(R.id.clockDigital) ?: return
        val analog = activity.findViewById<HpAnalogClockView>(R.id.heroClockHands)

        digital.visibility = View.GONE

        val buttons = activity.findViewById<LinearLayout>(R.id.pointageButtons)
        fun syncTodayVisibility() {
            analog?.visibility = if (buttons?.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            digital.visibility = View.GONE
        }
        syncTodayVisibility()
        buttons?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncTodayVisibility()
            syncTabs(activity)
            syncTodayLuxuryText(activity)
            normalizeTypography(activity.window.decorView, activity)
            applyTransparency(activity)
            tidySettingsSection(activity)
        }
        digital.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (digital.visibility == View.VISIBLE) digital.visibility = View.GONE
        }

        val theme = AppThemeCatalog.current(activity)
        val dark = AppThemeCatalog.useDarkPalette(activity)

        activity.findViewById<TextView>(R.id.logoText)?.apply {
            text = "♛\nH  P\nT R A V A I L"
            gravity = Gravity.CENTER
            setTextColor(if (dark) theme.accentLight else theme.accent)
            typeface = Typeface.create("serif", Typeface.BOLD)
            textSize = 16f
            letterSpacing = 0.08f
        }

        activity.findViewById<TextView>(R.id.statusCard)?.apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = 16f
            letterSpacing = 0.04f
        }

        activity.findViewById<TextView>(R.id.contentTitle)?.apply {
            setTextColor(if (dark) theme.accentLight else theme.accent)
            typeface = Typeface.create("serif", Typeface.BOLD)
            textSize = 16f
            letterSpacing = 0.08f
        }

        activity.findViewById<TextView>(R.id.historyText)?.apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = 14f
            letterSpacing = 0.03f
        }

        installAppearanceListener(activity)
        installTransparencyControl(activity)
        installFontSizeControl(activity)
        tidySettingsSection(activity)

        AppearanceManager.apply(activity)
        activity.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
        syncTabs(activity)
        syncTodayLuxuryText(activity)
        normalizeTypography(activity.window.decorView, activity)
        applyTransparency(activity)

        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && !activity.isFinishing && !activity.isDestroyed) {
                decor.post {
                    applyThemeColors(activity)
                    syncTabs(activity)
                    syncTodayLuxuryText(activity)
                    normalizeTypography(decor, activity)
                    applyTransparency(activity)
                    installFontSizeControl(activity)
                    tidySettingsSection(activity)
                }
            }
        }

        decor.post {
            AppearanceManager.apply(activity)
            applyThemeColors(activity)
            syncTabs(activity)
            syncTodayLuxuryText(activity)
            normalizeTypography(decor, activity)
            applyTransparency(activity)
            installFontSizeControl(activity)
            tidySettingsSection(activity)
        }
    }

    private fun applyThemeColors(activity: MainActivity) {
        val theme = AppThemeCatalog.current(activity)
        val dark = AppThemeCatalog.useDarkPalette(activity)
        val accent = if (dark) theme.accentLight else theme.accent
        val text = if (dark) theme.darkText else theme.lightText
        activity.findViewById<TextView>(R.id.logoText)?.setTextColor(accent)
        activity.findViewById<TextView>(R.id.contentTitle)?.setTextColor(accent)
        activity.findViewById<TextView>(R.id.statusCard)?.setTextColor(text)
        activity.findViewById<TextView>(R.id.historyText)?.setTextColor(text)
    }

    private fun installTransparencyControl(activity: MainActivity) {
        val gpsPanel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        val settingsSection = gpsPanel.findViewWithTag<View>("settings_personalization_installed") as? LinearLayout ?: return
        if (settingsSection.findViewWithTag<View>(TAG_TRANSPARENCY) != null) return

        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val wrapper = LinearLayout(activity).apply {
            tag = TAG_TRANSPARENCY
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        val label = TextView(activity).apply { textSize = 14f }
        val seekBar = SeekBar(activity).apply {
            max = 100
            progress = prefs.getInt(PREF_TRANSPARENCY, 0).coerceIn(0, 100)
        }

        fun updateLabel(value: Int) {
            label.text = "TRANSPARENCE DES BOUTONS ET AFFICHAGES : $value %"
        }
        updateLabel(seekBar.progress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabel(progress)
                if (fromUser) {
                    prefs.edit().putInt(PREF_TRANSPARENCY, progress).apply()
                    applyTransparency(activity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        wrapper.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrapper.addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        settingsSection.addView(wrapper, findWidgetSectionIndex(settingsSection))
    }

    private fun installFontSizeControl(activity: MainActivity) {
        val gpsPanel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        val settingsSection = gpsPanel.findViewWithTag<View>("settings_personalization_installed") as? LinearLayout ?: return
        if (settingsSection.findViewWithTag<View>(TAG_FONT_CONTROL) != null) return

        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val wrapper = LinearLayout(activity).apply {
            tag = TAG_FONT_CONTROL
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        val heading = TextView(activity).apply {
            text = "TAILLE DU TEXTE"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(activity, 6))
        }
        val button = Button(activity).apply {
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
            setBackgroundResource(R.drawable.hp_panel)
        }

        val scales = floatArrayOf(0.88f, 1.0f, 1.12f, 1.25f)
        val labels = arrayOf("Petite", "Normale", "Grande", "Très grande")
        fun currentIndex(): Int {
            val value = prefs.getFloat(PREF_FONT_SCALE, 1.0f)
            return scales.indices.minByOrNull { kotlin.math.abs(scales[it] - value) } ?: 1
        }
        fun updateButton() { button.text = "TAILLE DE POLICE : ${labels[currentIndex()].uppercase()}" }
        updateButton()

        button.setOnClickListener {
            val selected = currentIndex()
            AlertDialog.Builder(activity)
                .setTitle("Taille du texte")
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    prefs.edit().putFloat(PREF_FONT_SCALE, scales[which]).apply()
                    updateButton()
                    normalizeTypography(activity.window.decorView, activity)
                    dialog.dismiss()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        wrapper.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrapper.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46)).apply {
            topMargin = dp(activity, 2)
            bottomMargin = dp(activity, 2)
        })
        settingsSection.addView(wrapper, findWidgetSectionIndex(settingsSection))
    }

    private fun tidySettingsSection(activity: MainActivity) {
        val gpsPanel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        val section = gpsPanel.findViewWithTag<View>("settings_personalization_installed") as? LinearLayout ?: return
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            when (child) {
                is Button -> {
                    child.minHeight = 0
                    child.minimumHeight = 0
                    child.gravity = Gravity.CENTER
                    child.setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
                    child.layoutParams = (child.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46))).apply {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = dp(activity, 46)
                        topMargin = dp(activity, 4)
                        bottomMargin = dp(activity, 4)
                    }
                }
                is Switch -> {
                    child.gravity = Gravity.CENTER_VERTICAL
                    child.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 8), dp(activity, 7))
                    child.minHeight = dp(activity, 52)
                }
                is TextView -> {
                    val t = child.text?.toString().orEmpty()
                    if (t == "APPARENCE DE L'APPLICATION" || t == "PERSONNALISER LE WIDGET" || t == "NOTICE" || t == "SAUVEGARDE GOOGLE DRIVE" || t == "MISES À JOUR") {
                        child.typeface = Typeface.DEFAULT_BOLD
                        child.setPadding(0, dp(activity, 20), 0, dp(activity, 8))
                    }
                }
            }
        }
    }

    private fun findWidgetSectionIndex(settingsSection: LinearLayout): Int {
        for (i in 0 until settingsSection.childCount) {
            val text = (settingsSection.getChildAt(i) as? TextView)?.text?.toString()
            if (text == "PERSONNALISER LE WIDGET") return i
        }
        return settingsSection.childCount
    }

    private fun normalizeTypography(view: View, activity: MainActivity) {
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val fontScale = prefs.getFloat(PREF_FONT_SCALE, 1.0f).coerceIn(0.85f, 1.30f)
        if (view is TextView && view !is TextClock) {
            val baseSp = when (view.id) {
                R.id.tabToday, R.id.tabHistory, R.id.tabAnalytics, R.id.tabSalary, R.id.tabSettings -> 12f
                R.id.statusCard, R.id.contentTitle -> 16f
                R.id.historyText -> 14f
                else -> if (view is Button) 14f else {
                    val currentSp = view.textSize / view.resources.displayMetrics.scaledDensity / fontScale
                    when {
                        currentSp <= 12.5f -> 12f
                        currentSp <= 15.5f -> 14f
                        else -> 16f
                    }
                }
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * fontScale)
            view.setIncludeFontPadding(false)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) normalizeTypography(view.getChildAt(i), activity)
    }

    private fun applyTransparency(activity: MainActivity) {
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val transparency = prefs.getInt(PREF_TRANSPARENCY, 0).coerceIn(0, 100)
        val alpha = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        applyTransparencyToView(root, alpha)
    }

    private fun applyTransparencyToView(view: View, alpha: Int) {
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
        val isProtectedImageButton = idName == "entryButton" || idName == "pauseButton" || idName == "exitButton" || idName == "settingsButton"
        val isPanel = idName == "statusCard" || idName == "pointageButtons" || idName == "contentPanel" || idName == "gpsSettingsPanel" || idName == "analyticsPdfPanel" || idName.contains("Panel", ignoreCase = true) || idName.contains("Card", ignoreCase = true)
        val isStandardButton = view is Button && !isProtectedImageButton
        val isNavigationBar = view is LinearLayout && (0 until view.childCount).any { view.getChildAt(it).id == R.id.tabToday }
        if ((isPanel || isStandardButton || isNavigationBar) && view.background != null) view.background.mutate().alpha = alpha
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTransparencyToView(view.getChildAt(i), alpha)
    }

    private fun installAppearanceListener(activity: MainActivity) {
        if (appearanceListeners.containsKey(activity)) return
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mode" || key == AppThemeCatalog.KEY_THEME) {
                activity.window.decorView.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        AppearanceManager.apply(activity)
                        applyThemeColors(activity)
                        syncTabs(activity)
                        syncTodayLuxuryText(activity)
                    }
                }
            } else if (key == "app_bg" || key == "custom_bg" || key == "custom_image_bg") {
                activity.window.decorView.post {
                    AppearanceManager.apply(activity)
                    applyThemeColors(activity)
                    activity.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
                    syncTabs(activity)
                    syncTodayLuxuryText(activity)
                    normalizeTypography(activity.window.decorView, activity)
                    applyTransparency(activity)
                    tidySettingsSection(activity)
                }
            } else if (key == PREF_FONT_SCALE) {
                activity.window.decorView.post { normalizeTypography(activity.window.decorView, activity) }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        appearanceListeners[activity] = listener
    }

    private fun syncTodayLuxuryText(activity: MainActivity) {
        val theme = AppThemeCatalog.current(activity)
        val dark = AppThemeCatalog.useDarkPalette(activity)
        val mainText = if (dark) theme.darkText else theme.lightText
        val accentText = if (dark) theme.accentLight else theme.accent
        activity.findViewById<TextView>(R.id.statusCard)?.setTextColor(mainText)
        val pointagePanel = activity.findViewById<ViewGroup>(R.id.pointageButtons) ?: return
        recolorTextChildren(pointagePanel, accentText)
    }

    private fun recolorTextChildren(view: View, color: Int) {
        if (view is TextView && view !is LightReactiveJewelButton) view.setTextColor(color)
        if (view is ViewGroup) for (i in 0 until view.childCount) recolorTextChildren(view.getChildAt(i), color)
    }

    private fun syncTabs(activity: MainActivity) {
        val theme = AppThemeCatalog.current(activity)
        val dark = AppThemeCatalog.useDarkPalette(activity)
        val activeColor = if (dark) theme.accentLight else theme.accent
        val inactiveColor = if (dark) theme.darkHint else theme.lightHint
        val navColor = if (dark) theme.darkPanel else theme.lightPanel

        val today = activity.findViewById<TextView>(R.id.tabToday)
        val history = activity.findViewById<TextView>(R.id.tabHistory)
        val analytics = activity.findViewById<TextView>(R.id.tabAnalytics)
        val salary = activity.findViewById<TextView>(R.id.tabSalary)
        val settings = activity.findViewById<TextView>(R.id.tabSettings)

        (today?.parent as? LinearLayout)?.backgroundTintList = ColorStateList.valueOf(navColor)

        val settingsVisible = activity.findViewById<View>(R.id.gpsSettingsPanel)?.visibility == View.VISIBLE
        val analyticsVisible = activity.findViewById<View>(R.id.analyticsPdfPanel)?.visibility == View.VISIBLE
        val todayVisible = activity.findViewById<View>(R.id.pointageButtons)?.visibility == View.VISIBLE

        val active = when { settingsVisible -> settings; analyticsVisible -> analytics; todayVisible -> today; else -> history }
        listOf(today, history, analytics, salary, settings).forEach { tab -> tab?.setTextColor(if (tab === active) activeColor else inactiveColor) }
    }

    private fun dp(activity: MainActivity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
