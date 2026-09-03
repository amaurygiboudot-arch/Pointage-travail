package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/**
 * Déclenche une sauvegarde V2 après toute modification fonctionnelle importante.
 * Le debounce évite d'écrire plusieurs fois pendant la saisie d'un même formulaire.
 */
object V2AutoBackupCoordinator {
    private val watchedFiles = listOf(
        "horatrack_v2_test_runtime",
        "horatrack_v2_integration",
        "horatrack_v2_rights",
        "horatrack_v2_payslips",
        "horatrack_v2_company_pause",
        "horatrack_v2_gps_state",
        "v2_app_lock",
        "salary_settings",
        "gps_settings",
        "appearance_settings",
        "widget_style",
        "place_names",
        "smart_setup",
        "welcome_preview"
    )

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()
    private var appContext: Context? = null
    private var pending: Runnable? = null

    @Synchronized
    fun install(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        watchedFiles.forEach { name ->
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule() }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            listeners += prefs to listener
        }
    }

    @Synchronized
    private fun schedule() {
        val app = appContext ?: return
        pending?.let(handler::removeCallbacks)
        val task = Runnable { V2BackupManager.backupIfConfiguredAsync(app) }
        pending = task
        handler.postDelayed(task, 2500L)
    }
}
