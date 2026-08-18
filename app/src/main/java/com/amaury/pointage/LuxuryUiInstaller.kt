package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import java.util.WeakHashMap

object LuxuryUiInstaller {
    private const val TAG_TRANSPARENCY = "hp_ui_transparency_control"
    private const val PREF_TRANSPARENCY = "ui_transparency"
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
            normalizeTypography(activity.window.decorView)
            applyTransparency(activity)
        }
        digital.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (digital.visibility == View.VISIBLE) digital.visibility = View.GONE
        }

        activity.findViewById<TextView>(R.id.logoText)?.apply {
            text = "♛\nH  P\nT R A V A I L"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D6A84B"))
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
            setTextColor(Color.parseColor("#D6A84B"))
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

        AppearanceManager.apply(activity)
        activity.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
        syncTabs(activity)
        syncTodayLuxuryText(activity)
        normalizeTypography(activity.window.decorView)
        applyTransparency(activity)

        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && !activity.isFinishing && !activity.isDestroyed) {
                decor.post {
                    syncTabs(activity)
                    syncTodayLuxuryText(activity)
                    normalizeTypography(decor)
                    applyTransparency(activity)
                }
            }
        }

        decor.post {
            AppearanceManager.apply(activity)
            syncTabs(activity)
            syncTodayLuxuryText(activity)
            normalizeTypography(decor)
            applyTransparency(activity)
        }
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
        val label = TextView(activity).apply {
            textSize = 14f
        }
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

        wrapper.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        wrapper.addView(seekBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        var insertIndex = settingsSection.childCount
        for (i in 0 until settingsSection.childCount) {
            val text = (settingsSection.getChildAt(i) as? TextView)?.text?.toString()
            if (text == "PERSONNALISER LE WIDGET") {
                insertIndex = i
                break
            }
        }
        settingsSection.addView(wrapper, insertIndex)
    }

    private fun normalizeTypography(view: View) {
        if (view is TextView && view !is TextClock) {
            val targetSp = when (view.id) {
                R.id.tabToday, R.id.tabHistory, R.id.tabAnalytics, R.id.tabSalary, R.id.tabSettings -> 12f
                R.id.statusCard, R.id.contentTitle -> 16f
                R.id.historyText -> 14f
                else -> {
                    if (view is Button) {
                        14f
                    } else {
                        val currentSp = view.textSize / view.resources.displayMetrics.scaledDensity
                        when {
                            currentSp <= 12.5f -> 12f
                            currentSp <= 15.5f -> 14f
                            else -> 16f
                        }
                    }
                }
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSp)
            view.setIncludeFontPadding(false)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                normalizeTypography(view.getChildAt(i))
            }
        }
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
        val isProtectedImageButton = idName == "entryButton" ||
            idName == "pauseButton" ||
            idName == "exitButton" ||
            idName == "settingsButton"
        val isPanel = idName == "statusCard" ||
            idName == "pointageButtons" ||
            idName == "contentPanel" ||
            idName == "gpsSettingsPanel" ||
            idName == "analyticsPdfPanel" ||
            idName.contains("Panel", ignoreCase = true) ||
            idName.contains("Card", ignoreCase = true)
        val isStandardButton = view is Button && !isProtectedImageButton
        val isNavigationBar = view is LinearLayout && (0 until view.childCount).any {
            view.getChildAt(it).id == R.id.tabToday
        }

        if ((isPanel || isStandardButton || isNavigationBar) && view.background != null) {
            view.background.mutate().alpha = alpha
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTransparencyToView(view.getChildAt(i), alpha)
            }
        }
    }

    private fun installAppearanceListener(activity: MainActivity) {
        if (appearanceListeners.containsKey(activity)) return
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mode") {
                activity.window.decorView.post {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
                }
            } else if (key == "app_bg" || key == "custom_bg" || key == "custom_image_bg") {
                activity.window.decorView.post {
                    AppearanceManager.apply(activity)
                    activity.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
                    syncTabs(activity)
                    syncTodayLuxuryText(activity)
                    normalizeTypography(activity.window.decorView)
                    applyTransparency(activity)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        appearanceListeners[activity] = listener
    }

    private fun syncTodayLuxuryText(activity: MainActivity) {
        activity.findViewById<TextView>(R.id.statusCard)?.setTextColor(Color.parseColor("#F4EFE3"))

        val pointagePanel = activity.findViewById<ViewGroup>(R.id.pointageButtons) ?: return
        recolorTextChildren(pointagePanel, Color.parseColor("#F3D58A"))
    }

    private fun recolorTextChildren(view: View, color: Int) {
        if (view is TextView) view.setTextColor(color)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                recolorTextChildren(view.getChildAt(i), color)
            }
        }
    }

    private fun syncTabs(activity: MainActivity) {
        val prefs = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }

        val activeColor = Color.parseColor(if (dark) "#F3D58A" else "#795600")
        val inactiveColor = Color.parseColor(if (dark) "#CFC7B8" else "#555555")
        val navColor = Color.parseColor(if (dark) "#181818" else "#FFFFFF")

        val today = activity.findViewById<TextView>(R.id.tabToday)
        val history = activity.findViewById<TextView>(R.id.tabHistory)
        val analytics = activity.findViewById<TextView>(R.id.tabAnalytics)
        val salary = activity.findViewById<TextView>(R.id.tabSalary)
        val settings = activity.findViewById<TextView>(R.id.tabSettings)

        (today?.parent as? LinearLayout)?.backgroundTintList = ColorStateList.valueOf(navColor)

        val settingsVisible = activity.findViewById<View>(R.id.gpsSettingsPanel)?.visibility == View.VISIBLE
        val analyticsVisible = activity.findViewById<View>(R.id.analyticsPdfPanel)?.visibility == View.VISIBLE
        val todayVisible = activity.findViewById<View>(R.id.pointageButtons)?.visibility == View.VISIBLE

        val active = when {
            settingsVisible -> settings
            analyticsVisible -> analytics
            todayVisible -> today
            else -> history
        }

        listOf(today, history, analytics, salary, settings).forEach { tab ->
            tab?.setTextColor(if (tab === active) activeColor else inactiveColor)
        }
    }

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
