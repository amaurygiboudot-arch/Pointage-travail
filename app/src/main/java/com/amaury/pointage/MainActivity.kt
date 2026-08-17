package com.amaury.pointage

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

        settingsButton.setOnClickListener {
            animateClick(settingsButton)
            showSettingsDialog()
        }

        entryButton.setOnClickListener {
            animateClick(entryButton)
            if (PointageStore.entry(this)) {
                Toast.makeText(this, "Entrée enregistrée", Toast.LENGTH_SHORT).show()
                PointageWidgetProvider.updateAll(this)
                refreshScreen()
            } else {
                Toast.makeText(this, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
            }
        }

        exitButton.setOnClickListener {
            animateClick(exitButton)
            if (PointageStore.exit(this)) {
                Toast.makeText(this, "Sortie enregistrée", Toast.LENGTH_SHORT).show()
                PointageWidgetProvider.updateAll(this)
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
        val gold = getColor(R.color.hp_gold)
        val grey = getColor(R.color.hp_grey)
        tabToday.setTextColor(if (active == tabToday) gold else grey)
        tabHistory.setTextColor(if (active == tabHistory) gold else grey)
        tabAnalytics.setTextColor(if (active == tabAnalytics) gold else grey)
        tabSettings.setTextColor(if (active == tabSettings) gold else grey)
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
        autoGpsSwitch.isChecked = gpsPrefs.getBoolean("enabled", false)
    }

    private fun loadSavedZoneObjects(): JSONArray {
        return try {
            JSONArray(gpsPrefs.getString("zones", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

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
            .distinct()

        if (rawLines.isEmpty()) {
            Toast.makeText(this, "Entre au moins une adresse", Toast.LENGTH_LONG).show()
            return
        }

        val addresses = rawLines.take(10)
        if (rawLines.size > 10) {
            Toast.makeText(this, "Seules les 10 premières adresses seront enregistrées", Toast.LENGTH_LONG).show()
        }

        val radius = geofenceRadius.text.toString().toIntOrNull()?.coerceIn(50, 1000) ?: 150
        val existingZones = loadSavedZoneObjects()
        val zones = JSONArray()
        val workZones = mutableListOf<WorkZone>()
        val geocoder = Geocoder(this, Locale.FRANCE)

        addresses.forEach { address ->
            val result = runCatching { geocoder.getFromLocationName(address, 1) }.getOrNull()?.firstOrNull()
            if (result != null) {
                val id = existingZoneIdForAddress(address, existingZones) ?: UUID.randomUUID().toString()
                val item = JSONObject()
                    .put("id", id)
                    .put("address", address)
                    .put("latitude", result.latitude)
                    .put("longitude", result.longitude)
                    .put("radius", radius)
                zones.put(item)
                workZones += WorkZone(id, result.latitude, result.longitude, radius.toFloat())
            }
        }

        gpsPrefs.edit()
            .putString("address", addresses.joinToString("\n"))
            .putInt("radius", radius)
            .putBoolean("enabled", autoGpsSwitch.isChecked)
            .putString("zones", zones.toString())
            .apply()

        if (autoGpsSwitch.isChecked && workZones.isNotEmpty()) {
            if (GeofenceManager.hasRequiredPermissions(this)) {
                GeofenceManager.registerAll(this, workZones)
                Toast.makeText(this, "Lieux GPS enregistrés", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Autorise d'abord la localisation", Toast.LENGTH_LONG).show()
            }
        } else {
            GeofenceManager.unregisterAll(this)
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
        }
        updateGpsStatus()
    }

    private fun requestLocationAccess() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            Toast.makeText(this, "Localisation déjà autorisée", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(missing.toTypedArray(), 3001)
        }
    }

    private fun tryRestoreGeofence() {
        if (!gpsPrefs.getBoolean("enabled", false)) return
        if (!GeofenceManager.hasRequiredPermissions(this)) return
        val zonesJson = gpsPrefs.getString("zones", "[]") ?: "[]"
        val array = runCatching { JSONArray(zonesJson) }.getOrElse { JSONArray() }
        val zones = mutableListOf<WorkZone>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            zones += WorkZone(
                item.optString("id"),
                item.optDouble("latitude"),
                item.optDouble("longitude"),
                item.optDouble("radius", 150.0).toFloat()
            )
        }
        if (zones.isNotEmpty()) GeofenceManager.registerAll(this, zones)
    }

    private fun updateGpsStatus() {
        gpsStatusText.text = when {
            !autoGpsSwitch.isChecked -> "GPS automatique désactivé"
            !GeofenceManager.hasRequiredPermissions(this) -> "Localisation à autoriser"
            else -> "GPS automatique actif"
        }
    }

    private fun refreshScreen() {
        val data = PointageStore.load(this)
        var openEntry: Long? = null
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit")) {
                openEntry = item.optLong("entry").takeIf { it > 0L }
                break
            }
        }

        statusCard.text = if (openEntry != null) {
            "STATUT ACTUEL\n● ENTRÉE EN COURS\nDepuis ${dateFormat.format(Date(openEntry))}"
        } else {
            "STATUT ACTUEL\n○ Aucune entrée en cours"
        }
        historyText.text = buildTodayHistoryText()
    }

    private fun buildTodayHistoryText(): String {
        val data = PointageStore.load(this)
        val today = Calendar.getInstance(Locale.FRANCE)
        val builder = StringBuilder()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
            if (cal.get(Calendar.YEAR) != today.get(Calendar.YEAR) || cal.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) continue
            val place = item.optString("zoneAddress").ifBlank { "Pointage manuel / ancien pointage" }
            builder.append("📍 ").append(place).append("\n")
            builder.append("🟢 ").append(dateFormat.format(Date(entry))).append("  ENTRÉE\n")
            if (!item.isNull("exit")) {
                val exit = item.optLong("exit")
                builder.append("🔴 ").append(dateFormat.format(Date(exit))).append("  SORTIE  ").append(formatDuration(exit - entry)).append("\n")
            } else builder.append("🟢 EN COURS\n")
            builder.append("\n")
        }
        return builder.toString().ifBlank { "Aucun pointage aujourd'hui." }
    }

    private fun buildHistoryText(): String {
        val data = PointageStore.load(this)
        val builder = StringBuilder()
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            val place = item.optString("zoneAddress").ifBlank { "Pointage manuel / ancien pointage" }
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(entry))
            builder.append("📍 ").append(place).append("\n")
            builder.append("🟢 ").append(date).append("  ENTRÉE\n")
            if (!item.isNull("exit")) {
                val exit = item.optLong("exit")
                builder.append("🔴 ").append(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(exit)))
                    .append("  SORTIE  ").append(formatDuration(exit - entry)).append("\n")
            } else builder.append("🟢 EN COURS\n")
            builder.append("\n")
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
            val place = item.optString("zoneAddress").ifBlank { "Pointage manuel / ancien pointage" }
            if (entry > 0L && exit != null) {
                val duration = (exit - entry).coerceAtLeast(0L)
                totals[place] = (totals[place] ?: 0L) + duration
                total += duration
                sessions++
            }
        }
        val b = StringBuilder()
        b.append("⏱ TOTAL : ").append(formatDuration(total)).append("\n")
        b.append("✅ Sessions terminées : ").append(sessions).append("\n\n")
        b.append("HEURES PAR ADRESSE\n\n")
        if (totals.isEmpty()) b.append("Aucune donnée.") else totals.forEach { (place, duration) ->
            b.append("📍 ").append(place).append("\n⏱ ").append(formatDuration(duration)).append("\n\n")
        }
        return b.toString()
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60, totalMinutes % 60)
    }

    private fun showSettingsDialog() {
        showSettingsTab()
    }
}
