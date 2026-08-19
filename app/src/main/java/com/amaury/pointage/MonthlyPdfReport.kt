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

    private data class PlaceInfo(val name: String?, val address: String) {
        val key: String get() = if (name.isNullOrBlank()) address else "$name — $address"
    }

    private data class Row(
        val entry: Long,
        val exit: Long?,
        val place: PlaceInfo,
        val workedMs: Long,
        val pauseMs: Long
    )

    fun write(
        context: Context,
        data: JSONArray,
        year: Int,
        month: Int,
        output: OutputStream
    ) {
        val rows = mutableListOf<Row>()
        val cal = Calendar.getInstance(Locale.FRANCE)
        val now = System.currentTimeMillis()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            cal.timeInMillis = entry
            if (cal.get(Calendar.YEAR) != year || cal.get(Calendar.MONTH) != month) continue

            val rawPlace = item.optString("zoneAddress").trim()
            val place = resolvePlace(context, rawPlace)
            val exit = if (item.isNull("exit")) null else item.optLong("exit").takeIf { it > 0L }
            val effectiveEnd = exit ?: now
            rows += Row(
                entry = entry,
                exit = exit,
                place = place,
                workedMs = PointageStore.workedDuration(item, effectiveEnd),
                pauseMs = PointageStore.pauseDuration(item, effectiveEnd)
            )
        }

        rows.sortBy { it.entry }

        val totals = LinkedHashMap<String, Pair<PlaceInfo, Long>>()
        var grandTotal = 0L
        var completed = 0
        rows.forEach { row ->
            // Un mois clôturé ne doit jamais compter une session encore ouverte.
            val counted = if (row.exit != null) row.workedMs else 0L
            val previous = totals[row.place.key]?.second ?: 0L
            totals[row.place.key] = row.place to (previous + counted)
            if (row.exit != null) {
                grandTotal += row.workedMs
                completed++
            }
        }

        val pdf = PdfDocument()
        val writer = PageWriter(pdf, year, month)
        writer.startPage()
        writer.drawReportHeader(grandTotal, completed, totals.size)
        writer.drawPlaceTotals(totals)
        writer.drawDetailHeader()

        if (rows.isEmpty()) {
            writer.drawMuted("Aucun pointage enregistré pour ce mois.")
        } else {
            rows.forEach { writer.drawRow(it) }
        }

        writer.finish()
        pdf.writeTo(output)
        pdf.close()
    }

    private fun resolvePlace(context: Context, raw: String): PlaceInfo {
        if (raw.isBlank()) return PlaceInfo(null, "Pointage manuel / ancien pointage")

        val marker = " — "
        if (raw.contains(marker)) {
            val name = raw.substringBefore(marker).trim().takeIf { it.isNotBlank() }
            val address = raw.substringAfter(marker).trim().ifBlank { raw }
            return PlaceInfo(name, address)
        }

        val savedName = PlaceNames.get(context, raw)?.trim()?.takeIf { it.isNotBlank() }
        return PlaceInfo(savedName, raw)
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
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN
        }

        private fun canvas() = requireNotNull(page).canvas

        private fun endPage() {
            val current = page ?: return
            canvas().drawLine(MARGIN, PAGE_HEIGHT - 28f, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 28f, linePaint)
            canvas().drawText("Rapport HP Travail — page $pageNumber", MARGIN, PAGE_HEIGHT - 14f, mutedPaint)
            pdf.finishPage(current)
            page = null
        }

        fun finish() = endPage()

        private fun ensureSpace(height: Float, repeatDetailHeader: Boolean = false) {
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
            canvas().drawText(
                "Rapport mensuel — ${monthFormat.format(c.time).replaceFirstChar { it.uppercase() }}",
                MARGIN,
                y + 14f,
                boldPaint
            )
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
            canvas().drawText("Généré le ${generatedFormat.format(Date())}", PAGE_WIDTH - 185f, y + 14f, mutedPaint)
            y += 28f
            canvas().drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 48f, headerFill)
            canvas().drawText("Temps travaillé", MARGIN + 12f, y + 17f, mutedPaint)
            canvas().drawText(formatDuration(grandTotal), MARGIN + 12f, y + 38f, titlePaint)
            canvas().drawText("Sessions terminées", 238f, y + 17f, mutedPaint)
            canvas().drawText(completed.toString(), 238f, y + 38f, boldPaint)
            canvas().drawText("Lieux", 390f, y + 17f, mutedPaint)
            canvas().drawText(placeCount.toString(), 390f, y + 38f, boldPaint)
            y += 64f
        }

        fun drawPlaceTotals(totals: LinkedHashMap<String, Pair<PlaceInfo, Long>>) {
            ensureSpace(40f)
            canvas().drawText("RÉCAPITULATIF PAR LIEU", MARGIN, y + 12f, sectionPaint)
            y += 22f
            if (totals.isEmpty()) {
                drawMuted("Aucun lieu comptabilisé pour ce mois.")
                return
            }

            totals.values.forEach { (place, total) ->
                val addressLines = wrapText(place.address, 390f, normalPaint)
                val blockHeight = 22f + addressLines.size * 12f
                ensureSpace(blockHeight)
                if (!place.name.isNullOrBlank()) {
                    canvas().drawText(place.name, MARGIN, y + 12f, boldPaint)
                    y += 14f
                }
                addressLines.forEach { line ->
                    canvas().drawText(line, MARGIN, y + 11f, normalPaint)
                    y += 12f
                }
                canvas().drawText(formatDuration(total), PAGE_WIDTH - MARGIN - 70f, y - 2f, boldPaint)
                y += 5f
                canvas().drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
                y += 7f
            }
            y += 4f
        }

        fun drawDetailHeader() {
            ensureSpace(42f)
            canvas().drawText("DÉTAIL DES POINTAGES", MARGIN, y + 12f, sectionPaint)
            y += 20f
            canvas().drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 25f, headerFill)
            canvas().drawText("Date", MARGIN + 5f, y + 16f, boldPaint)
            canvas().drawText("Lieu / adresse", 98f, y + 16f, boldPaint)
            canvas().drawText("Entrée", 382f, y + 16f, boldPaint)
            canvas().drawText("Sortie", 438f, y + 16f, boldPaint)
            canvas().drawText("Travail", 495f, y + 16f, boldPaint)
            y += 30f
        }

        fun drawRow(row: Row) {
            val addressLines = wrapText(row.place.address, 270f, normalPaint)
            val nameLines = if (row.place.name.isNullOrBlank()) emptyList() else wrapText(row.place.name, 270f, boldPaint)
            val pauseLineCount = if (row.pauseMs > 0L) 1 else 0
            val lineCount = (nameLines.size + addressLines.size + pauseLineCount).coerceAtLeast(1)
            val height = maxOf(30f, 10f + lineCount * 12f)
            ensureSpace(height, repeatDetailHeader = true)

            val date = dateFormat.format(Date(row.entry))
            val entry = timeFormat.format(Date(row.entry))
            val exit = row.exit?.let { timeFormat.format(Date(it)) } ?: "-"
            val duration = if (row.exit != null) formatDuration(row.workedMs) else "En cours"
            val baseline = y + 13f
            canvas().drawText(date, MARGIN + 5f, baseline, normalPaint)
            canvas().drawText(entry, 382f, baseline, normalPaint)
            canvas().drawText(exit, 438f, baseline, normalPaint)
            canvas().drawText(duration, 495f, baseline, normalPaint)

            var placeY = baseline
            nameLines.forEach { line ->
                canvas().drawText(line, 98f, placeY, boldPaint)
                placeY += 12f
            }
            addressLines.forEach { line ->
                canvas().drawText(line, 98f, placeY, normalPaint)
                placeY += 12f
            }
            if (row.pauseMs > 0L) {
                canvas().drawText("Pauses : ${formatDuration(row.pauseMs)}", 98f, placeY, mutedPaint)
            }

            y += height
            canvas().drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 5f
        }

        fun drawMuted(text: String) {
            ensureSpace(24f)
            canvas().drawText(text, MARGIN, y + 12f, mutedPaint)
            y += 20f
        }

        private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
            if (text.isBlank()) return listOf("")
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val lines = mutableListOf<String>()
            var current = ""
            for (word in words) {
                val candidate = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotBlank()) lines += current
                    if (paint.measureText(word) <= maxWidth) {
                        current = word
                    } else {
                        var chunk = ""
                        word.forEach { ch ->
                            val test = chunk + ch
                            if (paint.measureText(test) > maxWidth && chunk.isNotEmpty()) {
                                lines += chunk
                                chunk = ch.toString()
                            } else {
                                chunk = test
                            }
                        }
                        current = chunk
                    }
                }
            }
            if (current.isNotBlank()) lines += current
            return lines.ifEmpty { listOf(text) }
        }

        private fun formatDuration(ms: Long): String {
            val totalMinutes = ms.coerceAtLeast(0L) / 60000L
            return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
        }
    }
}
