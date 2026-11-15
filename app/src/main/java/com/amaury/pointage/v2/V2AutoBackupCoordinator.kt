package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.SalaryCompanyStore
import com.google.firebase.auth.FirebaseAuth

/**
 * Déclenche une sauvegarde V2 après toute modification fonctionnelle importante.
 * Le debounce évite d'écrire plusieurs fois pendant la saisie d'un même formulaire.
 */
object V2AutoBackupCoordinator {
    private const val SALARY_COMPANIES_PREFS = "salary_companies_v2"
    private const val SALARY_COMPANY_PREFIX = "salary_company_"

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()
    private val registeredNames = linkedSetOf<String>()
    private var appContext: Context? = null
    private var pending: Runnable? = null
    private var registeredLease: SalaryAccountScope.Lease? = null
    private val authListener = FirebaseAuth.AuthStateListener { reloadAccountListeners() }

    @Synchronized
    private fun reloadAccountListeners() {
        val app = appContext ?: return
        val lease = SalaryAccountScope.capture()
        if (lease == registeredLease && listeners.isNotEmpty()) return
        pending?.let(handler::removeCallbacks)
        pending = null
        listeners.forEach { (prefs, listener) -> prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        listeners.clear()
        registeredNames.clear()
        registeredLease = lease
        if (lease.scope == null) return
        V2BackupManager.transferablePreferenceFiles(SalaryAccountScope.bindContext(app, lease)).forEach { register(app, it) }
        registerDynamicSalaryFiles(app)
    }

    @Synchronized
    fun install(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        reloadAccountListeners()
        SalaryAccountScope.addListener { reloadAccountListeners() }
        runCatching { FirebaseAuth.getInstance().addAuthStateListener(authListener) }
    }

    @Synchronized
    private fun register(app: Context, name: String) {
        if (!registeredNames.add(name)) return
        val lease = registeredLease ?: return
        val prefs = V2BackupManager.preferences(SalaryAccountScope.bindContext(app, lease), name)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (!SalaryAccountScope.isCurrent(lease)) return@OnSharedPreferenceChangeListener
            if (name == SALARY_COMPANIES_PREFS) registerDynamicSalaryFiles(app)
            schedule()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        listeners += prefs to listener
    }

    @Synchronized
    private fun registerDynamicSalaryFiles(app: Context) {
        val lease = registeredLease ?: return
        val scope = lease.scope ?: return
        val bound = SalaryAccountScope.bindContext(app, lease)
        val stored = SalaryCompanyStore.readConfirmed(bound)
        val existingOnDisk = SalaryPreferenceRegistry.transferableNames(bound, scope)
            .filter { it.startsWith(SALARY_COMPANY_PREFIX) }
        dynamicSalaryFileNames(stored, existingOnDisk).forEach { register(app, it) }
    }

    internal fun dynamicSalaryFileNames(
        stored: SalaryCompanyStore.ReadResult,
        existingOnDisk: List<String>
    ): List<String> {
        val expectedFromCompanies = if (stored.reliable) {
            stored.companies.map { company ->
                SALARY_COMPANY_PREFIX + company.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            }
        } else {
            emptyList()
        }
        return (expectedFromCompanies + existingOnDisk.filter { it.startsWith(SALARY_COMPANY_PREFIX) })
            .distinct()
    }

    @Synchronized
    private fun schedule() {
        val app = appContext ?: return
        pending?.let(handler::removeCallbacks)
        val lease = SalaryAccountScope.capture()
        if (lease.scope == null) return
        val task = Runnable {
            if (SalaryAccountScope.isCurrent(lease)) V2BackupManager.backupIfConfiguredAsync(SalaryAccountScope.bindContext(app, lease))
        }
        pending = task
        handler.postDelayed(task, 2500L)
    }
}
