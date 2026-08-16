package com.amaury.pointage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconSwitcher {

    fun setWorking(context: Context, working: Boolean) {
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
