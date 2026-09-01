package com.amaury.pointage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Point d'entrée unique de l'environnement des icônes launcher HoraTrack.
 *
 * Pour ajouter ou retirer une icône à l'avenir, modifier uniquement [icons] puis
 * déclarer/retirer l'alias Android correspondant dans le manifeste avec sa ressource.
 * Toute l'activation/désactivation passe ensuite automatiquement par ce registre.
 */
object IconSwitcher {

    private const val TAG = "HoraTrackIcon"
    private const val DIAG_PREFS = "icon_switch_diagnostics"
    private const val KEY_SUCCESS = "last_success"
    private const val KEY_TARGET = "last_target"
    private const val KEY_DETAILS = "last_details"
    private const val KEY_TIMESTAMP = "last_timestamp"

    private enum class IconState {
        DEFAULT,
        WORKING,
        PAUSED
    }

    private data class LauncherIcon(
        val state: IconState,
        val aliasClassName: String
    )

    private val icons = listOf(
        LauncherIcon(IconState.DEFAULT, "com.amaury.pointage.IconRed"),
        LauncherIcon(IconState.WORKING, "com.amaury.pointage.IconGreen"),
        LauncherIcon(IconState.PAUSED, "com.amaury.pointage.IconOrange")
    )

    private val fallbackIcon: LauncherIcon
        get() = icons.firstOrNull { it.state == IconState.DEFAULT }
            ?: error("HoraTrack launcher icon registry requires a DEFAULT icon")

    fun setWorking(context: Context, working: Boolean) = sync(context)
    fun applyPending(context: Context) = sync(context)

    fun sync(context: Context) {
        val state = when {
            PointageStore.isPaused(context) -> IconState.PAUSED
            PointageStore.hasOpen(context) -> IconState.WORKING
            else -> IconState.DEFAULT
        }

        val target = icons.firstOrNull { it.state == state } ?: fallbackIcon
        setOnly(context, target)
    }

    private fun setOnly(context: Context, enabledIcon: LauncherIcon) {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val target = ComponentName(appContext.packageName, enabledIcon.aliasClassName)
        val failures = mutableListOf<String>()

        // 1) Activer explicitement la nouvelle icône.
        try {
            pm.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (t: Throwable) {
            failures += "activation ${enabledIcon.aliasClassName}: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        }

        // 2) Relire immédiatement l'état réel retourné par Android.
        val targetState = runCatching { pm.getComponentEnabledSetting(target) }
            .getOrElse {
                failures += "lecture ${enabledIcon.aliasClassName}: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }

        if (targetState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            failures += "alias cible non activé (état=$targetState)"
        }

        // 3) On ne désactive les anciennes icônes que si Android confirme la cible active.
        if (failures.isEmpty()) {
            icons.asSequence()
                .filter { it.aliasClassName != enabledIcon.aliasClassName }
                .forEach { icon ->
                    val component = ComponentName(appContext.packageName, icon.aliasClassName)
                    try {
                        pm.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    } catch (t: Throwable) {
                        failures += "désactivation ${icon.aliasClassName}: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                        return@forEach
                    }

                    val actualState = runCatching { pm.getComponentEnabledSetting(component) }
                        .getOrElse {
                            failures += "lecture ${icon.aliasClassName}: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        }

                    if (actualState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        failures += "alias ${icon.aliasClassName} encore actif (état=$actualState)"
                    }
                }
        }

        val success = failures.isEmpty()
        val details = if (success) {
            "OK target=${enabledIcon.aliasClassName} state=$targetState"
        } else {
            failures.joinToString(" | ")
        }

        appContext.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SUCCESS, success)
            .putString(KEY_TARGET, enabledIcon.aliasClassName)
            .putString(KEY_DETAILS, details)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()

        if (success) {
            Log.i(TAG, details)
        } else {
            Log.e(TAG, details)
        }
    }
}
