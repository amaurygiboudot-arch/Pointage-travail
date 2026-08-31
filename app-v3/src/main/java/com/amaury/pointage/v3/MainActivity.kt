package com.amaury.pointage.v3

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object { private const val REQUEST_COARSE_LOCATION = 301 }

    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private lateinit var entryButton: V3JewelButton
    private lateinit var exitButton: V3JewelButton
    private lateinit var sunView: V3SunIndicatorView

    private var entryAt: Long? = null
    private var lastLocation: Location? = null
    private var deviceHeading = 0f
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val handler = Handler(Looper.getMainLooper())

    private val sunTicker = object : Runnable {
        override fun run() {
            refreshSunPosition()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        historyText = findViewById(R.id.historyText)
        entryButton = findViewById(R.id.entryButton)
        exitButton = findViewById(R.id.exitButton)
        sunView = findViewById(R.id.sunIndicator)
        exitButton.isEntry = false

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

        ensureLocationForSun()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        V3LightController.attach(
            this,
            onAngle = { angle ->
                entryButton.lightAngle = angle
                exitButton.lightAngle = angle
            },
            onHeading = { heading ->
                deviceHeading = heading
                refreshSunPosition()
            }
        )
        handler.removeCallbacks(sunTicker)
        handler.post(sunTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(sunTicker)
        V3LightController.detach()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_COARSE_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadLastKnownLocation()
            else sunView.hideSun()
        }
    }

    private fun ensureLocationForSun() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            loadLastKnownLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_COARSE_LOCATION)
        }
    }

    private fun loadLastKnownLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastLocation = manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        refreshSunPosition()
    }

    private fun refreshSunPosition() {
        val location = lastLocation ?: run {
            sunView.hideSun()
            return
        }
        val sun = V3SolarPosition.calculate(System.currentTimeMillis(), location.latitude, location.longitude)
        sunView.updateSun(sun.azimuthDeg, sun.altitudeDeg, deviceHeading)
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
