package com.amaury.pointage

import com.amaury.pointage.billing.BillingPdfGate
import com.amaury.pointage.billing.BillingOffers

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2LegacyPolicy
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.ui.HistoryTextFormatterV2
import com.amaury.pointage.v2.ui.HomeTabVisibilityPolicyV2
import com.amaury.pointage.v2.engine.CelestialGlobeModeV2
import com.amaury.pointage.v2.model.SessionStatusV2
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    private var settingsBackCallback: OnBackInvokedCallback? = null

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (SettingsCompactMenuV2.handleBack(this)) return
        super.onBackPressed()
    }

    internal fun updateSettingsBackCallback(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (enabled && settingsBackCallback == null) {
            val callback = OnBackInvokedCallback { SettingsCompactMenuV2.showMenu(this) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
            settingsBackCallback = callback
        } else if (!enabled) {
            settingsBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            settingsBackCallback = null
        }
    }

    companion object {
        private const val REQUEST_CREATE_MONTHLY_PDF = 2002
        private const val REQUEST_FINE_LOCATION = 3001
        private const val REQUEST_BACKGROUND_LOCATION = 3002
        private const val REQUEST_POINTAGE_NOTIFICATIONS = 3003
        private const val NAVIGATION_PREFS = "navigation_state"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_REPORT_MONTH_MS = "report_month_ms"
    }

    private lateinit var statusCard: TextView
    private lateinit var historyText: TextView
    private lateinit var contentTitle: TextView
    private lateinit var clockDigital: TextClock
    private lateinit var contentPanel: LinearLayout
    private lateinit var navigationTabs: LinearLayout
    private lateinit var celestialHomePanel: View
    private lateinit var sunIndicator: SunIndicatorView
    private lateinit var pointageButtons: LinearLayout
    private lateinit var gpsSettingsPanel: LinearLayout
    private lateinit var celestialGlobeModeGroup: RadioGroup
    private lateinit var analyticsPdfPanel: LinearLayout
    private lateinit var workplaceAddress: EditText
    private lateinit var geofenceRadius: EditText
    private lateinit var autoGpsSwitch: Switch
    private lateinit var gpsStatusText: TextView
    private lateinit var selectedReportMonthText: TextView
    private lateinit var tabHome: TextView
    private lateinit var tabToday: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabAnalytics: TextView
    private lateinit var tabSalary: SalaryTabTextView
    private lateinit var tabSettings: TextView

    private var activeTab = "home"
    private var updatingGpsSwitch = false
    private var updatingCelestialGlobeMode = false
    private var gpsSaveRequestId = 0
    private var gpsRegistrationConfirmed = false
    private var gpsRegistrationError: String? = null
    private val homeTabsHandler = Handler(Looper.getMainLooper())
    private val hideHomeTabsRunnable = Runnable {
        if (HomeTabVisibilityPolicyV2.shouldHide(activeTab == "home", HomeTabVisibilityPolicyV2.INACTIVITY_TIMEOUT_MS)) {
            navigationTabs.animate().cancel()
            navigationTabs.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    if (activeTab == "home") {
                        // Keep the measured slot: GONE reflows the sky panel after the fade.
                        navigationTabs.visibility = View.INVISIBLE
                        navigationTabs.alpha = 1f
                    }
                }
                .start()
        }
    }

    private val selectedReportMonth = Calendar.getInstance(Locale.FRANCE).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private var pendingPdfYear = selectedReportMonth.get(Calendar.YEAR)
    private var pendingPdfMonth = selectedReportMonth.get(Calendar.MONTH)

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val fullDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    private val reportMonthFormat = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)

    private val gpsPrefs by lazy { getSharedPreferences("gps_settings", Context.MODE_PRIVATE) }
    private val celestialPrefs by lazy {
        getSharedPreferences(CelestialGlobeModeV2.PREFS, Context.MODE_PRIVATE)
    }
    private val navigationPrefs by lazy { getSharedPreferences(NAVIGATION_PREFS, Context.MODE_PRIVATE) }

    private inline fun <reified T : View> requiredView(id: Int, name: String): T =
        requireNotNull(findViewById<T>(id)) { "MainActivity : vue obligatoire absente : $name" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.amaury.pointage.billing.HoraTrackBilling.initialize(this)
        setContentView(R.layout.activity_main)
        if (HoraTrackV2.ENABLED) V2RuntimeStore.bind(this)

        statusCard = requiredView(R.id.statusCard, "statusCard")
        historyText = requiredView(R.id.historyText, "historyText")
        contentTitle = requiredView(R.id.contentTitle, "contentTitle")
        clockDigital = requiredView(R.id.clockDigital, "clockDigital")
        contentPanel = requiredView(R.id.contentPanel, "contentPanel")
        navigationTabs = requiredView(R.id.navigationTabs, "navigationTabs")
        celestialHomePanel = requiredView(R.id.celestialHomePanel, "celestialHomePanel")
        sunIndicator = requiredView(R.id.sunIndicator, "sunIndicator")
        pointageButtons = requiredView(R.id.pointageButtons, "pointageButtons")
        gpsSettingsPanel = requiredView(R.id.gpsSettingsPanel, "gpsSettingsPanel")
        celestialGlobeModeGroup = requiredView(R.id.celestialGlobeModeGroup, "celestialGlobeModeGroup")
        analyticsPdfPanel = requiredView(R.id.analyticsPdfPanel, "analyticsPdfPanel")
        analyticsPdfPanel.addView(Button(this).apply {
            text = "PREMIUM ET ACHATS"
            setOnClickListener { BillingOffers.show(this@MainActivity) }
        })
        workplaceAddress = requiredView(R.id.workplaceAddress, "workplaceAddress")
        geofenceRadius = requiredView(R.id.geofenceRadius, "geofenceRadius")
        autoGpsSwitch = requiredView(R.id.autoGpsSwitch, "autoGpsSwitch")
        gpsStatusText = requiredView(R.id.gpsStatusText, "gpsStatusText")
        selectedReportMonthText = requiredView(R.id.selectedReportMonthText, "selectedReportMonthText")
        tabHome = requiredView(R.id.tabHome, "tabHome")
        tabToday = requiredView(R.id.tabToday, "tabToday")
        tabHistory = requiredView(R.id.tabHistory, "tabHistory")
        tabAnalytics = requiredView(R.id.tabAnalytics, "tabAnalytics")
        tabSalary = requiredView(R.id.tabSalary, "tabSalary")
        tabSettings = requiredView(R.id.tabSettings, "tabSettings")

        val entryButton: Button? = findViewById(R.id.entryButton)
        val exitButton: Button? = findViewById(R.id.exitButton)
        val locationPermissionButton: Button? = findViewById(R.id.locationPermissionButton)
        val chooseReportMonthButton: Button? = findViewById(R.id.chooseReportMonthButton)
        val generateMonthlyPdfButton: Button? = findViewById(R.id.generateMonthlyPdfButton)

        loadGpsSettings()
        loadCelestialSettings()
        restoreSelectedReportMonth()
        updateSelectedReportMonthText()

        celestialGlobeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingCelestialGlobeMode) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.celestialGlobeModeWorld -> CelestialGlobeModeV2.WORLD
                else -> CelestialGlobeModeV2.LOCAL
            }
            celestialPrefs.edit()
                .putString(CelestialGlobeModeV2.PREF_KEY_GLOBE_MODE, mode.name)
                .apply()
        }

        autoGpsSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingGpsSwitch) return@setOnCheckedChangeListener
            gpsRegistrationConfirmed = false
            gpsRegistrationError = null
            gpsPrefs.edit().putBoolean("enabled", checked).apply()
            if (!checked) {
                gpsSaveRequestId++
                GeofenceManager.reconfigureStoredZones(this)
                updateGpsStatus()
                Toast.makeText(this, "Pointage automatique GPS désactivé", Toast.LENGTH_SHORT).show()
            } else if (!GeofenceManager.hasRequiredPermissions(this)) {
                updateGpsStatus()
                Toast.makeText(this, "Autorise la localisation pour activer le pointage automatique", Toast.LENGTH_LONG).show()
                requestLocationAccess()
            } else {
                enableAutomaticGpsFromCanonicalZones()
                requestPointageNotificationPermissionIfNeeded()
            }
        }

        entryButton?.setOnClickListener {
            animateClick(entryButton)
            val ok = if (HoraTrackV2.ENABLED) {
                V2RuntimeStore.entry(this)
            } else {
                V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.POINTAGE)
                PointageStore.entry(this)
            }
            val message = when {
                ok -> "Entrée enregistrée"
                HoraTrackV2.ENABLED && !V2RuntimeReader.current(this).reliable -> "Pointage bloqué : données AGKGMG à vérifier"
                else -> "Une entrée est déjà en cours"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            if (ok) {
                // Les actions manuelles doivent aussi mettre à jour immédiatement la
                // notification système, sans attendre une nouvelle transition GPS.
                IconSwitcher.sync(this)
                refreshScreen()
            }
        }

        exitButton?.setOnClickListener {
            animateClick(exitButton)
            val ok = if (HoraTrackV2.ENABLED) {
                V2RuntimeStore.exit(this)
            } else {
                V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.POINTAGE)
                PointageStore.exit(this)
            }
            val message = when {
                ok -> "Sortie enregistrée"
                HoraTrackV2.ENABLED && !V2RuntimeReader.current(this).reliable -> "Pointage bloqué : données AGKGMG à vérifier"
                else -> "Aucune entrée en cours"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            if (ok) {
                // Les actions manuelles doivent aussi mettre à jour immédiatement la
                // notification système, sans attendre une nouvelle transition GPS.
                IconSwitcher.sync(this)
                refreshScreen()
            }
        }

        locationPermissionButton?.setOnClickListener { animateClick(locationPermissionButton); requestLocationAccess() }
        chooseReportMonthButton?.setOnClickListener { animateClick(chooseReportMonthButton); showReportMonthDialog() }
        generateMonthlyPdfButton?.setOnClickListener { animateClick(generateMonthlyPdfButton); requestMonthlyPdfPreview() }

        tabHome.setOnClickListener { showHomeTab() }
        tabToday.setOnClickListener { showTodayTab() }
        tabHistory.setOnClickListener { showHistoryTab() }
        tabAnalytics.setOnClickListener { showAnalyticsTab() }
        tabSettings.setOnClickListener { showSettingsTab() }
        openRequestedTab(intent)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (activeTab == "home" && event.actionMasked == MotionEvent.ACTION_DOWN) {
            revealHomeTabsAndScheduleHide()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            openRequestedTab(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (HoraTrackV2.ENABLED) V2RuntimeStore.bind(this)
        gpsRegistrationConfirmed = false
        gpsRegistrationError = null
        updateGpsStatus()
        tryRestoreGeofence()
        requestPointageNotificationPermissionIfNeeded()
        when (activeTab) {
            "home" -> showHomeTab()
            "history" -> showHistoryTab()
            "analytics" -> showAnalyticsTab()
            "salary" -> tabSalary.performClick()
            "settings" -> showSettingsTab()
            else -> showTodayTab()
        }
    }

    override fun onDestroy() {
        updateSettingsBackCallback(false)
        gpsSaveRequestId++
        homeTabsHandler.removeCallbacks(hideHomeTabsRunnable)
        navigationTabs.animate().cancel()
        super.onDestroy()
    }

    private fun openRequestedTab(intent: Intent?) {
        val requestedTab = intent?.getStringExtra("open_tab")
        val targetTab = requestedTab ?: navigationPrefs.getString(KEY_ACTIVE_TAB, "home")
        when (targetTab) {
            "home" -> showHomeTab()
            "settings" -> showSettingsTab()
            "history" -> showHistoryTab()
            "analytics" -> showAnalyticsTab()
            "salary" -> tabSalary.performClick()
            else -> showTodayTab()
        }
    }

    private fun persistActiveTab(tab: String) {
        activeTab = tab
        updateSettingsBackCallback(tab == "settings" && SettingsCompactMenuV2.hasActivePage(this))
        navigationPrefs.edit().putString(KEY_ACTIVE_TAB, tab).apply()
    }

    internal fun onSalaryTabShown() {
        setCelestialHomeBackground(false)
        cancelHomeTabAutoHideAndShowTabs()
        persistActiveTab("salary")
        setActiveTab(tabSalary)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            ForegroundLocationInitProvider.REQUEST_FOREGROUND_LOCATION -> {
                ForegroundLocationInitProvider.markOnboardingResolved(this)
                updateGpsStatus()
                tryRestoreGeofence()
            }
            REQUEST_FINE_LOCATION -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) requestLocationAccess()
                else disableAutomaticGps("La localisation précise est nécessaire pour le pointage automatique")
            }
            REQUEST_BACKGROUND_LOCATION -> {
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    updateGpsStatus()
                    tryRestoreGeofence()
                    requestPointageNotificationPermissionIfNeeded()
                } else disableAutomaticGps("Autorise la localisation tout le temps pour le pointage automatique")
            }
            REQUEST_POINTAGE_NOTIFICATIONS -> {
                updateGpsStatus()
                if (autoGpsSwitch.isChecked) tryRestoreGeofence()
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(
                        this,
                        "Notifications refusées : l’indicateur rouge/vert/orange ne peut pas rester dans la barre système et les sorties GPS seront confirmées à la prochaine ouverture.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    IconSwitcher.sync(this)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE_MONTHLY_PDF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (HoraTrackV2.ENABLED) {
            // A result from the former destination-first flow cannot bypass V2 preparation.
            runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            startActivity(Intent(this, V2MonthlyPdfActivity::class.java).apply {
                putExtra("report_year", pendingPdfYear)
                putExtra("report_month", pendingPdfMonth)
            })
            return
        }
        try {
            val file = java.io.File.createTempFile("monthly_export_", ".pdf", cacheDir)
            file.outputStream().use { output ->
                V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PDF)
                MonthlyPdfReport.write(this, PointageStore.load(this), pendingPdfYear, pendingPdfMonth, output)
            }
            BillingPdfGate.require(this, file, "AGKGMG_${pendingPdfYear}_${pendingPdfMonth + 1}.pdf", onDenied = {
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            }) { authorizedFile ->
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output -> authorizedFile.inputStream().use { it.copyTo(output) } }
                        ?: error("Impossible d'ouvrir le fichier")
                }.onSuccess { Toast.makeText(this, "PDF mensuel enregistré", Toast.LENGTH_LONG).show() }
                    .onFailure {
                        runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                        Toast.makeText(this, "Impossible d'enregistrer le PDF", Toast.LENGTH_LONG).show()
                    }
            }
        } catch (e: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            Toast.makeText(this, "Impossible de générer le PDF : ${e.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun animateClick(button: Button) {
        button.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
            button.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun setCelestialHomeBackground(active: Boolean) {
        findViewById<ThemedBackgroundScrollView>(R.id.appRootScroll)
            ?.setCelestialHomeActive(active)
    }

    private fun showHomeTab() {
        setCelestialHomeBackground(true)
        persistActiveTab("home")
        setActiveTab(tabHome)
        revealHomeTabsAndScheduleHide()
        celestialHomePanel.visibility = View.VISIBLE
        sunIndicator.setSunVisible(true)
        // Le Soleil et la Lune sont des objets célestes du premier plan :
        // ils ne doivent jamais être masqués par le cadran central.
        sunIndicator.bringToFront()
        clockDigital.visibility = View.VISIBLE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        contentPanel.visibility = View.GONE
        historyText.visibility = View.GONE
        analyticsPdfPanel.visibility = View.GONE
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.visibility = View.GONE
    }

    private fun showTodayTab() {
        setCelestialHomeBackground(false)
        cancelHomeTabAutoHideAndShowTabs()
        persistActiveTab("today")
        setActiveTab(tabToday)
        celestialHomePanel.visibility = View.GONE
        sunIndicator.setSunVisible(false)
        contentPanel.visibility = View.VISIBLE
        contentTitle.visibility = View.VISIBLE
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.VISIBLE
        pointageButtons.visibility = View.VISIBLE
        historyText.visibility = View.VISIBLE
        analyticsPdfPanel.visibility = View.GONE
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.text = "HISTORIQUE DU JOUR"
        refreshScreen()
    }

    private fun showHistoryTab() {
        setCelestialHomeBackground(false)
        cancelHomeTabAutoHideAndShowTabs()
        persistActiveTab("history")
        setActiveTab(tabHistory)
        celestialHomePanel.visibility = View.GONE
        sunIndicator.setSunVisible(false)
        contentPanel.visibility = View.VISIBLE
        contentTitle.visibility = View.VISIBLE
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        historyText.visibility = View.VISIBLE
        analyticsPdfPanel.visibility = View.GONE
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.text = "HISTORIQUE COMPLET"
        historyText.text = if (HoraTrackV2.ENABLED) buildV2HistoryText(todayOnly = false) else buildLegacyHistoryText()
    }

    private fun showAnalyticsTab() {
        setCelestialHomeBackground(false)
        cancelHomeTabAutoHideAndShowTabs()
        persistActiveTab("analytics")
        setActiveTab(tabAnalytics)
        celestialHomePanel.visibility = View.GONE
        sunIndicator.setSunVisible(false)
        contentPanel.visibility = View.VISIBLE
        contentTitle.visibility = View.VISIBLE
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        historyText.visibility = View.VISIBLE
        analyticsPdfPanel.visibility = View.VISIBLE
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.text = "HEURES PAR LIEU"
        historyText.text = if (HoraTrackV2.ENABLED) buildV2AnalyticsText() else buildLegacyAnalyticsText()
        updateSelectedReportMonthText()
    }

    private fun showSettingsTab() {
        setCelestialHomeBackground(false)
        cancelHomeTabAutoHideAndShowTabs()
        persistActiveTab("settings")
        setActiveTab(tabSettings)
        celestialHomePanel.visibility = View.GONE
        sunIndicator.setSunVisible(false)
        contentPanel.visibility = View.VISIBLE
        contentTitle.visibility = View.VISIBLE
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        historyText.visibility = View.GONE
        analyticsPdfPanel.visibility = View.GONE
        gpsSettingsPanel.visibility = View.VISIBLE
        contentTitle.text = "PARAMÈTRES"
        loadCelestialSettings()
        loadGpsSettings()
        updateGpsStatus()
    }

    private fun revealHomeTabsAndScheduleHide() {
        if (activeTab != "home") return
        homeTabsHandler.removeCallbacks(hideHomeTabsRunnable)
        navigationTabs.animate().cancel()
        if (navigationTabs.visibility != View.VISIBLE) {
            navigationTabs.alpha = 0f
            navigationTabs.visibility = View.VISIBLE
            navigationTabs.animate().alpha(1f).setDuration(160L).start()
        } else {
            navigationTabs.alpha = 1f
        }
        homeTabsHandler.postDelayed(
            hideHomeTabsRunnable,
            HomeTabVisibilityPolicyV2.INACTIVITY_TIMEOUT_MS
        )
    }

    private fun cancelHomeTabAutoHideAndShowTabs() {
        homeTabsHandler.removeCallbacks(hideHomeTabsRunnable)
        navigationTabs.animate().cancel()
        navigationTabs.alpha = 1f
        navigationTabs.visibility = View.VISIBLE
    }

    private fun setActiveTab(active: TextView) {
        listOf(tabHome, tabToday, tabHistory, tabAnalytics, tabSalary, tabSettings).forEach { tab ->
            NavigationTabContrastV2.style(tab, tab === active)
        }
    }

    private fun restoreSelectedReportMonth() {
        val savedMonthMs = navigationPrefs.getLong(KEY_REPORT_MONTH_MS, -1L)
        if (savedMonthMs > 0L) selectedReportMonth.timeInMillis = savedMonthMs
    }

    private fun updateSelectedReportMonthText() {
        val label = reportMonthFormat.format(selectedReportMonth.time).replaceFirstChar { it.uppercase() }
        selectedReportMonthText.text = "Mois du rapport : $label"
    }

    private fun showReportMonthDialog() {
        val options = ArrayList<String>()
        val calendars = ArrayList<Calendar>()
        val cursor = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(36) {
            calendars.add(cursor.clone() as Calendar)
            options.add(reportMonthFormat.format(cursor.time).replaceFirstChar { it.uppercase() })
            cursor.add(Calendar.MONTH, -1)
        }
        val selectedIndex = calendars.indexOfFirst {
            it.get(Calendar.YEAR) == selectedReportMonth.get(Calendar.YEAR) && it.get(Calendar.MONTH) == selectedReportMonth.get(Calendar.MONTH)
        }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Choisir le mois du rapport")
            .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { dialog, which ->
                selectedReportMonth.timeInMillis = calendars[which].timeInMillis
                navigationPrefs.edit().putLong(KEY_REPORT_MONTH_MS, selectedReportMonth.timeInMillis).apply()
                updateSelectedReportMonthText(); dialog.dismiss()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun requestMonthlyPdfPreview() {
        if (HoraTrackV2.ENABLED) {
            startActivity(Intent(this, V2MonthlyPdfActivity::class.java).apply {
                putExtra("report_year", selectedReportMonth.get(Calendar.YEAR))
                putExtra("report_month", selectedReportMonth.get(Calendar.MONTH))
                putExtra("report_preview", true)
            })
        } else {
            findViewById<PreviewPdfButton>(R.id.generateMonthlyPdfButton)?.openPreview(
                selectedReportMonth.get(Calendar.YEAR), selectedReportMonth.get(Calendar.MONTH))
        }
    }

    private fun loadCelestialSettings() {
        val mode = CelestialGlobeModeV2.fromStored(
            celestialPrefs.getString(CelestialGlobeModeV2.PREF_KEY_GLOBE_MODE, null)
        )
        updatingCelestialGlobeMode = true
        celestialGlobeModeGroup.check(
            if (mode == CelestialGlobeModeV2.WORLD) {
                R.id.celestialGlobeModeWorld
            } else {
                R.id.celestialGlobeModeLocal
            }
        )
        updatingCelestialGlobeMode = false

        findViewById<TextView>(R.id.celestialWeatherAttribution)?.apply {
            val endpoint = BuildConfig.CELESTIAL_WEATHER_ENDPOINT
            visibility = if (endpoint.contains("open-meteo.com", ignoreCase = true)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun loadGpsSettings() {
        workplaceAddress.setText(gpsPrefs.getString("address", "") ?: "")
        workplaceAddress.hint = "Une adresse par ligne — 10 adresses maximum"
        geofenceRadius.setText(gpsPrefs.getInt("radius", 150).toString())
        updatingGpsSwitch = true
        autoGpsSwitch.isChecked = gpsPrefs.getBoolean("enabled", false)
        updatingGpsSwitch = false
    }

    private fun enableAutomaticGpsFromCanonicalZones() {
        when (val stored = readPersistedGpsZones(gpsPrefs)) {
            GpsZonesReadResult.Missing -> {
                disableAutomaticGps("Aucune zone GPS enregistrée")
            }
            is GpsZonesReadResult.Corrupt -> {
                disableAutomaticGps("Configuration GPS invalide : vérifie les zones enregistrées")
            }
            is GpsZonesReadResult.Valid -> {
                if (stored.zones.isEmpty()) {
                    disableAutomaticGps("Aucune zone GPS valide enregistrée")
                    return
                }
                GeofenceManager.resyncStoredZones(this) { success, message ->
                    runOnUiThread {
                        showGpsReconciliationResult(success, message)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun requestLocationAccess() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_FINE_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AlertDialog.Builder(this).setTitle("Autoriser le pointage automatique")
                    .setMessage("Pour détecter automatiquement l'arrivée et le départ même quand AGKGMG est fermé, choisis Localisation puis « Toujours autoriser ».")
                    .setPositiveButton("OUVRIR LES RÉGLAGES") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }) }
                    .setNegativeButton("Annuler") { _, _ -> disableAutomaticGps("Localisation en arrière-plan non autorisée") }.show()
            } else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND_LOCATION)
            return
        }
        Toast.makeText(this, "Localisation autorisée", Toast.LENGTH_SHORT).show()
        updateGpsStatus()
        if (autoGpsSwitch.isChecked) tryRestoreGeofence()
        requestPointageNotificationPermissionIfNeeded()
    }

    private fun requestPointageNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            IconSwitcher.sync(this)
            return
        }
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            IconSwitcher.sync(this)
            return
        }
        val required = PointageStatusNotificationV2.isEnabled(this) || autoGpsSwitch.isChecked
        if (!required) return
        if (PointageStatusNotificationV2.permissionWasRequested(this)) return

        PointageStatusNotificationV2.markPermissionRequested(this)
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POINTAGE_NOTIFICATIONS
        )
    }

    internal fun requestPointageNotificationPermissionFromSettings() {
        requestPointageNotificationPermissionIfNeeded()
    }

    private fun disableAutomaticGps(message: String) {
        gpsSaveRequestId++
        gpsRegistrationConfirmed = false
        gpsRegistrationError = null
        updatingGpsSwitch = true; autoGpsSwitch.isChecked = false; updatingGpsSwitch = false
        gpsPrefs.edit().putBoolean("enabled", false)
            .remove("active_zones").remove("entry_resolution_pending")
            .remove("entry_resolution_token").remove("pending_exit_zones").apply()
        GeofenceManager.reconfigureStoredZones(this)
        GpsExitConfirmationNotificationV2.cancel(this)
        gpsStatusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun tryRestoreGeofence() {
        if (!gpsPrefs.getBoolean("enabled", false) || !GeofenceManager.hasRequiredPermissions(this)) return
        when (val stored = readPersistedGpsZones(gpsPrefs)) {
            GpsZonesReadResult.Missing -> {
                disableAutomaticGps("Aucune zone GPS enregistrée")
            }

            is GpsZonesReadResult.Corrupt -> {
                disableAutomaticGps("Configuration GPS invalide : reconfigure les adresses")
            }

            is GpsZonesReadResult.Valid -> {
                if (stored.zones.isEmpty()) {
                    disableAutomaticGps("Aucune zone GPS valide enregistrée")
                    return
                }
                // Reopening the app only reconciles Android's registrations. It must
                // not discard a business event (for example an EXIT awaiting confirmation).
                GeofenceManager.resyncStoredZones(this) { success, message ->
                    runOnUiThread {
                        showGpsReconciliationResult(success, message)
                    }
                }
            }
        }
    }

    private fun updateGpsStatus() {
        val precisePermission =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val systemLocationEnabled = DeviceLocationAvailability.isEnabled(this)
        val status = gpsAutomaticStatus(
            enabled = autoGpsSwitch.isChecked,
            precisePermission = precisePermission,
            backgroundPermission = backgroundPermission,
            systemLocationEnabled = systemLocationEnabled,
            registrationCurrent = gpsRegistrationConfirmed &&
                GeofenceManager.isStoredRegistrationCurrent(this),
            registrationError = gpsRegistrationError
        )
        val openSystemSettings = shouldOpenSystemLocationSettings(
            enabled = autoGpsSwitch.isChecked,
            precisePermission = precisePermission,
            backgroundPermission = backgroundPermission,
            systemLocationEnabled = systemLocationEnabled
        )
        gpsStatusText.text = if (openSystemSettings) {
            "$status\nOuvrir les réglages de localisation"
        } else {
            status
        }
        gpsStatusText.contentDescription = if (openSystemSettings) {
            "$status. Ouvrir les réglages de localisation"
        } else {
            null
        }
        gpsStatusText.isClickable = openSystemSettings
        gpsStatusText.isFocusable = openSystemSettings
        gpsStatusText.setOnClickListener(
            if (openSystemSettings) {
                View.OnClickListener {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            } else {
                null
            }
        )
    }

    private fun showGpsReconciliationResult(success: Boolean, message: String) {
        gpsRegistrationConfirmed = success && GeofenceManager.isStoredRegistrationCurrent(this)
        gpsRegistrationError = if (success || !autoGpsSwitch.isChecked) null else message
        updateGpsStatus()
    }

    private fun refreshScreen() {
        if (HoraTrackV2.ENABLED) {
            val read = V2RuntimeReader.current(this)
            if (!read.reliable) {
                statusCard.text = "STATUT ACTUEL\n⚠ DONNÉES À VÉRIFIER"
                historyText.text = "Historique AGKGMG indisponible.\n${V2RuntimeReader.warningText(read.warnings)}"
                return
            }
            val session = read.snapshot.session
            val openPause = session?.pauses?.lastOrNull { it.endMs == null }
            statusCard.text = when {
                session == null -> "STATUT ACTUEL\n○ Aucune entrée en cours"
                session.status == SessionStatusV2.CLOSED -> "STATUT ACTUEL\n● SESSION TERMINÉE"
                openPause != null -> "STATUT ACTUEL\n⏸ PAUSE EN COURS\nDepuis ${dateFormat.format(Date(openPause.startMs))}"
                else -> "STATUT ACTUEL\n● ENTRÉE EN COURS\nDepuis ${dateFormat.format(Date(session.realArrivalMs ?: System.currentTimeMillis()))}"
            }
            historyText.text = buildV2HistoryText(todayOnly = true)
            return
        }
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.HISTORY)
        val data = PointageStore.load(this)
        var openItem: JSONObject? = null
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optLong("entry", -1L) > 0L && item.isNull("exit")) { openItem = item; break }
        }
        statusCard.text = if (openItem != null) "STATUT ACTUEL\n● ENTRÉE EN COURS\nDepuis ${dateFormat.format(Date(openItem.optLong("entry")))}" else "STATUT ACTUEL\n○ Aucune entrée en cours"
        historyText.text = buildLegacyTodayHistoryText()
    }

    private fun buildV2HistoryText(todayOnly: Boolean): String {
        val now = System.currentTimeMillis()
        val read = V2RuntimeReader.allSessions(this, now)
        if (!read.reliable) {
            return "Historique AGKGMG indisponible.\n${V2RuntimeReader.warningText(read.warnings)}"
        }

        val employerNames = buildV2EmployerNames()
        val sessions = HistoryTextFormatterV2.selectSessions(
            sessions = read.sessions,
            nowMs = now,
            todayOnly = todayOnly,
            employerNames = employerNames
        )
        return HistoryTextFormatterV2.format(
            sessions = sessions,
            engine = HoraTrackV2.time,
            nowMs = now,
            options = HistoryTextFormatterV2.Options(
                employerNames = employerNames,
                emptyMessage = if (todayOnly) "Aucun pointage aujourd'hui." else "Aucun historique."
            )
        )
    }

    private fun buildV2EmployerNames(): Map<String, String> = buildMap {
        for (slot in 1..2) {
            V2ProfileStore.load(this@MainActivity, slot).employer?.let { put(it.id, it.name) }
        }
    }

    private fun buildV2AnalyticsText(): String {
        val read = V2RuntimeReader.allSessions(this)
        if (!read.reliable) {
            return "⚠️ ANALYSE INDISPONIBLE\n${V2RuntimeReader.warningText(read.warnings)}"
        }
        val analytics = com.amaury.pointage.v2.engine.AnalyticsEngineV2.summarize(read.sessions, HoraTrackV2.time, System.currentTimeMillis())
        if (!analytics.timeTotalsReliable) {
            return "⚠️ ANALYSE À CONFIRMER\nUne ou plusieurs sessions contiennent une durée ou une pause non certifiable."
        }
        return "⏱ TOTAL PRÉSENCE : ${formatDuration(analytics.totalPresenceMs)}\n⏱ TOTAL PAYÉ : ${formatDuration(analytics.totalPaidMs)}\n✅ Sessions : ${analytics.sessions}\n⚠️ Avertissements : ${analytics.warnings}"
    }

    private fun buildLegacyTodayHistoryText(): String {
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.HISTORY)
        val data = PointageStore.load(this)
        val today = Calendar.getInstance(Locale.FRANCE)
        val now = System.currentTimeMillis()
        val builder = StringBuilder()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
            if (cal.get(Calendar.YEAR) != today.get(Calendar.YEAR) || cal.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) continue
            builder.append("🟢 ").append(dateFormat.format(Date(entry))).append("  ENTRÉE\n")
            val end = if (item.isNull("exit")) now else item.optLong("exit", entry)
            if (!item.isNull("exit")) builder.append("🔴 ").append(dateFormat.format(Date(end))).append("  SORTIE\n") else builder.append("🟢 EN COURS\n")
            builder.append('\n')
        }
        return builder.toString().ifBlank { "Aucun pointage aujourd'hui." }
    }

    private fun buildLegacyHistoryText(): String {
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.HISTORY)
        val data = PointageStore.load(this)
        val builder = StringBuilder()
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            builder.append("🟢 ").append(fullDateFormat.format(Date(entry))).append("  ENTRÉE\n")
            if (!item.isNull("exit")) builder.append("🔴 ").append(fullDateFormat.format(Date(item.optLong("exit")))).append("  SORTIE\n")
            builder.append('\n')
        }
        return builder.toString().ifBlank { "Aucun historique." }
    }

    private fun buildLegacyAnalyticsText(): String {
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.ANALYTICS)
        return "Analyses historiques désactivées lorsque AGKGMG est actif."
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun showSettingsDialog() { showSettingsTab() }
}
