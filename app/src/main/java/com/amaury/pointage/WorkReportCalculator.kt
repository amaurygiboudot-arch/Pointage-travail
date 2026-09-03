package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Source unique des calculs utilisés par les relevés PDF mensuels et annuels. */
object WorkReportCalculator {
    data class Day(
        val dayStart: Long,
        val dateLabel: String,
        val shiftId: String,
        val shiftLabel: String,
        val firstArrival: Long,
        val firstCountedEntry: Long,
        val lastExit: Long,
        val presenceMs: Long,
        val unpaidPauseMs: Long,
        val paidTeamPauseMs: Long,
        val paidWorkMs: Long,
        val mealCount: Int,
        val places: List<String>,
        val sessions: Int,
        val manual: Boolean
    )

    fun month(context: Context, data: JSONArray, year: Int, month: Int): List<Day> {
        val grouped = linkedMapOf<Long, MutableList<JSONObject>>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || item.isNull("exit")) continue
            val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
            if (c.get(Calendar.YEAR) != year || c.get(Calendar.MONTH) != month) continue
            grouped.getOrPut(dayStart(entry)) { mutableListOf() }.add(item)
        }
        return grouped.entries.sortedBy { it.key }.map { (day, sessions) -> buildDay(context, day, sessions) }
    }

    fun year(context: Context, data: JSONArray, year: Int): List<Day> =
        (0..11).flatMap { month(context, data, year, it) }

    private fun buildDay(context: Context, dayStart: Long, items: List<JSONObject>): Day {
        val sorted = items.sortedBy { it.optLong("entry", Long.MAX_VALUE) }
        val first = sorted.first()
        // Les anciens profils de poste ne pilotent plus aucun calcul. Un identifiant déjà
        // enregistré reste seulement affichable pour ne pas réécrire l'historique.
        val shiftId = first.optString("shiftType").trim()
        val shiftLabel = when (shiftId) {
            "morning" -> "Matin"
            "day" -> "Journée"
            "afternoon" -> "Après-midi"
            "night" -> "Nuit"
            else -> "Non défini"
        }
        val teamShift = shiftId == "morning" || shiftId == "afternoon" || shiftId == "night"
        var presence = 0L
        var unpaid = 0L
        var paidTeamPause = 0L
        var paidWork = 0L
        var meals = 0
        val places = linkedSetOf<String>()
        var manual = false

        sorted.forEach { item ->
            val entry = item.optLong("entry", -1L)
            val exit = item.optLong("exit", -1L)
            if (entry <= 0L || exit <= entry) return@forEach
            val rawPresence = exit - entry
            presence += rawPresence

            val storedPauseMinutes = item.optInt("autoPauseMinutes", 0).coerceIn(0, 240)
            val configured = storedPauseMinutes * 60_000L
            val actualPause = actualPauseMs(item, entry, exit)

            if (teamShift) {
                // Matin / Après-midi / Nuit : la pause d'équipe est rémunérée.
                val paid = if (configured > 0L) configured else actualPause.coerceAtMost(30L * 60_000L)
                paidTeamPause += paid
                val extraUnpaid = (actualPause - paid).coerceAtLeast(0L)
                unpaid += extraUnpaid
                paidWork += (rawPresence - extraUnpaid).coerceAtLeast(0L)
            } else {
                // Journée normale : la pause configurée est non rémunérée et doit toujours être déduite.
                val toDeduct = maxOf(configured, actualPause).coerceAtMost(rawPresence)
                unpaid += toDeduct
                paidWork += (rawPresence - toDeduct).coerceAtLeast(0L)
            }

            if (item.optBoolean("mealEnabled", false)) meals++
            item.optString("zoneAddress").trim().takeIf { it.isNotBlank() }?.let { places += it }
            manual = manual || item.optBoolean("manual", false)
        }

        val arrival = sorted.map { it.optLong("arrivalTime", it.optLong("entry", -1L)) }.filter { it > 0L }.minOrNull() ?: first.optLong("entry")
        val counted = sorted.map { it.optLong("entry", -1L) }.filter { it > 0L }.minOrNull() ?: arrival
        val lastExit = sorted.map { it.optLong("exit", -1L) }.filter { it > 0L }.maxOrNull() ?: counted
        return Day(
            dayStart, SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(dayStart), shiftId, shiftLabel,
            arrival, counted, lastExit, presence, unpaid, paidTeamPause, paidWork, meals, places.toList(), sorted.size, manual
        )
    }

    private fun actualPauseMs(item: JSONObject, entry: Long, exit: Long): Long {
        val ps = item.optJSONArray("pauses") ?: return 0L
        val intervals = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until ps.length()) {
            val p = ps.optJSONObject(i) ?: continue
            val s = p.optLong("start", -1L).coerceAtLeast(entry)
            val e = p.optLong("end", -1L).coerceAtMost(exit)
            if (s > 0L && e > s) intervals += s to e
        }
        if (intervals.isEmpty()) return 0L
        intervals.sortBy { it.first }
        var total = 0L; var s = intervals[0].first; var e = intervals[0].second
        for (i in 1 until intervals.size) {
            val (a,b) = intervals[i]
            if (a <= e) e = maxOf(e,b) else { total += e-s; s=a; e=b }
        }
        return total + (e-s)
    }

    private fun dayStart(ms: Long): Long = Calendar.getInstance(Locale.FRANCE).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
    }.timeInMillis
}
