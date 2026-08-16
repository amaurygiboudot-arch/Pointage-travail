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
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var statusCard: TextView
    private lateinit var historyText: TextView
    private lateinit var contentTitle: TextView
    private lateinit var clockDigital: TextClock
    private lateinit var pointageButtons: LinearLayout
    private lateinit var gpsSettingsPanel: LinearLayout

    private lateinit var workplaceAddress: EditText
    private lateinit var geofenceRadius: EditText
    private lateinit var autoGpsSwitch: Switch
    private lateinit var gpsStatusText: TextView

    private lateinit var tabToday: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabAnalytics: TextView
    private lateinit var tabSettings: TextView

    private var activeTab = "today"

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

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

        workplaceAddress = findViewById(R.id.workplaceAddress)
        geofenceRadius = findViewById(R.id.geofenceRadius)
        autoGpsSwitch = findViewById(R.id.autoGpsSwitch)
        gpsStatusText = findViewById(R.id.gpsStatusText)

        tabToday = findViewById(R.id.tabToday)
        tabHistory = findViewById(R.id.tabHistory)
        tabAnalytics = findViewById(R.id.tabAnalytics)
        tabSettings = findViewById(R.id.tabSettings)

        val settingsButton = findViewById<Button>(R.id.settingsButton)
        val entryButton = findViewById<Button>(R.id.entryButton)
        val exitButton = findViewById<Button>(R.id.exitButton)
        val saveGpsSettingsButton = findViewById<Button>(R.id.saveGpsSettingsButton)
        val locationPermissionButton = findViewById<Button>(R.id.locationPermissionButton)

        loadGpsSettings()

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

        tabToday.setOnClickListener { showTodayTab() }
        tabHistory.setOnClickListener { showHistoryTab() }
        tabAnalytics.setOnClickListener { showAnalyticsTab() }
        tabSettings.setOnClickListener { showSettingsTab() }

        showTodayTab()
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
        gpsSettingsPanel.visibility = View.GONE
        contentTitle.text = "HEURES PAR LIEU"
        historyText.text = buildAnalyticsText()
    }

    private fun showSettingsTab() {
        activeTab = "settings"
        setActiveTab(tabSettings)
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        historyText.visibility = View.GONE
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
        val enabled = autoGpsSwitch.isChecked

        gpsPrefs.edit()
            .putString("address", addresses.joinToString("\n"))
            .putInt("radius", radius)
            .putBoolean("enabled", enabled)
            .apply()

        if (!enabled) {
            GeofenceManager.remove(this)
            gpsPrefs.edit().remove("active_zones").apply()
            updateGpsStatus()
            Toast.makeText(this, "Pointage automatique désactivé", Toast.LENGTH_SHORT).show()
            return
        }

        if (!GeofenceManager.hasRequiredPermissions(this)) {
            updateGpsStatus()
            requestLocationAccess()
            return
        }

        gpsStatusText.text = "Recherche des ${addresses.size} adresse(s)…"
        val existingZones = loadSavedZoneObjects()

        Thread {
            val zones = mutableListOf<WorkZone>()
            val zonesJson = JSONArray()
            val failedAddresses = mutableListOf<String>()
            val geocoder = Geocoder(this, Locale.FRANCE)

            addresses.forEach { address ->
                try {
                    val location = geocoder.getFromLocationName(address, 1)?.firstOrNull()
                    if (location == null) {
                        failedAddresses.add(address)
                    } else {
                        val stableId = existingZoneIdForAddress(address, existingZones)
                            ?: "workplace_${UUID.randomUUID()}"

                        val zone = WorkZone(
                            id = stableId,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            radius = radius.toFloat()
                        )
                        zones.add(zone)
                        zonesJson.put(
                            JSONObject()
                                .put("id", zone.id)
                                .put("address", address)
                                .put("latitude", zone.latitude)
                                .put("longitude", zone.longitude)
                                .put("radius", zone.radius.toDouble())
                        )
                    }
                } catch (_: Exception) {
                    failedAddresses.add(address)
                }
            }

            runOnUiThread {
                if (zones.isEmpty()) {
                    gpsStatusText.text = "Aucune adresse n'a pu être localisée"
                    Toast.makeText(this, "Vérifie les adresses et ta connexion", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                gpsPrefs.edit()
                    .putString("zones", zonesJson.toString())
                    .remove("latitude")
                    .remove("longitude")
                    .remove("active_zones")
                    .apply()

                GeofenceManager.registerAll(this, zones) { success, message ->
                    runOnUiThread {
                        gpsStatusText.text = if (success) {
                            "● ${zones.size} lieu(x) GPS actif(s) — rayon ${radius} m"
                        } else {
                            message
                        }

                        val extra = if (failedAddresses.isNotEmpty()) {
                            " — ${failedAddresses.size} adresse(s) introuvable(s)"
                        } else {
                            ""
                        }
                        Toast.makeText(this, message + extra, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun requestLocationAccess() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1001
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Localisation en arrière-plan")
                .setMessage("Pour pointer automatiquement quand tu arrives ou quittes un des lieux enregistrés, choisis l'autorisation de localisation « Toujours autoriser » dans les paramètres Android.")
                .setPositiveButton("Ouvrir les paramètres") { _, _ -> openAppSettings() }
                .setNegativeButton("Plus tard", null)
                .show()
            return
        }

        Toast.makeText(this, "Localisation autorisée", Toast.LENGTH_SHORT).show()
        tryRestoreGeofence()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationAccess()
        } else if (requestCode == 1001) {
            Toast.makeText(this, "La localisation est nécessaire pour le pointage automatique", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryRestoreGeofence() {
        if (!gpsPrefs.getBoolean("enabled", false)) return
        if (!GeofenceManager.hasRequiredPermissions(this)) return

        val zones = loadSavedZones()
        if (zones.isNotEmpty()) {
            GeofenceManager.registerAll(this, zones)
            return
        }

        if (gpsPrefs.contains("latitude") && gpsPrefs.contains("longitude")) {
            val latitude = java.lang.Double.longBitsToDouble(gpsPrefs.getLong("latitude", 0L))
            val longitude = java.lang.Double.longBitsToDouble(gpsPrefs.getLong("longitude", 0L))
            val radius = gpsPrefs.getInt("radius", 150).toFloat()
            GeofenceManager.register(this, latitude, longitude, radius)
        }
    }

    private fun loadSavedZones(): List<WorkZone> {
        return try {
            val array = loadSavedZoneObjects()
            val zones = mutableListOf<WorkZone>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                zones.add(
                    WorkZone(
                        id = item.getString("id"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        radius = item.getDouble("radius").toFloat()
                    )
                )
            }
            zones
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun updateGpsStatus() {
        if (!gpsPrefs.getBoolean("enabled", false)) {
            gpsStatusText.text = "GPS automatique désactivé"
            return
        }

        if (!GeofenceManager.hasRequiredPermissions(this)) {
            gpsStatusText.text = "⚠ Autorisation « Toujours autoriser » nécessaire"
            return
        }

        val count = loadSavedZones().size
        gpsStatusText.text = if (count > 0) {
            "● GPS automatique configuré sur $count lieu(x)"
        } else {
            "● GPS autorisé — enregistre tes adresses"
        }
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Paramètres Android")
            .setMessage("Ouvre les paramètres Android de Pointage Travail pour gérer les autorisations, notamment la localisation.")
            .setPositiveButton("Ouvrir") { _, _ -> openAppSettings() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun refreshScreen() {
        val data = PointageStore.load(this)

        if (PointageStore.hasOpen(this)) {
            val last = data.getJSONObject(data.length() - 1)
            val entry = last.getLong("entry")
            val place = last.optString("zoneAddress").takeIf { it.isNotBlank() }
            statusCard.text = if (place != null) {
                "STATUT ACTUEL\n🟢 ENTRÉE EN COURS\nDepuis ${dateFormat.format(Date(entry))}\n📍 $place"
            } else {
                "STATUT ACTUEL\n🟢 ENTRÉE EN COURS\nDepuis ${dateFormat.format(Date(entry))}"
            }
        } else {
            statusCard.text = "STATUT ACTUEL\n⚪ Aucune entrée en cours"
        }

        historyText.text = if (data.length() == 0) {
            "Aucun pointage aujourd'hui."
        } else {
            buildHistoryText()
        }
    }

    private fun buildHistoryText(): String {
        val data = PointageStore.load(this)
        if (data.length() == 0) return "Aucun pointage enregistré."

        val builder = StringBuilder()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val entry = item.getLong("entry")
            val place = item.optString("zoneAddress").takeIf { it.isNotBlank() }

            if (place != null) {
                builder.append("📍 $place\n")
            } else {
                builder.append("📍 Pointage manuel / ancien pointage\n")
            }

            builder.append("🟢  ${dateFormat.format(Date(entry))}   ENTRÉE")

            if (!item.isNull("exit")) {
                val exit = item.getLong("exit")
                builder.append("\n🔴  ${dateFormat.format(Date(exit))}   SORTIE   ${formatDuration(exit - entry)}")
            } else {
                builder.append("   EN COURS")
            }
            builder.append("\n\n")
        }
        return builder.toString()
    }

    private fun buildAnalyticsText(): String {
        val data = PointageStore.load(this)
        val totalsByPlace = LinkedHashMap<String, Long>()
        val sessionsByPlace = LinkedHashMap<String, Int>()

        val savedZones = loadSavedZoneObjects()
        for (i in 0 until savedZones.length()) {
            val address = savedZones.optJSONObject(i)?.optString("address")?.trim().orEmpty()
            if (address.isNotEmpty()) {
                totalsByPlace.putIfAbsent(address, 0L)
                sessionsByPlace.putIfAbsent(address, 0)
            }
        }

        var grandTotal = 0L
        var completedSessions = 0

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            if (item.isNull("exit")) continue

            val entry = item.getLong("entry")
            val exit = item.getLong("exit")
            val duration = (exit - entry).coerceAtLeast(0L)
            val place = item.optString("zoneAddress").takeIf { it.isNotBlank() }
                ?: "Pointage manuel / ancien pointage"

            totalsByPlace[place] = (totalsByPlace[place] ?: 0L) + duration
            sessionsByPlace[place] = (sessionsByPlace[place] ?: 0) + 1
            grandTotal += duration
            completedSessions++
        }

        if (totalsByPlace.isEmpty() && completedSessions == 0) {
            return "Aucune donnée disponible pour le moment."
        }

        val builder = StringBuilder()
        builder.append("⏱ TOTAL : ${formatDuration(grandTotal)}\n")
        builder.append("✅ Sessions terminées : $completedSessions\n\n")
        builder.append("HEURES PAR ADRESSE\n\n")

        totalsByPlace.forEach { (place, total) ->
            val count = sessionsByPlace[place] ?: 0
            builder.append("📍 $place\n")
            builder.append("   ⏱ ${formatDuration(total)}")
            builder.append("   •   $count session(s)\n\n")
        }

        return builder.toString()
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.FRANCE, "%02dh %02dm", hours, minutes)
    }
}
