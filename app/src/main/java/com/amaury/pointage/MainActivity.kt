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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val dateFormat =
        SimpleDateFormat("HH:mm", Locale.FRANCE)

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
        contentTitle.text = "ANALYSES"
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
        contentTitle.text = "PARAMÈTRES GPS"
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
        geofenceRadius.setText(gpsPrefs.getInt("radius", 150).toString())
        autoGpsSwitch.isChecked = gpsPrefs.getBoolean("enabled", false)
    }

    private fun saveGpsSettings() {
        val address = workplaceAddress.text.toString().trim()
        val radius = geofenceRadius.text.toString().toIntOrNull()?.coerceIn(50, 1000) ?: 150
        val enabled = autoGpsSwitch.isChecked

        if (address.isEmpty()) {
            Toast.makeText(this, "Entre d'abord l'adresse du lieu de travail", Toast.LENGTH_LONG).show()
            return
        }

        gpsPrefs.edit()
            .putString("address", address)
            .putInt("radius", radius)
            .putBoolean("enabled", enabled)
            .apply()

        if (!enabled) {
            GeofenceManager.remove(this)
            updateGpsStatus()
            Toast.makeText(this, "Pointage automatique désactivé", Toast.LENGTH_SHORT).show()
            return
        }

        if (!GeofenceManager.hasRequiredPermissions(this)) {
            updateGpsStatus()
            requestLocationAccess()
            return
        }

        gpsStatusText.text = "Recherche de l'adresse…"

        Thread {
            try {
                val results = Geocoder(this, Locale.FRANCE).getFromLocationName(address, 1)
                val location = results?.firstOrNull()

                runOnUiThread {
                    if (location == null) {
                        gpsStatusText.text = "Adresse introuvable"
                        Toast.makeText(this, "Impossible de trouver cette adresse", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    gpsPrefs.edit()
                        .putLong("latitude", java.lang.Double.doubleToRawLongBits(location.latitude))
                        .putLong("longitude", java.lang.Double.doubleToRawLongBits(location.longitude))
                        .apply()

                    GeofenceManager.register(
                        this,
                        location.latitude,
                        location.longitude,
                        radius.toFloat()
                    ) { success, message ->
                        runOnUiThread {
                            gpsStatusText.text = if (success) {
                                "● GPS automatique actif — rayon ${radius} m"
                            } else {
                                message
                            }
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    gpsStatusText.text = "Erreur lors de la recherche de l'adresse"
                    Toast.makeText(this, "Vérifie l'adresse et ta connexion", Toast.LENGTH_LONG).show()
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
                .setMessage("Pour pointer automatiquement quand tu arrives ou quittes le travail, choisis l'autorisation de localisation « Toujours autoriser » dans les paramètres Android.")
                .setPositiveButton("Ouvrir les paramètres") { _, _ ->
                    openAppSettings()
                }
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
        if (!gpsPrefs.contains("latitude") || !gpsPrefs.contains("longitude")) return

        val latitude = java.lang.Double.longBitsToDouble(gpsPrefs.getLong("latitude", 0L))
        val longitude = java.lang.Double.longBitsToDouble(gpsPrefs.getLong("longitude", 0L))
        val radius = gpsPrefs.getInt("radius", 150).toFloat()
        GeofenceManager.register(this, latitude, longitude, radius)
    }

    private fun updateGpsStatus() {
        if (!gpsPrefs.getBoolean("enabled", false)) {
            gpsStatusText.text = "GPS automatique désactivé"
            return
        }

        gpsStatusText.text = if (GeofenceManager.hasRequiredPermissions(this)) {
            "● GPS automatique configuré"
        } else {
            "⚠ Autorisation « Toujours autoriser » nécessaire"
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
            statusCard.text = "STATUT ACTUEL\n🟢 ENTRÉE EN COURS\nDepuis ${dateFormat.format(Date(entry))}"
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
        if (data.length() == 0) return "Aucune donnée disponible pour le moment."

        var completedSessions = 0
        var totalDuration = 0L

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            if (!item.isNull("exit")) {
                val entry = item.getLong("entry")
                val exit = item.getLong("exit")
                totalDuration += (exit - entry).coerceAtLeast(0L)
                completedSessions++
            }
        }

        val averageDuration = if (completedSessions > 0) totalDuration / completedSessions else 0L
        return "📊 Nombre de pointages : ${data.length()}\n\n" +
            "✅ Sessions terminées : $completedSessions\n\n" +
            "⏱ Temps total : ${formatDuration(totalDuration)}\n\n" +
            "📈 Durée moyenne : ${formatDuration(averageDuration)}"
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.FRANCE, "%02dh %02dm", hours, minutes)
    }
}
