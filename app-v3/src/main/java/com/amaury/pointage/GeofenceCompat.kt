package com.amaury.pointage

import android.content.Context

fun GeofenceManager.unregisterAll(context: Context) {
    remove(context)
}
