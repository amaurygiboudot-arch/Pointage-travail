package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.SalaryCompanyStore
import java.io.File

/**
 * Déclenche une sauvegarde V2 après toute modification fonctionnelle importante.
 * Le debounce évite d'écrire plusieurs fois pendant la saisie d'un même formulaire.
 */
object V2AutoBackupCoordinator {
    private const val SALARY_COMPANIES_PREFS = "salary_companies_v2"
    private const val SALARY_COMPANY_PREFIX = "salary_company_"

    private val watchedFiles = listOf(
        "horatrack_v2_test_runtime",
        "horatrack_v2_integration",
        "horatrack_v2_rights",
        "horatrack_v2_payslips",
        "horatrack_v2_company_pause",
        "horatrack_v2_gps_state",
        "v2_app_lock",
        SALARY_COMPANIES_PREFS,
        "salary_settings",
        "gps_settings",
        "shift_profiles",
        "appearance_settings",
        "widget_style",
        "place_names",
        "smart_setup",
        "welcome_preview"
    )

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()
    private val registeredNames = linkedSetOf<String>()
    private var appContext: Context? = null
    private var pending: Runnable? = null

    @Synchronized
    fun install(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        watchedFiles.forEach { name -> register(app, name) }
        registerDynamicSalaryFiles(app)
    }

    @Synchronized
    private fun register(app: Context, name: String) {
        if (!registeredNames.add(name)) return
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (name == SALARY_COMPANIES_PREFS) registerDynamicSalaryFiles(app)
            schedule()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        listeners += prefs to listener
    }

    @Synchronized
    private fun registerDynamicSalaryFiles(app: Context) {
        val expectedFromCompanies = SalaryCompanyStore.list(app).map { company ->
            SALARY_COMPANY_PREFIX + company.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        }
        val existingOnDisk = sharedPreferenceFileNames(app).filter { it.startsWith(SALARY_COMPANY_PREFIX) }
        (expectedFromCompanies + existingOnDisk).distinct().forEach { register(app, it) }
    }

    private fun sharedPreferenceFileNames(context: Context): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
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
