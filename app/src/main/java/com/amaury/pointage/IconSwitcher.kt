package com.amaury.pointage

import android.content.Context

/**
 * L'application utilise désormais une seule icône launcher fixe.
 * Cette couche reste présente uniquement pour compatibilité avec les anciens appels.
 */
object IconSwitcher {
    fun setWorking(context: Context, working: Boolean) = Unit
    fun applyPending(context: Context) = Unit
    fun sync(context: Context) = Unit
}
