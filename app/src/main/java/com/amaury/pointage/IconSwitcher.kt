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
        val target = ComponentName(context.packageName, enabledClass)

        // Toujours activer la nouvelle icône AVANT de désactiver l'ancienne :
        // le tiroir Android ne se retrouve ainsi jamais sans composant launcher.
        runCatching {
            if (pm.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    target,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        listOf(RED, GREEN, ORANGE)
            .filter { it != enabledClass }
            .forEach { className ->
                val component = ComponentName(context.packageName, className)
                runCatching {
                    if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
            }
    }
}
