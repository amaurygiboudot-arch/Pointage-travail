package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Demande une seule fois la localisation au premier lancement de HP Travail.
 * Android 12+ attend que les permissions approximative et précise soient demandées
 * ensemble pour laisser l'utilisateur choisir correctement son niveau d'accès.
 * La localisation en arrière-plan reste volontairement séparée.
 */
class ForegroundLocationInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(LocationPermissionLifecycle())
        return true
    }

    private class LocationPermissionLifecycle : Application.ActivityLifecycleCallbacks {
        private var requestPosted = false
        private var refreshPosted = false

        override fun onActivityResumed(activity: Activity) {
            if (activity !is MainActivity || activity.isFinishing || activity.isDestroyed) return

            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fineGranted = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseGranted = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (fineGranted || coarseGranted) {
                if (prefs.getBoolean(KEY_REFRESH_AFTER_GRANT, false) && !refreshPosted) {
                    refreshPosted = true
                    prefs.edit().putBoolean(KEY_REFRESH_AFTER_GRANT, false).apply()
                    activity.window.decorView.post {
                        if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
                    }
                }
                return
            }

            if (prefs.getBoolean(KEY_ASKED, false) || requestPosted) return

            requestPosted = true
            prefs.edit()
                .putBoolean(KEY_ASKED, true)
                .putBoolean(KEY_REFRESH_AFTER_GRANT, true)
                .apply()

            activity.window.decorView.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ),
                        REQUEST_FOREGROUND_LOCATION
                    )
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val PREFS = "location_onboarding"
        private const val KEY_ASKED = "foreground_location_asked"
        private const val KEY_REFRESH_AFTER_GRANT = "refresh_after_location_grant"
        private const val REQUEST_FOREGROUND_LOCATION = 3010
    }
}
