package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Branche automatiquement les trois boutons principaux sur le moteur Diamant 3D
 * lorsque le thème diamant est actif.
 *
 * Cette initialisation avait été neutralisée : le manifeste chargeait bien ce
 * provider, mais il ne faisait plus rien. Les boutons visibles restaient donc
 * les anciens boutons et ne recevaient jamais l'inclinaison du téléphone.
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
        if (AppThemeCatalog.current(activity).id != "diamond_crystal") return
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
        // Réessaie au retour au premier plan : certains écrans construisent leurs
        // boutons après onActivityCreated(). L'installateur est idempotent.
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