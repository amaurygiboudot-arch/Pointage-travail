package com.amaury.pointage

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconSwitcher {

    private const val PREFS = "icon_switcher"
    private const val KEY_PENDING = "pending_working"

    fun setWorking(context: Context, working: Boolean) {
        if (context is Activity) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, working)
                .apply()
            return
        }

        applyState(context, working)
    }

    fun applyPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PENDING)) return

        val working = prefs.getBoolean(KEY_PENDING, false)
        prefs.edit().remove(KEY_PENDING).apply()
        applyState(context.applicationContext, working)
    }

    private fun applyState(context: Context, working: Boolean) {
        val pm = context.packageManager

        val red = ComponentName(
            context,
            "com.amaury.pointage.IconRed"
        )

        val green = ComponentName(
            context,
            "com.amaury.pointage.IconGreen"
        )

        pm.setComponentEnabledSetting(
            green,
            if (working)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        pm.setComponentEnabledSetting(
            red,
            if (working)
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    fun sync(context: Context) {
        setWorking(context, PointageStore.hasOpen(context))
    }
}
