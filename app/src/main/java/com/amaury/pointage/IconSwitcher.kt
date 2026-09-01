package com.amaury.pointage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Point d'entrée unique de l'environnement des icônes launcher HoraTrack.
 *
 * Pour ajouter ou retirer une icône à l'avenir, modifier uniquement [icons] puis
 * déclarer/retirer l'alias Android correspondant dans le manifeste avec sa ressource.
 * Toute l'activation/désactivation passe ensuite automatiquement par ce registre.
 */
object IconSwitcher {

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
        val pm = context.packageManager
        val target = ComponentName(context.packageName, enabledIcon.aliasClassName)

        // Toujours activer la nouvelle icône AVANT de désactiver les autres :
        // le launcher Android ne se retrouve ainsi jamais sans composant actif.
        runCatching {
            if (pm.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    target,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        icons.asSequence()
            .filter { it.aliasClassName != enabledIcon.aliasClassName }
            .forEach { icon ->
                val component = ComponentName(context.packageName, icon.aliasClassName)
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
