package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusCard: TextView
    private lateinit var historyText: TextView

    private val dateFormat =
        SimpleDateFormat("HH:mm", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        historyText = findViewById(R.id.historyText)

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

        refreshScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshScreen()
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

        historyText.text = builder.toString()
    }

    private fun formatDuration(ms: Long): String {

        val totalMinutes = ms / 60000
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
