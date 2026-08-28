package com.amaury.pointage.v2.engine

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.PdfVisualStyle
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Génération mensuelle basée uniquement sur les sessions et calculs V2. */
object MonthlyPdfReportV2 {
    private const val W = 842
    private const val H = 595
    private const val M = 28f

    fun write(sessions: List<WorkSessionV2>, year: Int, month: Int, output: OutputStream) {
        val selected = sessions.filter { s ->
            val at = s.realArrivalMs ?: return@filter false
            Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = at }.let {
                it.get(Calendar.YEAR) == year && it.get(Calendar.MONTH) == month
            }
        }.sortedBy { it.realArrivalMs }

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val canvas = page.canvas
        val normal = PdfVisualStyle.bodyPaint()
        val bold = PdfVisualStyle.boldPaint()
        val muted = PdfVisualStyle.bodyPaint(8f).apply { color = Color.rgb(105, 105, 105) }
        val line = Paint(1).apply { color = PdfVisualStyle.line; strokeWidth = .7f }
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(
            Calendar.getInstance(Locale.FRANCE).apply { set(year, month, 1) }.time
        ).replaceFirstChar { it.uppercase() }

        PdfVisualStyle.header(
            canvas,
            W,
            "RELEVÉ MENSUEL DE TRAVAIL — $monthLabel",
            "HoraTrack V2 • mode test • données utilisateur conservées"
        )

        var y = 92f
        var totalPresence = 0L
        var totalPaid = 0L
        var totalPause = 0L
        selected.forEach { s ->
            if (y > H - 70) return@forEach
            val result = HoraTrackV2.time.calculate(s)
            val entry = s.realArrivalMs?.let(time::format) ?: "—"
            val exit = s.realExitMs?.let(time::format) ?: "EN COURS"
            canvas.drawText("Entrée : $entry", M, y, bold)
            canvas.drawText("Sortie : $exit", 270f, y, normal)
            canvas.drawText("Payé : ${duration(result.paidWorkMs)}", 585f, y, bold)
            y += 17f
            canvas.drawText(
                "Entrée comptée ${s.countedEntryMs?.let(time::format) ?: "—"}  •  Sortie comptée ${s.countedExitMs?.let(time::format) ?: "—"}  •  Pauses ${duration(result.unpaidPauseMs)}",
                M,
                y,
                muted
            )
            y += 12f
            canvas.drawLine(M, y, W - M, y, line)
            y += 15f
            totalPresence += result.presenceMs
            totalPaid += result.paidWorkMs
            totalPause += result.unpaidPauseMs
        }

        y = (y + 12f).coerceAtMost(H - 48f)
        canvas.drawText("TOTAL — Présence ${duration(totalPresence)} • Temps payé ${duration(totalPaid)} • Pauses déduites ${duration(totalPause)} • Sessions ${selected.size}", M, y, bold)
        PdfVisualStyle.footer(canvas, W, H, 1)
        pdf.finishPage(page)
        pdf.writeTo(output)
        pdf.close()
    }

    private fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh%02d", minutes / 60L, minutes % 60L)
    }
}
