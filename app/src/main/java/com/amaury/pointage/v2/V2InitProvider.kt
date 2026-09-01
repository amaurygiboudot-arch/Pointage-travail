package com.amaury.pointage.v2

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.IconSwitcher
import com.amaury.pointage.PauseScheduleManager
import com.amaury.pointage.V2AppLock

/** Initialisation V2 indépendante des écrans : aucune donnée utilisateur n'est effacée. */
class V2InitProvider : ContentProvider() {
    private var runtimePrefs: SharedPreferences? = null
    private var iconStateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        if (HoraTrackV2.ENABLED) {
            V2RuntimeStore.bind(app)
            V2TestDataPolicy.ensurePreservation(app)
            V2ProfileStore.bind(app)
            V2BackupManager.restoreFreshInstallIfConfiguredAsync(app)
            V2MigrationManager.ensureMigrated(app)
            V2LegalSourceUpdater.checkIfDue(app)
            V2AppLock.install(app)
            V2AutoBackupCoordinator.install(app)
            installIconStateSync(app)
        }
        return true
    }

    private fun installIconStateSync(app: Application) {
        val prefs = app.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            syncIconNowAndAfterStateCommit(app)
        }
        runtimePrefs = prefs
        iconStateListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) = syncIconNowAndAfterStateCommit(app)
            override fun onActivityStopped(activity: Activity) = syncIconNowAndAfterStateCommit(app)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                // Une alarme de fin peut être retardée par Android/HyperOS.
                // Au retour dans l'app, on réconcilie immédiatement l'état avec le créneau prévu.
                PauseScheduleManager.applyCurrentWindow(app)
                syncIconNowAndAfterStateCommit(app)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        PauseScheduleManager.applyCurrentWindow(app)
        syncIconNowAndAfterStateCommit(app)
    }

    private fun syncIconNowAndAfterStateCommit(app: Application) {
        IconSwitcher.sync(app)
        mainHandler.removeCallbacksAndMessages(ICON_SYNC_TOKEN)
        mainHandler.postAtTime(
            { IconSwitcher.sync(app) },
            ICON_SYNC_TOKEN,
            android.os.SystemClock.uptimeMillis() + ICON_SYNC_DELAY_MS
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val ICON_SYNC_DELAY_MS = 250L
        val ICON_SYNC_TOKEN = Any()
    }
}
