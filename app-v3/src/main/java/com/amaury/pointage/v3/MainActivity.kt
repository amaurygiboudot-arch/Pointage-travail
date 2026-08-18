package com.amaury.pointage.v3

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private var entryAt: Long? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        historyText = findViewById(R.id.historyText)

        val entryButton = findViewById<Button>(R.id.entryButton)
        val exitButton = findViewById<Button>(R.id.exitButton)
        val manualButton = findViewById<Button>(R.id.manualButton)

        entryButton.setOnClickListener {
            if (entryAt == null) {
                entryAt = System.currentTimeMillis()
                refresh()
            }
        }

        exitButton.setOnClickListener {
            val start = entryAt ?: return@setOnClickListener
            val end = System.currentTimeMillis()
            historyText.text = "🟢 ${timeFormat.format(Date(start))}  ENTRÉE\n🔴 ${timeFormat.format(Date(end))}  SORTIE"
            entryAt = null
            refresh()
        }

        manualButton.setOnClickListener {
            historyText.text = "Mode manuel à migrer depuis la V2."
        }

        refresh()
    }

    private fun refresh() {
        val start = entryAt
        statusText.text = if (start == null) {
            "STATUT ACTUEL\n● AUCUNE ENTRÉE EN COURS"
        } else {
            "STATUT ACTUEL\n● ENTRÉE EN COURS\nDepuis ${timeFormat.format(Date(start))}"
        }
    }
}
