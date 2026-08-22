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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CREATE_MONTHLY_PDF = 2002
        private const val REQUEST_FINE_LOCATION = 3001
        private const val REQUEST_BACKGROUND_LOCATION = 3002
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

    private val gpsPrefs by lazy {
        getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        historyText = findViewById(R.id.historyText)
        contentTitle = findViewById(R.id.contentTitle)
        clockDigital = findViewById(R.id.clockDigital)
        pointageButtons = findViewById(R.id.pointageButtons)
        gpsSettingsPanel = findViewById(R.id.gpsSettingsPanel)
        analyticsPdfPanel = findViewById(R.id.analyticsPdfPanel)

        workplaceAddress = findViewById(R.id.workplaceAddress)
        geofenceRadius = findViewById(R.id.geofenceRadius)
        autoGpsSwitch = findViewById(R.id.autoGpsSwitch)
        gpsStatusText = findViewById(R.id.gpsStatusText)
        selectedReportMonthText = findViewById(R.id.selectedReportMonthText)

        tabToday = findViewById(R.id.tabToday)
        tabHistory = findViewById(R.id.tabHistory)
        tabAnalytics = findViewById(R.id.tabAnalytics)
        tabSettings = findViewById(R.id.tabSettings)

        val settingsButton = findViewById<Button>(R.id.settingsButton)
        val entryButton = findViewById<Button>(R.id.entryButton)
        val exitButton = findViewById<Button>(R.id.exitButton)
        val saveGpsSettingsButton = findViewById<Button>(R.id.saveGpsSettingsButton)
        val locationPermissionButton = findViewById<Button>(R.id.locationPermissionButton)
        val chooseReportMonthButton = findViewById<Button>(R.id.chooseReportMonthButton)
        val generateMonthlyPdfButton = findViewById<Button>(R.id.generateMonthlyPdfButton)

        loadGpsSettings()
        updateSelectedReportMonthText()

        autoGpsSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingGpsSwitch) return@setOnCheckedChangeListener

            gpsPrefs.edit().putBoolean("enabled", checked).apply()
            if (!checked) {
                gpsPrefs.edit().remove("active_zones").apply()
                GeofenceManager.unregisterAll(this)
                updateGpsStatus()
                Toast.makeText(this, "Pointage automatique GPS désactivé", Toast.LENGTH_SHORT).show()
            } else {
                updateGpsStatus()
                if (!GeofenceManager.hasRequiredPermissions(this)) {
                    Toast.makeText(this, "Autorise la localisation pour activer le pointage automatique", Toast.LENGTH_LONG).show()
                    requestLocationAccess()
                } else {
                    saveGpsSettings()
                }
            }
        }

        settingsButton.setOnClickListener {
            animateClick(settingsButton)
            showSettingsDialog()
        }

        entryButton.setOnClickListener {
            animateClick(entryButton)
            if (PointageStore.entry(this)) {
                Toast.makeText(this, "Entrée enregistrée", Toast.LENGTH_SHORT).show()
                refreshScreen()
            } else {
                Toast.makeText(this, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
            }
        }

        exitButton.setOnClickListener {
            animateClick(exitButton)
            if (PointageStore.exit(this)) {
                Toast.makeText(this, "Sortie enregistrée", Toast.LENGTH_SHORT).show()
                refreshScreen()
            } else {
                Toast.makeText(this, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
            }
        }

        saveGpsSettingsButton.setOnClickListener {
            animateClick(saveGpsSettingsButton)
            saveGpsSettings()
        }

        locationPermissionButton.setOnClickListener {
            animateClick(locationPermissionButton)
            requestLocationAccess()
        }

        chooseReportMonthButton.setOnClickListener {
            animateClick(chooseReportMonthButton)
            showReportMonthDialog()
        }

        generateMonthlyPdfButton.setOnClickListener {
            animateClick(generateMonthlyPdfButton)
            requestMonthlyPdfDestination()
        }

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

    private fun openRequestedTab(intent: Intent?) {
        when (intent?.getStringExtra("open_tab")) {
            "settings" -> showSettingsTab()
            "history" -> showHistoryTab()
            "analytics" -> showAnalyticsTab()
            else -> showTodayTab()
        }
    }

    override fun onResume() {
        super.onResume()
        updateGpsStatus()
        tryRestoreGeofence()

        when (activeTab) {
            "history" -> showHistoryTab()
            "analytics" -> showAnalyticsTab()
            "settings" -> showSettingsTab()
            else -> showTodayTab()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_FINE_LOCATION -> {
                val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fineGranted) {
                    requestLocationAccess()
                } else {
                    disableAutomaticGps("La localisation précise est nécessaire pour le pointage automatique")
                }
            }
            REQUEST_BACKGROUND_LOCATION -> {
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    updateGpsStatus()
                    tryRestoreGeofence()
                } else {
                    disableAutomaticGps("Autorise la localisation tout le temps pour le pointage automatique")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CREATE_MONTHLY_PDF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                MonthlyPdfReport.write(
                    this,
                    PointageStore.load(this),
                    pendingPdfYear,
                    pendingPdfMonth,
                    output
                )
            } ?: throw IllegalStateException("Impossible d'ouvrir le fichier")

            Toast.makeText(this, "PDF mensuel enregistré", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Impossible de générer le PDF : ${e.message ?: "erreur inconnue"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun animateClick(button: Button) {
        button.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(80)
            .withEndAction {
                button.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    private fun showTodayTab() {
        activeTab = "today"
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
        activeTab = "history"
        setActiveTab(tabHistory)
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        historyText.visibility = View.VISIBLE
        analyticsPdfPanel.visibility = View.GONE
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.text = "HISTORIQUE COMPLET"
        historyText.text = buildHistoryText()
    }

    private fun showAnalyticsTab() {
        activeTab = "analytics"
        setActiveTab(tabAnalytics)
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        historyText.visibility = View.VISIBLE
        analyticsPdfPanel.visibility = View.VISIBLE
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.text = "HEURES PAR LIEU"
        historyText.text = buildAnalyticsText()
        updateSelectedReportMonthText()
    }

    private fun showSettingsTab() {
        activeTab = "settings"
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

    private fun updateSelectedReportMonthText() {
        val label = reportMonthFormat.format(selectedReportMonth.time)
            .replaceFirstChar { it.uppercase() }
        selectedReportMonthText.text = "Mois du rapport : $label"
    }

    private fun showReportMonthDialog() {
        val options = ArrayList<String>()
        val calendars = ArrayList<Calendar>()
        val cursor = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        repeat(36) {
            calendars.add(cursor.clone() as Calendar)
            options.add(reportMonthFormat.format(cursor.time).replaceFirstChar { it.uppercase() })
            cursor.add(Calendar.MONTH, -1)
        }

        val selectedIndex = calendars.indexOfFirst {
            it.get(Calendar.YEAR) == selectedReportMonth.get(Calendar.YEAR) &&
                it.get(Calendar.MONTH) == selectedReportMonth.get(Calendar.MONTH)
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Choisir le mois du rapport")
            .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { dialog, which ->
                selectedReportMonth.timeInMillis = calendars[which].timeInMillis
                updateSelectedReportMonthText()
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestMonthlyPdfDestination() {
        pendingPdfYear = selectedReportMonth.get(Calendar.YEAR)
        pendingPdfMonth = selectedReportMonth.get(Calendar.MONTH)

        val monthFile = SimpleDateFormat("MMMM_yyyy", Locale.FRANCE)
            .format(selectedReportMonth.time)
            .replaceFirstChar { it.uppercase() }
            .replace("é", "e")
            .replace("è", "e")
            .replace("ê", "e")
            .replace("û", "u")
            .replace("ô", "o")
            .replace("à", "a")
            .replace("ç", "c")

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "Pointage_$monthFile.pdf")
        }
        startActivityForResult(intent, REQUEST_CREATE_MONTHLY_PDF)
    }

    private fun loadGpsSettings() {
        workplaceAddress.setText(gpsPrefs.getString("address", "") ?: "")
        workplaceAddress.hint = "Une adresse par ligne — 10 adresses maximum"
        geofenceRadius.setText(gpsPrefs.getInt("radius", 150).toString())
        updatingGpsSwitch = true
        autoGpsSwitch.isChecked = gpsPrefs.getBoolean("enabled", false)
        updatingGpsSwitch = false
    }

    private fun loadSavedZoneObjects(): JSONArray =
        runCatching { JSONArray(gpsPrefs.getString("zones", "[]") ?: "[]") }
            .getOrElse { JSONArray() }

    private fun existingZoneIdForAddress(address: String, existingZones: JSONArray): String? {
        for (i in 0 until existingZones.length()) {
            val zone = existingZones.optJSONObject(i) ?: continue
            if (zone.optString("address").trim().equals(address.trim(), ignoreCase = true)) {
                return zone.optString("id").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun saveGpsSettings() {
        val rawLines = workplaceAddress.text.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.FRANCE) }

        if (rawLines.isEmpty()) {
            Toast.makeText(this, "Entre au moins une adresse", Toast.LENGTH_LONG).show()
            return
        }

        val addresses = rawLines.take(10)
        if (rawLines.size > 10) {
            Toast.makeText(this, "Seules les 10 premières adresses seront enregistrées", Toast.LENGTH_LONG).show()
        }

        val radius = geofenceRadius.text.toString().toIntOrNull()?.coerceIn(50, 1000) ?: 150
        geofenceRadius.setText(radius.toString())
        val existingZones = loadSavedZoneObjects()
        val zones = JSONArray()
        val workZones = mutableListOf<WorkZone>()
        val failedAddresses = mutableListOf<String>()
        val geocoder = Geocoder(this, Locale.FRANCE)

        addresses.forEach { address ->
            val result = runCatching { geocoder.getFromLocationName(address, 1) }
                .getOrNull()
                ?.firstOrNull()
            if (result != null) {
                val id = existingZoneIdForAddress(address, existingZones) ?: UUID.randomUUID().toString()
                zones.put(
                    JSONObject()
                        .put("id", id)
                        .put("address", address)
                        .put("latitude", result.latitude)
                        .put("longitude", result.longitude)
                        .put("radius", radius)
                )
                workZones += WorkZone(id, result.latitude, result.longitude, radius.toFloat())
            } else {
                failedAddresses += address
            }
        }

        gpsPrefs.edit()
            .putString("address", addresses.joinToString("\n"))
            .putInt("radius", radius)
            .putBoolean("enabled", autoGpsSwitch.isChecked)
            .putString("zones", zones.toString())
            .remove("active_zones")
            .apply()

        if (failedAddresses.isNotEmpty()) {
            Toast.makeText(
                this,
                "${failedAddresses.size} adresse(s) n'ont pas pu être localisées. Vérifie leur écriture avant d'utiliser le GPS automatique.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (autoGpsSwitch.isChecked) {
            if (workZones.isEmpty()) {
                disableAutomaticGps("Aucune adresse valide pour le pointage GPS")
            } else if (GeofenceManager.hasRequiredPermissions(this)) {
                GeofenceManager.registerAll(this, workZones) { success, message ->
                    runOnUiThread {
                        gpsStatusText.text = if (success) "GPS automatique actif" else message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, "Autorise d'abord la localisation", Toast.LENGTH_LONG).show()
                requestLocationAccess()
            }
        } else {
            GeofenceManager.unregisterAll(this)
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
        }
        updateGpsStatus()
    }

    private fun requestLocationAccess() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_FINE_LOCATION
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AlertDialog.Builder(this)
                    .setTitle("Autoriser le pointage automatique")
                    .setMessage("Pour détecter automatiquement l'arrivée et le départ même quand HP Travail est fermé, ouvre les autorisations de l'application, choisis Localisation puis « Toujours autoriser ». La localisation normale suffit pour le soleil et la lune.")
                    .setPositiveButton("OUVRIR LES RÉGLAGES") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("Annuler") { _, _ ->
                        disableAutomaticGps("Localisation en arrière-plan non autorisée")
                    }
                    .show()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_BACKGROUND_LOCATION
                )
            }
            return
        }

        val foregroundMessage = if (coarseGranted && !fineGranted) "Localisation approximative autorisée" else "Localisation autorisée"
        Toast.makeText(this, foregroundMessage, Toast.LENGTH_SHORT).show()
        updateGpsStatus()
        if (autoGpsSwitch.isChecked) tryRestoreGeofence()
    }

    private fun disableAutomaticGps(message: String) {
        updatingGpsSwitch = true
        autoGpsSwitch.isChecked = false
        updatingGpsSwitch = false
        gpsPrefs.edit()
            .putBoolean("enabled", false)
            .remove("active_zones")
            .apply()
        GeofenceManager.unregisterAll(this)
        gpsStatusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun tryRestoreGeofence() {
        if (!gpsPrefs.getBoolean("enabled", false)) return
        if (!GeofenceManager.hasRequiredPermissions(this)) return

        val array = loadSavedZoneObjects()
        val zones = mutableListOf<WorkZone>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val latitude = item.optDouble("latitude", Double.NaN)
            val longitude = item.optDouble("longitude", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            val radius = item.optDouble("radius", 150.0).toFloat().coerceIn(50f, 1000f)
            zones += WorkZone(id, latitude, longitude, radius)
        }

        if (zones.isEmpty()) {
            disableAutomaticGps("Aucune zone GPS valide enregistrée")
            return
        }

        GeofenceManager.registerAll(this, zones) { success, message ->
            runOnUiThread {
                gpsStatusText.text = if (success) "GPS automatique actif" else message
            }
        }
    }

    private fun updateGpsStatus() {
        gpsStatusText.text = when {
            !autoGpsSwitch.isChecked -> "GPS automatique désactivé"
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> "Localisation précise à autoriser"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "Autorise la localisation tout le temps"
            else -> "GPS automatique actif"
        }
    }

    private fun refreshScreen() {
        val data = PointageStore.load(this)
        var openItem: JSONObject? = null
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry > 0L && item.isNull("exit")) {
                openItem = item
                break
            }
        }

        statusCard.text = if (openItem != null) {
            val entry = openItem.optLong("entry")
            val state = if (PointageStore.isPaused(this)) "⏸ PAUSE EN COURS" else "● ENTRÉE EN COURS"
            "STATUT ACTUEL\n$state\nDepuis ${dateFormat.format(Date(entry))}"
        } else {
            "STATUT ACTUEL\n○ Aucune entrée en cours"
        }
        historyText.text = buildTodayHistoryText()
    }

    private fun buildTodayHistoryText(): String {
        val data = PointageStore.load(this)
        val today = Calendar.getInstance(Locale.FRANCE)
        val now = System.currentTimeMillis()
        val builder = StringBuilder()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
            if (cal.get(Calendar.YEAR) != today.get(Calendar.YEAR) ||
                cal.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)
            ) continue

            val place = item.optString("zoneAddress").ifBlank { "Pointage manuel / ancien pointage" }
            builder.append("📍 ").append(place).append('\n')
            builder.append("🟢 ").append(dateFormat.format(Date(entry))).append("  ENTRÉE\n")

            val end = if (item.isNull("exit")) now else item.optLong("exit", entry)
            val pauses = PointageStore.pauseDuration(item, end)
            if (pauses > 0L) {
                builder.append("⏸ Pauses : ").append(formatDuration(pauses)).append('\n')
            }

            if (!item.isNull("exit")) {
                builder.append("🔴 ")
                    .append(dateFormat.format(Date(end)))
                    .append("  SORTIE  ")
                    .append(formatDuration(PointageStore.workedDuration(item, end)))
                    .append(" travaillées\n")
            } else {
                builder.append("🟢 EN COURS  ")
                    .append(formatDuration(PointageStore.workedDuration(item, now)))
                    .append(" travaillées\n")
            }
            builder.append('\n')
        }
        return builder.toString().ifBlank { "Aucun pointage aujourd'hui." }
    }

    private fun buildHistoryText(): String {
        val data = PointageStore.load(this)
        val now = System.currentTimeMillis()
        val builder = StringBuilder()

        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            val place = item.optString("zoneAddress").ifBlank { "Pointage manuel / ancien pointage" }
            builder.append("📍 ").append(place).append('\n')
            builder.append("🟢 ").append(fullDateFormat.format(Date(entry))).append("  ENTRÉE\n")

            val end = if (item.isNull("exit")) now else item.optLong("exit", entry)
            val pauses = PointageStore.pauseDuration(item, end)
            if (pauses > 0L) {
                builder.append("⏸ Pauses : ").append(formatDuration(pauses)).append('\n')
            }

            if (!item.isNull("exit")) {
                builder.append("🔴 ")
                    .append(fullDateFormat.format(Date(end)))
                    .append("  SORTIE  ")
                    .append(formatDuration(PointageStore.workedDuration(item, end)))
                    .append(" travaillées\n")
            } else {
                builder.append("🟢 EN COURS  ")
                    .append(formatDuration(PointageStore.workedDuration(item, now)))
                    .append(" travaillées\n")
            }
            builder.append('\n')
        }
        return builder.toString().ifBlank { "Aucun historique." }
    }

    private fun buildAnalyticsText(): String {
        val data = PointageStore.load(this)
        val totals = LinkedHashMap<String, Long>()
        var total = 0L
        var sessions = 0

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            val exit = if (item.isNull("exit")) null else item.optLong("exit").takeIf { it > 0L }
            if (entry <= 0L || exit == null || exit < entry) continue

            val place = item.optString("zoneAddress").ifBlank { "Pointage manuel / ancien pointage" }
            val worked = PointageStore.workedDuration(item, exit)
            totals[place] = (totals[place] ?: 0L) + worked
            total += worked
            sessions++
        }

        return buildString {
            append("⏱ TOTAL TRAVAILLÉ : ").append(formatDuration(total)).append('\n')
            append("✅ Sessions terminées : ").append(sessions).append("\n\n")
            append("HEURES PAR ADRESSE\n\n")
            if (totals.isEmpty()) {
                append("Aucune donnée.")
            } else {
                totals.forEach { (place, duration) ->
                    append("📍 ").append(place).append("\n⏱ ")
                        .append(formatDuration(duration)).append("\n\n")
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun updateWidgets() {
        PointageWidgetProvider.updateAll(this)
        QuickActionsWidgetProvider.updateAll(this)
    }

    private fun showSettingsDialog() {
        showSettingsTab()
    }
}