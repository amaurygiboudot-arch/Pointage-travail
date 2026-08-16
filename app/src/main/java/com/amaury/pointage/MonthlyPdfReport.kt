package com.amaury.pointage

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

    private data class Row(
        val entry: Long,
        val exit: Long?,
        val place: String
    )

    fun write(
        data: JSONArray,
        year: Int,
        month: Int,
        output: OutputStream
    ) {
        val rows = mutableListOf<Row>()
        val cal = Calendar.getInstance(Locale.FRANCE)

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue

            cal.timeInMillis = entry
            if (cal.get(Calendar.YEAR) != year || cal.get(Calendar.MONTH) != month) continue

            val place = item.optString("zoneAddress").trim().takeIf { it.isNotBlank() }
                ?: "Pointage manuel / ancien pointage"
            val exit = if (item.isNull("exit")) null else item.optLong("exit").takeIf { it > 0L }
            rows.add(Row(entry, exit, place))
        }

        rows.sortBy { it.entry }

        val totals = LinkedHashMap<String, Long>()
        var grandTotal = 0L
        var completed = 0

        rows.forEach { row ->
            if (row.exit != null) {
                val duration = (row.exit - row.entry).coerceAtLeast(0L)
                totals[row.place] = (totals[row.place] ?: 0L) + duration
                grandTotal += duration
                completed++
            } else {
                totals.putIfAbsent(row.place, 0L)
            }
        }

        val pdf = PdfDocument()
        val writer = PageWriter(pdf, year, month)

        writer.startPage()
        writer.drawReportHeader(grandTotal, completed, totals.size)
        writer.drawPlaceTotals(totals)
        writer.drawDetailHeader()

        if (rows.isEmpty()) {
            writer.ensureSpace(34f)
            writer.drawMuted("Aucun pointage enregistre pour ce mois.")
        } else {
            rows.forEach { row -> writer.drawRow(row) }
        }

        writer.finish()
        pdf.writeTo(output)
        pdf.close()
    }

    private class PageWriter(
        private val pdf: PdfDocument,
        private val year: Int,
        private val month: Int
    ) {
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = MARGIN

        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 38, 38)
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(133, 94, 20)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 45, 45)
            textSize = 9.5f
        }
        private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(105, 105, 105)
            textSize = 9f
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 205, 205)
            strokeWidth = 0.8f
        }
        private val headerFill = Paint().apply { color = Color.rgb(243, 239, 230) }

        private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
        private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
        private val generatedFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

        fun startPage() {
            pageNumber++
            page = pdf.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            )
            y = MARGIN
        }

        private fun canvas() = requireNotNull(page).canvas

        private fun endPage() {
            val current = page ?: return
            val footer = "Rapport Pointage Travail - page $pageNumber"
            canvas().drawLine(MARGIN, PAGE_HEIGHT - 28f, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 28f, linePaint)
            canvas().drawText(footer, MARGIN, PAGE_HEIGHT - 14f, mutedPaint)
            pdf.finishPage(current)
            page = null
        }

        fun finish() {
            endPage()
        }

        fun ensureSpace(height: Float, repeatDetailHeader: Boolean = false) {
            if (y + height <= PAGE_HEIGHT - 48f) return
            endPage()
            startPage()
            drawContinuationHeader()
            if (repeatDetailHeader) drawDetailHeader()
        }

        private fun drawContinuationHeader() {
            val c = Calendar.getInstance(Locale.FRANCE).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            canvas().drawText("Rapport mensuel - ${monthFormat.format(c.time).replaceFirstChar { it.uppercase() }}", MARGIN, y + 14f, boldPaint)
            y += 25f
            canvas().drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 10f
        }

        fun drawReportHeader(grandTotal: Long, completed: Int, placeCount: Int) {
            val c = Calendar.getInstance(Locale.FRANCE).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val monthLabel = monthFormat.format(c.time).replaceFirstChar { it.uppercase() }

            canvas().drawText("RAPPORT MENSUEL DE POINTAGE", MARGIN, y + 18f, titlePaint)
            y += 30f
            canvas().drawText(monthLabel, MARGIN, y + 14f, sectionPaint)
            canvas().drawText("Genere le ${generatedFormat.format(Date())}", PAGE_WIDTH - 185f, y + 14f, mutedPaint)
            y += 28f

            canvas().drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 48f, headerFill)
            canvas().drawText("Temps total", MARGIN + 12f, y + 17f, mutedPaint)
            canvas().drawText(formatDuration(grandTotal), MARGIN + 12f, y + 38f, titlePaint)
            canvas().drawText("Sessions terminees", 238f, y + 17f, mutedPaint)
            canvas().drawText(completed.toString(), 238f, y + 38f, boldPaint)
            canvas().drawText("Lieux", 390f, y + 17f, mutedPaint)
            canvas().drawText(placeCount.toString(), 390f, y + 38f, boldPaint)
            y += 64f
        }

        fun drawPlaceTotals(totals: LinkedHashMap<String, Long>) {
            ensureSpace(40f)
            canvas().drawText("RECAPITULATIF PAR LIEU", MARGIN, y + 12f, sectionPaint)
            y += 22f

            if (totals.isEmpty()) {
                drawMuted("Aucun lieu comptabilise pour ce mois.")
                y += 8f
                return
            }

            totals.forEach { (place, total) ->
                ensureSpace(27f)
                val shownPlace = fitText(place, 380f, normalPaint)
                canvas().drawText(shownPlace, MARGIN, y + 12f, normalPaint)
                canvas().drawText(formatDuration(total), PAGE_WIDTH - MARGIN - 70f, y + 12f, boldPaint)
                y += 20f
                canvas().drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
                y += 6f
            }
            y += 8f
        }

        fun drawDetailHeader() {
            ensureSpace(42f)
            canvas().drawText("DETAIL DES POINTAGES", MARGIN, y + 12f, sectionPaint)
            y += 20f
            canvas().drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 25f, headerFill)
            canvas().drawText("Date", MARGIN + 5f, y + 16f, boldPaint)
            canvas().drawText("Lieu", 98f, y + 16f, boldPaint)
            canvas().drawText("Entree", 382f, y + 16f, boldPaint)
            canvas().drawText("Sortie", 438f, y + 16f, boldPaint)
            canvas().drawText("Duree", 495f, y + 16f, boldPaint)
            y += 30f
        }

        fun drawRow(row: Row) {
            ensureSpace(30f, repeatDetailHeader = true)

            val date = dateFormat.format(Date(row.entry))
            val entry = timeFormat.format(Date(row.entry))
            val exit = row.exit?.let { timeFormat.format(Date(it)) } ?: "-"
            val duration = row.exit?.let { formatDuration((it - row.entry).coerceAtLeast(0L)) } ?: "En cours"

            canvas().drawText(date, MARGIN + 5f, y + 13f, normalPaint)
            canvas().drawText(fitText(row.place, 270f, normalPaint), 98f, y + 13f, normalPaint)
            canvas().drawText(entry, 382f, y + 13f, normalPaint)
            canvas().drawText(exit, 438f, y + 13f, normalPaint)
            canvas().drawText(duration, 495f, y + 13f, normalPaint)
            y += 21f
            canvas().drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 5f
        }

        fun drawMuted(text: String) {
            canvas().drawText(text, MARGIN, y + 12f, mutedPaint)
            y += 20f
        }

        private fun fitText(text: String, maxWidth: Float, paint: Paint): String {
            if (paint.measureText(text) <= maxWidth) return text
            var value = text
            while (value.length > 3 && paint.measureText("$value...") > maxWidth) {
                value = value.dropLast(1)
            }
            return "$value..."
        }

        private fun formatDuration(ms: Long): String {
            val totalMinutes = ms.coerceAtLeast(0L) / 60000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return String.format(Locale.FRANCE, "%02dh %02dm", hours, minutes)
        }
    }
}
