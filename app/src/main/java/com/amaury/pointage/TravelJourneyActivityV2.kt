package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Travel journal only: does not clock in/out, classify salary, or follow the user
 * in the background. One explicit GPS sample per start/finish action.
 */
class TravelJourneyActivityV2 : Activity() {
    private lateinit var status: TextView
    private lateinit var history: LinearLayout
    private lateinit var startButton: Button
    private lateinit var finishButton: Button
    private lateinit var mapsButton: Button
    private lateinit var permissionButton: Button
    private val format = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = AppThemeCatalog.useDarkPalette(this)
        val colors = AppThemeCatalog.current(this)
        val foreground = if (dark) colors.darkText else colors.lightText
        val background = if (dark) colors.darkBackground else colors.lightBackground
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            setBackgroundColor(background)
        }
        val scroller = ScrollView(this).apply { addView(column) }
        setContentView(scroller)

        fun heading(text: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(foreground)
            setPadding(0, dp(10), 0, dp(8))
            column.addView(this)
        }
        fun action(text: String, onClick: () -> Unit): Button = Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = dp(52)
            setOnClickListener { onClick() }
            column.addView(this)
        }

        heading("DÉPLACEMENTS PROFESSIONNELS", 19f)
        heading("Journal séparé du pointage et de Salaire V2. L'enregistrement GPS est ponctuel ; aucune localisation continue ni distance réelle n'est calculée.")
        status = heading("Chargement…")
        startButton = action("DÉMARRER UN DÉPLACEMENT") { startJourney() }
        finishButton = action("TERMINER LE DÉPLACEMENT") { finishJourney() }
        permissionButton = action("AUTORISER LA POSITION GPS") { requestPositionPermission() }
        mapsButton = action("VOIR LE TRAJET DANS MAPS") { openMapWithConsent() }
        heading("HISTORIQUE DES TRAJETS", 17f)
        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(history)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::history.isInitialized) render()
    }

    private fun startJourney() {
        val employer = SalaryCompanyStore.readConfirmed(this).let { result ->
            if (result.reliable) result.companies.singleOrNull()?.id else null
        }
        val at = System.currentTimeMillis()
        val id = TravelJourneyStoreV2.begin(this, at, employer)
        if (id == null) {
            Toast.makeText(this, "Départ non enregistré : trajet ou journal à vérifier", Toast.LENGTH_LONG).show()
        } else {
            requestPositionFor(id, isDeparture = true, eventAtMs = at)
            Toast.makeText(this, "Départ enregistré", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun finishJourney() {
        val at = System.currentTimeMillis()
        val id = TravelJourneyStoreV2.finish(this, at)
        if (id == null) {
            Toast.makeText(this, "Arrivée non enregistrée : trajet ou journal à vérifier", Toast.LENGTH_LONG).show()
        } else {
            requestPositionFor(id, isDeparture = false, eventAtMs = at)
            Toast.makeText(this, "Arrivée enregistrée", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun hasPositionPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPositionPermission() {
        if (hasPositionPermission()) {
            Toast.makeText(this, "Position GPS autorisée", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ), 802)
    }

    private fun requestPositionFor(id: String, isDeparture: Boolean, eventAtMs: Long) {
        // Account changes while the GPS provider is responding must never reassign a sample.
        val ownerAtRequest = V2RuntimeAccountScope.currentScope() ?: return
        if (!hasPositionPermission()) {
            Toast.makeText(this, "Heure conservée ; autorise le GPS pour les prochains trajets", Toast.LENGTH_LONG).show()
            return
        }
        val source = CancellationTokenSource()
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, source.token)
                .addOnSuccessListener { position ->
                    if (!travelGpsAccountStillCurrentV2(ownerAtRequest, V2RuntimeAccountScope.currentScope()))
                        return@addOnSuccessListener
                    if (position == null || !position.hasAccuracy() ||
                        abs(position.time - eventAtMs) > 180_000L) return@addOnSuccessListener
                    val fix = TravelFixV2(
                        position.latitude, position.longitude,
                        position.accuracy.toDouble(), position.time
                    )
                    if (fix.isUsable()) {
                        TravelJourneyStoreV2.attachFix(this, id, isDeparture, fix)
                    }
                    if (!isFinishing) render()
                }
                .addOnFailureListener {
                    if (!isFinishing) render()
                }
        } catch (_: SecurityException) {
            // The user's recorded action survives GPS permission changes.
        }
    }

    private fun selectedTrip(): TravelJourneyV2? =
        TravelJourneyStoreV2.read(this)?.lastOrNull()

    private fun mapsUrl(trip: TravelJourneyV2?): String {
        val departure = trip?.departure
        val arrival = trip?.arrival
        return if (departure != null && arrival != null) {
            "https://www.google.com/maps/dir/?api=1&origin=" +
                "${departure.latitude},${departure.longitude}&destination=" +
                "${arrival.latitude},${arrival.longitude}&travelmode=driving"
        } else if (departure != null || arrival != null) {
            val location = requireNotNull(departure ?: arrival)
            "https://www.google.com/maps/search/?api=1&query=" +
                "${location.latitude},${location.longitude}"
        } else {
            "https://www.google.com/maps/@?api=1&map_action=map"
        }
    }

    private fun openMapWithConsent() {
        val link = mapsUrl(selectedTrip())
        AlertDialog.Builder(this)
            .setTitle("Ouvrir Google Maps ?")
            .setMessage("Maps recevra les coordonnées enregistrées uniquement si tu confirmes. Un itinéraire proposé ne représente pas les kilomètres réellement parcourus.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Ouvrir") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                catch (_: Exception) {
                    Toast.makeText(this, "Aucune application de cartes disponible", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun render() {
        val journal = TravelJourneyStoreV2.read(this)
        val reliable = journal != null
        val open = journal?.singleOrNull { it.endedAtMs == null }
        status.text = when {
            !reliable -> "Journal indisponible ou à vérifier. Aucun trajet écrasé."
            open != null -> "Déplacement depuis ${format.format(Date(open.startedAtMs))}"
            else -> "Aucun déplacement en cours"
        }
        startButton.isEnabled = reliable && open == null
        finishButton.isEnabled = reliable && open != null
        mapsButton.isEnabled = reliable
        permissionButton.isEnabled = !hasPositionPermission()
        history.removeAllViews()
        journal?.asReversed()?.take(30)?.forEach { trip ->
            val state = trip.endedAtMs?.let { format.format(Date(it)) } ?: "en cours"
            val gps = when {
                trip.departure != null && trip.arrival != null -> "positions départ et arrivée présentes"
                trip.departure != null || trip.arrival != null -> "position partielle"
                else -> "position non disponible"
            }
            history.addView(TextView(this).apply {
                text = "${format.format(Date(trip.startedAtMs))} → $state\nGPS : $gps"
                textSize = 14f
                val theme = AppThemeCatalog.current(this@TravelJourneyActivityV2)
                setTextColor(if (AppThemeCatalog.useDarkPalette(this@TravelJourneyActivityV2))
                    theme.darkText else theme.lightText)
                setPadding(0, dp(8), 0, dp(9))
            })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
