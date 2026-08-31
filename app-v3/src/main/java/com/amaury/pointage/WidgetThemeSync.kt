package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences

object WidgetThemeSync {
    private var installed = false
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(context: Context) {
        if (installed) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == "mode" ||
                key == "app_bg" ||
                key == "custom_bg" ||
                key == "custom_image_bg" ||
                key == AppThemeCatalog.KEY_THEME
            ) {
                PointageWidgetProvider.updateAll(appContext)
                QuickActionsWidgetProvider.updateAll(appContext)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        listener = l
        installed = true
        PointageWidgetProvider.updateAll(appContext)
        QuickActionsWidgetProvider.updateAll(appContext)
    }
}
