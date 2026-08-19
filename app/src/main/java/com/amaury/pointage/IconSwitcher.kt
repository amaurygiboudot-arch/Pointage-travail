package com.amaury.pointage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconSwitcher {
    private const val RED = "com.amaury.pointage.IconRed"
    private const val GREEN = "com.amaury.pointage.IconGreen"
    private const val ORANGE = "com.amaury.pointage.IconOrange"

    fun setWorking(context: Context, working: Boolean) = sync(context)
    fun applyPending(context: Context) = sync(context)

    fun sync(context: Context) {
        val target = when {
            PointageStore.isPaused(context) -> ORANGE
            PointageStore.hasOpen(context) -> GREEN
            else -> RED
        }
        setOnly(context, target)
    }

    private fun setOnly(context: Context, enabledClass: String) {
        val pm = context.packageManager
        listOf(RED, GREEN, ORANGE).forEach { className ->
            val component = ComponentName(context.packageName, className)
            val desired = if (className == enabledClass) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                if (pm.getComponentEnabledSetting(component) != desired) {
                    pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}
