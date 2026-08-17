package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.WeakHashMap

object LuxuryUiInstaller {
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
        }
        digital.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (digital.visibility == View.VISIBLE) digital.visibility = View.GONE
        }

        activity.findViewById<TextView>(R.id.logoText)?.apply {
            text = "♛\nH  P\nT R A V A I L"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D6A84B"))
            typeface = Typeface.create("serif", Typeface.BOLD)
            textSize = 20f
            letterSpacing = 0.08f
        }

        activity.findViewById<TextView>(R.id.statusCard)?.apply {
            setTextColor(Color.parseColor("#F4EFE3"))
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = 17f
            letterSpacing = 0.04f
        }

        activity.findViewById<TextView>(R.id.contentTitle)?.apply {
            setTextColor(Color.parseColor("#D6A84B"))
            typeface = Typeface.create("serif", Typeface.BOLD)
            letterSpacing = 0.08f
        }

        activity.findViewById<TextView>(R.id.historyText)?.apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            letterSpacing = 0.03f
        }

        installAppearanceListener(activity)

        AppearanceManager.apply(activity)
        activity.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
        syncTabs(activity)

        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && !activity.isFinishing && !activity.isDestroyed) {
                decor.post { syncTabs(activity) }
            }
        }

        decor.post {
            AppearanceManager.apply(activity)
            syncTabs(activity)
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
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        appearanceListeners[activity] = listener
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
}
