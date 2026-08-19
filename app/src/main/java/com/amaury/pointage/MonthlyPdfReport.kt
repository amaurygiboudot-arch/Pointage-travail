package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

object MonthlyPdfReport {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 32f

    private data class PlaceTotal(
        val name: String?,
        val address: String,
        var duration: Long = 0L,
        var sessions: Int = 0
    )

    fun write(context: Context, data: JSONArray, year: Int, month: Int, output: OutputStream) {
        val totals = LinkedHashMap<String, PlaceTotal>()
        val cal = Calendar.getInstance(Locale.FRANCE)
        var grandTotal = 0L
        var completedSessions = 0

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || item.isNull("exit")) continue
            cal.timeInMillis = entry
            if (cal.get(Calendar.YEAR) != year || cal.get(Calendar.MONTH) != month) continue

            val exit = item.optLong("exit", -1L)
            if (exit <= entry) continue
            val raw = item.optString("zoneAddress").trim()
            val (name, address) = resolvePlace(context, raw)
            val key = normalizeAddress(address)
            val duration = PointageStore.workedDuration(item, exit)
            val place = totals.getOrPut(key) { PlaceTotal(name, address) }
            if (place.name.isNullOrBlank() && !name.isNullOrBlank()) {
                totals[key] = place.copy(name = name)
            }
            totals[key]!!.duration += duration
            totals[key]!!.sessions += 1
            grandTotal += duration
            completedSessions++
        }

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 38, 38)
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val section = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(133, 94, 20)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 45, 45); textSize = 9.5f }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(105, 105, 105); textSize = 9f }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 205); strokeWidth = 0.8f }
        val fill = Paint().apply { color = Color.rgb(243, 239, 230) }

        val monthDate = Calendar.getInstance(Locale.FRANCE).apply { set(year, month, 1) }.time
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(monthDate).replaceFirstChar { it.uppercase() }
        val generated = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())
        var y = MARGIN

        canvas.drawText("RAPPORT MENSUEL DE POINTAGE", MARGIN, y + 18f, title)
        y += 30f
        canvas.drawText(monthLabel, MARGIN, y + 14f, section)
        canvas.drawText("Généré le $generated", PAGE_WIDTH - 185f, y + 14f, muted)
        y += 28f

        canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 48f, fill)
        canvas.drawText("Temps travaillé", MARGIN + 12f, y + 17f, muted)
        canvas.drawText(formatDuration(grandTotal), MARGIN + 12f, y + 38f, title)
        canvas.drawText("Sessions", 255f, y + 17f, muted)
        canvas.drawText(completedSessions.toString(), 255f, y + 38f, bold)
        canvas.drawText("Adresses", 400f, y + 17f, muted)
        canvas.drawText(totals.size.toString(), 400f, y + 38f, bold)
        y += 68f

        canvas.drawText("RÉCAPITULATIF PAR ADRESSE", MARGIN, y + 12f, section)
        y += 22f
        canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 25f, fill)
        canvas.drawText("Lieu / adresse", MARGIN + 6f, y + 16f, bold)
        canvas.drawText("Sessions", 420f, y + 16f, bold)
        canvas.drawText("Temps", 492f, y + 16f, bold)
        y += 31f

        if (totals.isEmpty()) {
            canvas.drawText("Aucun pointage terminé pour ce mois.", MARGIN, y + 14f, muted)
        } else {
            totals.values.sortedBy { it.address.lowercase(Locale.FRANCE) }.forEach { place ->
                if (y > PAGE_HEIGHT - 60f) return@forEach
                val label = if (place.name.isNullOrBlank()) place.address else "${place.name} — ${place.address}"
                val shown = if (label.length <= 62) label else label.take(59) + "…"
                canvas.drawText(shown, MARGIN + 6f, y + 14f, normal)
                canvas.drawText(place.sessions.toString(), 435f, y + 14f, normal)
                canvas.drawText(formatDuration(place.duration), 492f, y + 14f, bold)
                y += 24f
                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, line)
            }
        }

        canvas.drawLine(MARGIN, PAGE_HEIGHT - 28f, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 28f, line)
        canvas.drawText("HP Travail — récapitulatif mensuel", MARGIN, PAGE_HEIGHT - 14f, muted)
        pdf.finishPage(page)
        pdf.writeTo(output)
        pdf.close()
    }

    private fun resolvePlace(context: Context, raw: String): Pair<String?, String> {
        if (raw.isBlank()) return null to "Pointage manuel / ancien pointage"
        val marker = " — "
        if (raw.contains(marker)) {
            val name = raw.substringBefore(marker).trim().takeIf { it.isNotBlank() }
            val address = raw.substringAfterLast(marker).trim().ifBlank { raw }
            return name to address
        }
        return PlaceNames.get(context, raw)?.trim()?.takeIf { it.isNotBlank() } to raw
    }

    private fun normalizeAddress(address: String): String = address
        .trim()
        .lowercase(Locale.FRANCE)
        .replace(Regex("\\s+"), " ")

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }
}
