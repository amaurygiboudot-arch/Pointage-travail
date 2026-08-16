package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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

    private lateinit var tabToday: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabAnalytics: TextView
    private lateinit var tabSettings: TextView

    private var activeTab = "today"

    private val dateFormat =
        SimpleDateFormat("HH:mm", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        historyText = findViewById(R.id.historyText)
        contentTitle = findViewById(R.id.contentTitle)
        clockDigital = findViewById(R.id.clockDigital)
        pointageButtons = findViewById(R.id.pointageButtons)

        tabToday = findViewById(R.id.tabToday)
        tabHistory = findViewById(R.id.tabHistory)
        tabAnalytics = findViewById(R.id.tabAnalytics)
        tabSettings = findViewById(R.id.tabSettings)

        val settingsButton = findViewById<Button>(R.id.settingsButton)
        val entryButton = findViewById<Button>(R.id.entryButton)
        val exitButton = findViewById<Button>(R.id.exitButton)

        settingsButton.setOnClickListener {
            animateClick(settingsButton)
            showSettingsDialog()
        }

        entryButton.setOnClickListener {
            animateClick(entryButton)

            if (PointageStore.entry(this)) {
                Toast.makeText(
                    this,
                    "Entrée enregistrée",
                    Toast.LENGTH_SHORT
                ).show()

                PointageWidgetProvider.updateAll(this)
                refreshScreen()
            } else {
                Toast.makeText(
                    this,
                    "Une entrée est déjà en cours",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        exitButton.setOnClickListener {
            animateClick(exitButton)

            if (PointageStore.exit(this)) {
                Toast.makeText(
                    this,
                    "Sortie enregistrée",
                    Toast.LENGTH_SHORT
                ).show()

                PointageWidgetProvider.updateAll(this)
                refreshScreen()
            } else {
                Toast.makeText(
                    this,
                    "Aucune entrée en cours",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        tabToday.setOnClickListener { showTodayTab() }
        tabHistory.setOnClickListener { showHistoryTab() }
        tabAnalytics.setOnClickListener { showAnalyticsTab() }
        tabSettings.setOnClickListener { showSettingsTab() }

        showTodayTab()
    }

    override fun onResume() {
        super.onResume()

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
        contentTitle.text = "HISTORIQUE DU JOUR"
        refreshScreen()
    }

    private fun showHistoryTab() {
        activeTab = "history"
        setActiveTab(tabHistory)
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        contentTitle.text = "HISTORIQUE COMPLET"
        historyText.text = buildHistoryText()
    }

    private fun showAnalyticsTab() {
        activeTab = "analytics"
        setActiveTab(tabAnalytics)
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        contentTitle.text = "ANALYSES"
        historyText.text = buildAnalyticsText()
    }

    private fun showSettingsTab() {
        activeTab = "settings"
        setActiveTab(tabSettings)
        clockDigital.visibility = View.GONE
        statusCard.visibility = View.GONE
        pointageButtons.visibility = View.GONE
        contentTitle.text = "PARAMÈTRES"
        historyText.text =
            "⚙ Paramètres de Pointage Travail\n\n" +
            "Utilise la roue dentée en haut à droite pour ouvrir les paramètres Android de l'application.\n\n" +
            "Tu peux y gérer les autorisations, les notifications et les informations de l'application."
    }

    private fun setActiveTab(active: TextView) {
        val gold = getColor(R.color.hp_gold)
        val grey = getColor(R.color.hp_grey)

        tabToday.setTextColor(if (active == tabToday) gold else grey)
        tabHistory.setTextColor(if (active == tabHistory) gold else grey)
        tabAnalytics.setTextColor(if (active == tabAnalytics) gold else grey)
        tabSettings.setTextColor(if (active == tabSettings) gold else grey)
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Paramètres")
            .setMessage("Tu peux ouvrir les paramètres Android de Pointage Travail pour gérer les notifications, le stockage et les autorisations de l'application.")
            .setPositiveButton("Ouvrir") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun refreshScreen() {

        val data = PointageStore.load(this)

        if (PointageStore.hasOpen(this)) {
            val last = data.getJSONObject(data.length() - 1)
            val entry = last.getLong("entry")

            statusCard.text =
                "STATUT ACTUEL\n🟢 ENTRÉE EN COURS\nDepuis ${dateFormat.format(Date(entry))}"
        } else {
            statusCard.text =
                "STATUT ACTUEL\n⚪ Aucune entrée en cours"
        }

        if (data.length() == 0) {
            historyText.text = "Aucun pointage aujourd'hui."
            return
        }

        historyText.text = buildHistoryText()
    }

    private fun buildHistoryText(): String {
        val data = PointageStore.load(this)

        if (data.length() == 0) {
            return "Aucun pointage enregistré."
        }

        val builder = StringBuilder()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val entry = item.getLong("entry")
            val entryText = dateFormat.format(Date(entry))

            builder.append("🟢  ")
            builder.append(entryText)
            builder.append("   ENTRÉE")

            if (!item.isNull("exit")) {
                val exit = item.getLong("exit")
                val exitText = dateFormat.format(Date(exit))

                builder.append("\n🔴  ")
                builder.append(exitText)
                builder.append("   SORTIE")

                val duration = exit - entry
                builder.append("   ")
                builder.append(formatDuration(duration))
            } else {
                builder.append("   EN COURS")
            }

            builder.append("\n\n")
        }

        return builder.toString()
    }

    private fun buildAnalyticsText(): String {
        val data = PointageStore.load(this)

        if (data.length() == 0) {
            return "Aucune donnée disponible pour le moment."
        }

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

        val averageDuration =
            if (completedSessions > 0) totalDuration / completedSessions else 0L

        return "📊 Nombre de pointages : ${data.length()}\n\n" +
            "✅ Sessions terminées : $completedSessions\n\n" +
            "⏱ Temps total : ${formatDuration(totalDuration)}\n\n" +
            "📈 Durée moyenne : ${formatDuration(averageDuration)}"
    }

    private fun formatDuration(ms: Long): String {

        val totalMinutes = ms.coerceAtLeast(0L) / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return String.format(
            Locale.FRANCE,
            "%02dh %02dm",
            hours,
            minutes
        )
    }
}
