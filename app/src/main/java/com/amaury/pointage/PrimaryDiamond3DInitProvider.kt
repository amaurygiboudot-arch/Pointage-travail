package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Branche automatiquement les trois boutons principaux de HoraTrack sur le
 * moteur OpenGL déjà intégré à :app.
 *
 * Le choix du niveau de rendu est fait par DiamondDeviceProfile : les appareils
 * modestes restent en ECO/BALANCED et les plus puissants utilisent HIGH/ULTRA.
 * Le rendu 3D n'est donc plus conditionné par un thème visuel caché/réservé.
 */
class PrimaryDiamond3DInitProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private var application: Application? = null

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        application = app
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    private fun installIfNeeded(activity: Activity) {
        if (activity !is MainActivity) return
        activity.window.decorView.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                PrimaryDiamond3DInstaller.install(activity.window.decorView)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        installIfNeeded(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        // Certains boutons sont reconstruits après onActivityCreated().
        // L'installateur est idempotent : on peut donc raccorder à nouveau sans doublon.
        installIfNeeded(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}