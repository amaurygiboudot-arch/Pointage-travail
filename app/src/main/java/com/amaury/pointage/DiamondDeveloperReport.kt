package com.amaury.pointage

import android.content.Intent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rapport partageable du laboratoire des vrais boutons de pointage. */
object DiamondDeveloperReport {
    fun build(activity: MainActivity): String {
        val tuning = PrimaryDiamondLiveTuning.current(activity)
        val snapshot = CelestialStateStore.current()
        val ids = listOf("ENTRÉE" to R.id.entryButton, "PAUSE" to R.id.pauseButton, "SORTIE" to R.id.exitButton)
        val found = ids.map { (label, id) -> "$label=${activity.findViewById<View>(id)?.javaClass?.simpleName ?: "ABSENT"}" }
        val connectedCount = ids.count { (_, id) -> activity.findViewById<View>(id) is RedDiamondFinalButton }
        val values = PrimaryDiamondLiveTuning.values(tuning)
        val disconnected = values.filter { PrimaryDiamondLiveTuning.effectRoute(it.first) == "NON CONNECTÉ" }
        val last = PrimaryDiamondLiveTuning.lastSaveStatus(activity)
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.FRANCE).format(Date())

        return buildString {
            appendLine("HORATRACK — RAPPORT BOUTONS DIAMANT LIVE v1")
            appendLine("Généré : $date")
            appendLine("Moteur : ${RedDiamondFinalButton.RENDER_NAME}")
            appendLine("Boutons réels connectés : $connectedCount/3")
            appendLine(found.joinToString(" | "))
            appendLine("Réglages exposés : ${values.size}")
            appendLine("Réglages connectés : ${values.size - disconnected.size}/${values.size}")
            appendLine("Contrôle global : ${if (disconnected.isEmpty()) "OK — tous les réglages exposés ont une destination moteur/rendu" else "ERREUR — réglages non connectés présents"}")
            if (disconnected.isNotEmpty()) appendLine("NON CONNECTÉS : ${disconnected.joinToString { it.first }}")
            appendLine()
            appendLine("ENREGISTREMENT FORCÉ")
            if (last == null) appendLine("Aucune modification enregistrée depuis l'installation/réinitialisation.")
            else appendLine("OK=${last.ok} | clé=${last.key} | demandé=${fmt(last.requested)} | relu=${fmt(last.persisted)} | timestamp=${last.timestampMs}")
            appendLine()
            appendLine("ÉTAT CÉLESTE / MOUVEMENT")
            appendLine("Mode=${if (snapshot.isNight) "LUNE/NUIT" else "SOLEIL/JOUR"} | confiance position=${snapshot.locationConfidence}")
            appendLine("Soleil: disponible=${snapshot.sun.available}, intensité=${fmt(snapshot.sun.opticalIntensity)}, azimut=${fmt(snapshot.sun.azimuthDeg)}, altitude=${fmt(snapshot.sun.altitudeDeg)}")
            appendLine("Lune: disponible=${snapshot.moon.available}, intensité=${fmt(snapshot.moon.opticalIntensity)}, azimut=${fmt(snapshot.moon.azimuthDeg)}, altitude=${fmt(snapshot.moon.altitudeDeg)}")
            appendLine("Téléphone: azimut=${fmt(snapshot.orientation.azimuthDeg)}, pitch=${fmt(snapshot.orientation.pitchDeg)}, roll=${fmt(snapshot.orientation.rollDeg)}")
            appendLine("Position: ${snapshot.location?.let { "présente, précision=${it.accuracyMeters ?: -1f}m, fix=${it.fixTimeMs}" } ?: "absente"}")
            appendLine()
            appendLine("VALEURS ET DESTINATIONS")
            values.forEach { (key, value) -> appendLine("$key=${fmt(value)} | ${PrimaryDiamondLiveTuning.effectRoute(key)}") }
        }
    }

    fun share(activity: MainActivity) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Rapport HoraTrack — boutons diamant")
            putExtra(Intent.EXTRA_TEXT, build(activity))
        }
        activity.startActivity(Intent.createChooser(intent, "Partager le rapport des boutons"))
    }

    private fun fmt(value: Float) = if (value.isFinite()) String.format(Locale.FRANCE, "%.5f", value) else "NaN"
    private fun fmt(value: Double) = if (value.isFinite()) String.format(Locale.FRANCE, "%.5f", value) else "NaN"
}
