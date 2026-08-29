package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2LegacyPolicy
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.MonthlyPdfReportV2
import com.amaury.pointage.v2.model.SessionStatusV2
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CREATE_MONTHLY_PDF = 2002
        private const val REQUEST_FINE_LOCATION = 3001
        private const val REQUEST_BACKGROUND_LOCATION = 3002
        private const val NAVIGATION_PREFS = "navigation_state"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_REPORT_MONTH_MS = "report_month_ms"
    }

    private lateinit var statusCard: TextView
    private lateinit var historyText: TextView
    private lateinit var contentTitle: TextView
    private lateinit var clockDigital: TextClock
    private lateinit var pointageButtons: LinearLayout
    private lateinit var gpsSettingsPanel: LinearLayout
    private lateinit var analyticsPdfPanel: LinearLayout
    private lateinit var workplaceAddress: EditText
    private lateinit var geofenceRadius: EditText
    private lateinit var autoGpsSwitch: Switch
    private lateinit var gpsStatusText: TextView
    private lateinit var selectedReportMonthText: TextView
    private lateinit var tabToday: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabAnalytics: TextView
    private lateinit var tabSettings: TextView

    private var activeTab = "today"
    private var updatingGpsSwitch = false

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
    private val navigationPrefs by lazy { getSharedPreferences(NAVIGATION_PREFS, Context.MODE_PRIVATE) }

    private inline fun <reified T : View> requiredView(id: Int, name: String): T =
        requireNotNull(findViewById<T>(id)) { "MainActivity : vue obligatoire absente : $name" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (HoraTrackV2.ENABLED) V2RuntimeStore.bind(this)

        statusCard = requiredView(R.id.statusCard, "statusCard")
        historyText = requiredView(R.id.historyText, "historyText")
        contentTitle = requiredView(R.id.contentTitle, "contentTitle")
        clockDigital = requiredView(R.id.clockDigital, "clockDigital")
        pointageButtons = requiredView(R.id.pointageButtons, "pointageButtons")
        gpsSettingsPanel = requiredView(R.id.gpsSettingsPanel, "gpsSettingsPanel")
        analyticsPdfPanel = requiredView(R.id.analyticsPdfPanel, "analyticsPdfPanel")
        workplaceAddress = requiredView(R.id.workplaceAddress, "workplaceAddress")
        geofenceRadius = requiredView(R.id.geofenceRadius, "geofenceRadius")
        autoGpsSwitch = requiredView(R.id.autoGpsSwitch, "autoGpsSwitch")
        gpsStatusText = requiredView(R.id.gpsStatusText, "gpsStatusText")
        selectedReportMonthText = requiredView(R.id.selectedReportMonthText, "selectedReportMonthText")
        tabToday = requiredView(R.id.tabToday, "tabToday")
        tabHistory = requiredView(R.id.tabHistory, "tabHistory")
        tabAnalytics = requiredView(R.id.tabAnalytics, "tabAnalytics")
        tabSettings = requiredView(R.id.tabSettings, "tabSettings")

        val settingsButton: Button? = findViewById(R.id.settingsButton)
        val entryButton: Button? = findViewById(R.id.entryButton)
        val exitButton: Button? = findViewById(R.id.exitButton)
        val saveGpsSettingsButton: Button? = findViewById(R.id.saveGpsSettingsButton)
        val locationPermissionButton: Button? = findViewById(R.id.locationPermissionButton)
        val chooseReportMonthButton: Button? = findViewById(R.id.chooseReportMonthButton)
        val generateMonthlyPdfButton: Button? = findViewById(R.id.generateMonthlyPdfButton)

        loadGpsSettings()
        restoreSelectedReportMonth()
        updateSelectedReportMonthText()

        autoGpsSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingGpsSwitch) return@setOnCheckedChangeListener
            gpsPrefs.edit().putBoolean("enabled", checked).apply()
            if (!checked) {
                gpsPrefs.edit().remove("active_zones").apply()
                GeofenceManager.unregisterAll(this)
                updateGpsStatus()
                Toast.makeText(this, "Pointage automatique GPS désactivé", Toast.LENGTH_SHORT).show()
            } else if (!GeofenceManager.hasRequiredPermissions(this)) {
                updateGpsStatus()
                Toast.makeText(this, "Autorise la localisation pour activer le pointage automatique", Toast.LENGTH_LONG).show()
                requestLocationAccess()
            } else {
                saveGpsSettings()
            }
        }

        settingsButton?.setOnClickListener {
            animateClick(settingsButton)
            showSettingsDialog()
        }

        entryButton?.setOnClickListener {
            animateClick(entryButton)
            val ok = if (HoraTrackV2.ENABLED) {
                V2RuntimeStore.entry(this)
            } else {
                V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.POINTAGE)
                PointageStore.entry(this)
            }
            Toast.makeText(this, if (ok) "Entrée enregistrée" else "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
            if (ok) refreshScreen()
        }

        exitButton?.setOnClickListener {
            animateClick(exitButton)
            val ok = if (HoraTrackV2.ENABLED) {
                V2RuntimeStore.exit(this)
            } else {
                V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.POINTAGE)
                PointageStore.exit(this)
            }
            Toast.makeText(this, if (ok) "Sortie enregistrée" else "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
            if (ok) refreshScreen()
        }

        saveGpsSettingsButton?.setOnClickListener { animateClick(saveGpsSettingsButton); saveGpsSettings() }
        locationPermissionButton?.setOnClickListener { animateClick(locationPermissionButton); requestLocationAccess() }
        chooseReportMonthButton?.setOnClickListener { animateClick(chooseReportMonthButton); showReportMonthDialog() }
        generateMonthlyPdfButton?.setOnClickListener { animateClick(generateMonthlyPdfButton); requestMonthlyPdfDestination() }

        tabToday.setOnClickListener { showTodayTab() }
        tabHistory.setOnClickListener { showHistoryTab() }
        tabAnalytics.setOnClickListener { showAnalyticsTab() }
        tabSettings.setOnClickListener { showSettingsTab() }
        openRequestedTab(intent)
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
        updateGpsStatus()
        tryRestoreGeofence()
        when (activeTab) {
            "history" -> showHistoryTab()
            "analytics" -> showAnalyticsTab()
            "settings" -> showSettingsTab()
            else -> showTodayTab()
        }
    }

    private fun openRequestedTab(intent: Intent?) {
        val requestedTab = intent?.getStringExtra("open_tab")
        val targetTab = requestedTab ?: navigationPrefs.getString(KEY_ACTIVE_TAB, "today")
        when (targetTab) {
            "settings" -> showSettingsTab()
            "history" -> showHistoryTab()
            "analytics" -> showAnalyticsTab()
            else -> showTodayTab()
        }
    }

    private fun persistActiveTab(tab: String) {
        activeTab = tab
        navigationPrefs.edit().putString(KEY_ACTIVE_TAB, tab).apply()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
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
                } else disableAutomaticGps("Autorise la localisation tout le temps pour le pointage automatique")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE_MONTHLY_PDF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                if (HoraTrackV2.ENABLED) {
                    MonthlyPdfReportV2.write(V2RuntimeStore.allSessions(this), pendingPdfYear, pendingPdfMonth, output)
                } else {
                    V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PDF)
                    MonthlyPdfReport.write(this, PointageStore.load(this), pendingPdfYear, pendingPdfMonth, output)
                }
            } ?: throw IllegalStateException("Impossible d'ouvrir le fichier")
            Toast.makeText(this, "PDF mensuel enregistré", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible de générer le PDF : ${e.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun animateClick(button: Button) {
        button.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
            button.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun showTodayTab() {
        persistActiveTab("today")
        setActiveTab(tabToday)
        clockDigital.visibility = View.VISIBLE
        statusCard.visibility = View.VISIBLE
        pointageButtons.visibility = View.VISIBLE
        historyText.visibility = View.VISIBLE
        analyticsPdfPanel.visibility = View.GONE
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.text = "HISTORIQUE DU JOUR"
        refreshScreen()
    }

    private fun showHistoryTab() {
        persistActiveTab("history")
        setActiveTab(tabHistory)
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
        persistActiveTab("analytics")
        setActiveTab(tabAnalytics)
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
        persistActiveTab("settings")
        setActiveTab(tabSettings)
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        historyText.visibility = View.GONE
        analyticsPdfPanel.visibility = View.GONE
        gpsSettingsPanel.visibility = View.VISIBLE
        contentTitle.text = "LIEUX DE TRAVAIL GPS"
        loadGpsSettings()
        updateGpsStatus()
    }

    private fun setActiveTab(active: TextView) {
        val appearance = getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val mode = appearance.getString("mode", "auto") ?: "auto"
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && systemDark)
        val theme = AppThemeCatalog.current(this)
        val activeColor = if (dark) theme.accentLight else theme.accent
        val inactiveColor = if (dark) theme.darkHint else theme.lightHint
        tabToday.setTextColor(if (active == tabToday) activeColor else inactiveColor)
        tabHistory.setTextColor(if (active == tabHistory) activeColor else inactiveColor)
        tabAnalytics.setTextColor(if (active == tabAnalytics) activeColor else inactiveColor)
        tabSettings.setTextColor(if (active == tabSettings) activeColor else inactiveColor)
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

    private fun requestMonthlyPdfDestination() {
        pendingPdfYear = selectedReportMonth.get(Calendar.YEAR)
        pendingPdfMonth = selectedReportMonth.get(Calendar.MONTH)
        val monthFile = SimpleDateFormat("MMMM_yyyy", Locale.FRANCE).format(selectedReportMonth.time)
            .replaceFirstChar { it.uppercase() }.replace("é", "e").replace("è", "e").replace("ê", "e")
            .replace("û", "u").replace("ô", "o").replace("à", "a").replace("ç", "c")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, "Pointage_$monthFile.pdf")
        }, REQUEST_CREATE_MONTHLY_PDF)
    }

    private fun loadGpsSettings() {
        workplaceAddress.setText(gpsPrefs.getString("address", "") ?: "")
        workplaceAddress.hint = "Une adresse par ligne — 10 adresses maximum"
        geofenceRadius.setText(gpsPrefs.getInt("radius", 150).toString())
        updatingGpsSwitch = true
        autoGpsSwitch.isChecked = gpsPrefs.getBoolean("enabled", false)
        updatingGpsSwitch = false
    }

    private fun loadSavedZoneObjects(): JSONArray = runCatching { JSONArray(gpsPrefs.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }

    private fun existingZoneIdForAddress(address: String, existingZones: JSONArray): String? {
        for (i in 0 until existingZones.length()) {
            val zone = existingZones.optJSONObject(i) ?: continue
            if (zone.optString("address").trim().equals(address.trim(), ignoreCase = true)) return zone.optString("id").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun saveGpsSettings() {
        val rawLines = workplaceAddress.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.FRANCE) }
        if (rawLines.isEmpty()) { Toast.makeText(this, "Entre au moins une adresse", Toast.LENGTH_LONG).show(); return }
        val addresses = rawLines.take(10)
        if (rawLines.size > 10) Toast.makeText(this, "Seules les 10 premières adresses seront enregistrées", Toast.LENGTH_LONG).show()
        val radius = geofenceRadius.text.toString().toIntOrNull()?.coerceIn(50, 1000) ?: 150
        geofenceRadius.setText(radius.toString())
        val existingZones = loadSavedZoneObjects()
        val zones = JSONArray()
        val workZones = mutableListOf<WorkZone>()
        val failedAddresses = mutableListOf<String>()
        val geocoder = Geocoder(this, Locale.FRANCE)
        addresses.forEach { address ->
            val result = runCatching { geocoder.getFromLocationName(address, 1) }.getOrNull()?.firstOrNull()
            if (result != null) {
                val id = existingZoneIdForAddress(address, existingZones) ?: UUID.randomUUID().toString()
                zones.put(JSONObject().put("id", id).put("address", address).put("latitude", result.latitude).put("longitude", result.longitude).put("radius", radius))
                workZones += WorkZone(id, result.latitude, result.longitude, radius.toFloat())
            } else failedAddresses += address
        }
        gpsPrefs.edit().putString("address", addresses.joinToString("\n")).putInt("radius", radius)
            .putBoolean("enabled", autoGpsSwitch.isChecked).putString("zones", zones.toString()).remove("active_zones").apply()
        if (failedAddresses.isNotEmpty()) Toast.makeText(this, "${failedAddresses.size} adresse(s) n'ont pas pu être localisées.", Toast.LENGTH_LONG).show()
        if (autoGpsSwitch.isChecked) {
            if (workZones.isEmpty()) disableAutomaticGps("Aucune adresse valide pour le pointage GPS")
            else if (GeofenceManager.hasRequiredPermissions(this)) {
                GeofenceManager.registerAll(this, workZones) { success, message -> runOnUiThread {
                    gpsStatusText.text = if (success) "GPS automatique actif" else message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                } }
            } else requestLocationAccess()
        } else {
            GeofenceManager.unregisterAll(this)
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
        }
        updateGpsStatus()
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
                    .setMessage("Pour détecter automatiquement l'arrivée et le départ même quand HoraTrack est fermé, choisis Localisation puis « Toujours autoriser ».")
                    .setPositiveButton("OUVRIR LES RÉGLAGES") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }) }
                    .setNegativeButton("Annuler") { _, _ -> disableAutomaticGps("Localisation en arrière-plan non autorisée") }.show()
            } else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND_LOCATION)
            return
        }
        Toast.makeText(this, "Localisation autorisée", Toast.LENGTH_SHORT).show()
        updateGpsStatus()
        if (autoGpsSwitch.isChecked) tryRestoreGeofence()
    }

    private fun disableAutomaticGps(message: String) {
        updatingGpsSwitch = true; autoGpsSwitch.isChecked = false; updatingGpsSwitch = false
        gpsPrefs.edit().putBoolean("enabled", false).remove("active_zones").apply()
        GeofenceManager.unregisterAll(this)
        gpsStatusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun tryRestoreGeofence() {
        if (!gpsPrefs.getBoolean("enabled", false) || !GeofenceManager.hasRequiredPermissions(this)) return
        val array = loadSavedZoneObjects()
        val zones = mutableListOf<WorkZone>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val latitude = item.optDouble("latitude", Double.NaN)
            val longitude = item.optDouble("longitude", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            zones += WorkZone(id, latitude, longitude, item.optDouble("radius", 150.0).toFloat().coerceIn(50f, 1000f))
        }
        if (zones.isEmpty()) { disableAutomaticGps("Aucune zone GPS valide enregistrée"); return }
        GeofenceManager.registerAll(this, zones) { success, message -> runOnUiThread { gpsStatusText.text = if (success) "GPS automatique actif" else message } }
    }

    private fun updateGpsStatus() {
        gpsStatusText.text = when {
            !autoGpsSwitch.isChecked -> "GPS automatique désactivé"
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> "Localisation précise à autoriser"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED -> "Autorise la localisation tout le temps"
            else -> "GPS automatique actif"
        }
    }

    private fun refreshScreen() {
        if (HoraTrackV2.ENABLED) {
            val snap = V2RuntimeStore.snapshot(this)
            val session = snap.session
            statusCard.text = when {
                session == null -> "STATUT ACTUEL\n○ Aucune entrée en cours"
                session.status == SessionStatusV2.CLOSED -> "STATUT ACTUEL\n● SESSION TERMINÉE"
                session.pauses.any { it.endMs == null } -> "STATUT ACTUEL\n⏸ PAUSE EN COURS\nDepuis ${dateFormat.format(Date(session.realArrivalMs ?: System.currentTimeMillis()))}"
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
        val today = Calendar.getInstance(Locale.FRANCE)
        val sessions = V2RuntimeStore.allSessions(this, now).filter { session ->
            if (!todayOnly) true else session.realArrivalMs?.let { at ->
                val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = at }
                c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            } ?: false
        }.sortedByDescending { it.realArrivalMs ?: 0L }
        return buildString {
            sessions.forEach { s ->
                append("🟢 ").append(fullDateFormat.format(Date(s.realArrivalMs ?: 0L))).append("  ENTRÉE RÉELLE\n")
                append("⏱ ").append(s.countedEntryMs?.let { fullDateFormat.format(Date(it)) } ?: "—").append("  ENTRÉE COMPTÉE\n")
                s.placeLabel?.trim()?.takeIf { it.isNotBlank() }?.let { append("📍 ").append(it).append('\n') }
                s.pauses.forEachIndexed { index, p -> append("⏸ Pause ").append(index + 1).append(" : ").append(dateFormat.format(Date(p.startMs))).append(" → ").append(p.endMs?.let { dateFormat.format(Date(it)) } ?: "EN COURS").append('\n') }
                if (s.realExitMs != null) {
                    append("🔴 ").append(fullDateFormat.format(Date(s.realExitMs))).append("  SORTIE RÉELLE\n")
                    append("⏱ ").append(s.countedExitMs?.let { fullDateFormat.format(Date(it)) } ?: "—").append("  SORTIE COMPTÉE\n")
                } else append("🟢 EN COURS\n")
                val r = HoraTrackV2.time.calculate(s, now)
                append("Temps payé V2 : ").append(formatDuration(r.paidWorkMs)).append("\n\n")
            }
        }.ifBlank { if (todayOnly) "Aucun pointage aujourd'hui." else "Aucun historique." }
    }

    private fun buildV2AnalyticsText(): String {
        val sessions = V2RuntimeStore.allSessions(this)
        val analytics = com.amaury.pointage.v2.engine.AnalyticsEngineV2.summarize(sessions, HoraTrackV2.time, System.currentTimeMillis())
        return "⏱ TOTAL PRÉSENCE : ${formatDuration(analytics.totalPresenceMs)}\n⏱ TOTAL PAYÉ V2 : ${formatDuration(analytics.totalPaidMs)}\n✅ Sessions : ${analytics.sessions}\n⚠️ Avertissements : ${analytics.warnings}"
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
        return "Analyses historiques désactivées lorsque HoraTrack V2 est actif."
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun showSettingsDialog() { showSettingsTab() }
}
