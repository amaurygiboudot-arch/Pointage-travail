package com.amaury.pointage

import android.content.Context
import android.location.LocationManager
import android.os.Build

/** État réel du service de localisation du téléphone, distinct des permissions AGKGMG. */
object DeviceLocationAvailability {
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
    }
}
