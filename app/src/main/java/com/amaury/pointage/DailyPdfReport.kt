package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import org.json.JSONArray
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DailyPdfReport {
    private const val W = 595
    private const val H = 842
    private const val M = 38f

    fun write(context: Context, data: JSONArray, dayStart: Long, dayEnd: Long, output: OutputStream) {
        if (HoraTrackV2.ENABLED) {
            writeV2(context, dayStart, dayEnd, output)
            return
        }
        writeLegacy(data, dayStart, dayEnd, output)
    }

    private fun writeV2(context: Context, dayStart: Long, dayEnd: Long, output: OutputStream) {
        val sessions = V2RuntimeStore.allSessions(context).filter { s ->
            val entry = s.countedEntryMs ?: s.realArrivalMs ?: return@filter false
            entry in dayStart until dayEnd && s.realExitMs != null
        }
        val pdf = PdfDocument()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35,35,35); textSize = 21f; typeface = Typeface.DEFAULT_BOLD }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(138,98,0); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45,45,45); textSize = 10f }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val muted = Paint(text).apply { color = Color.rgb(105,105,105); textSize = 9f }
        val line = Paint().apply { color = Color.rgb(205,205,205); strokeWidth = 1f }
        val dateF = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRANCE)
        val timeF = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val dayLabel = dateF.format(Date(dayStart)).replaceFirstChar { it.uppercase() }
        val contentBottom = H - 82f

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finishPage() {
            page?.let {
                it.canvas.drawText("© HoraTrack — Rapport généré par HoraTrack.  •  Page $pageNo", M, H - 20f, muted)
                pdf.finishPage(it)
            }
            page = null
        }

        fun startPage(continuation: Boolean = false) {
            finishPage()
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            val c = page!!.canvas
            y = M
            c.drawText(if (continuation) "RAPPORT JOURNALIER HORATRACK — SUITE" else "RAPPORT JOURNALIER HORATRACK", M, y + 18, title)
            y += 34
            c.drawText(dayLabel, M, y + 12, head)
            y += 28
        }

        fun ensureSpace(required: Float) {
            if (page == null) startPage(false)
            if (y + required > contentBottom) startPage(true)
        }

        startPage(false)
        var totalWorked = 0L

        sessions.forEachIndexed { index, s ->
            val entry = s.countedEntryMs ?: s.realArrivalMs ?: return@forEachIndexed
            val exit = s.countedExitMs ?: s.realExitMs ?: return@forEachIndexed
            val result = HoraTrackV2.time.calculate(s)
            totalWorked += result.paidWorkMs

            ensureSpace(90f)
            val c = page!!.canvas
            c.drawText("Session ${index + 1}", M, y + 12, head); y += 20
            c.drawText("Entrée comptée : ${timeF.format(Date(entry))}", M, y + 12, text); y += 16
            c.drawText("Sortie comptée : ${timeF.format(Date(exit))}", M, y + 12, text); y += 16
            c.drawText("Pauses non payées : ${format(result.unpaidPauseMs)}", M, y + 12, text); y += 16
            c.drawText("Temps payé : ${format(result.paidWorkMs)}", M, y + 12, bold); y += 22

            s.pauses.forEach { p ->
                val end = p.endMs ?: return@forEach
                ensureSpace(24f)
                page!!.canvas.drawText("• ${timeF.format(Date(p.startMs))} → ${timeF.format(Date(end))} (${format(end - p.startMs)})", M + 20, y + 11, text)
                y += 15
            }

            ensureSpace(22f)
            page!!.canvas.drawLine(M, y, W - M, y, line)
            y += 16
        }

        if (sessions.isEmpty()) {
            ensureSpace(30f)
            page!!.canvas.drawText("Aucune session terminée pour cette journée.", M, y + 12, muted)
            y += 24
        }

        ensureSpace(58f)
        page!!.canvas.drawLine(M, y, W - M, y, line)
        y += 20
        page!!.canvas.drawText("Total payé : ${format(totalWorked)}", M, y, title)

        finishPage()
        pdf.writeTo(output)
        pdf.close()
    }

    /** Rollback uniquement quand V2 est désactivé. */
    private fun writeLegacy(data: JSONArray, dayStart: Long, dayEnd: Long, output: OutputStream) {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35,35,35); textSize = 21f; typeface = Typeface.DEFAULT_BOLD }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(138,98,0); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45,45,45); textSize = 10f }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val muted = Paint(text).apply { color = Color.rgb(105,105,105); textSize = 9f }
        val line = Paint().apply { color = Color.rgb(205,205,205); strokeWidth = 1f }
        val dateF = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRANCE)
        val timeF = SimpleDateFormat("HH:mm", Locale.FRANCE)
        var y = M
        c.drawText("RAPPORT JOURNALIER DE POINTAGE", M, y + 18, title); y += 34
        c.drawText(dateF.format(Date(dayStart)).replaceFirstChar { it.uppercase() }, M, y + 12, head); y += 28

        var totalWorked = 0L
        var sessionCount = 0
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry !in dayStart until dayEnd || item.isNull("exit")) continue
            val exit = item.optLong("exit", -1L)
            if (exit <= 0L) continue
            val pauses = PointageStore.pauseDuration(item, exit)
            val worked = PointageStore.workedDuration(item, exit)
            totalWorked += worked; sessionCount++
            c.drawText("Session $sessionCount", M, y + 12, head); y += 20
            c.drawText("Entrée : ${timeF.format(Date(entry))}", M, y + 12, text); y += 16
            c.drawText("Sortie : ${timeF.format(Date(exit))}", M, y + 12, text); y += 16
            c.drawText("Pauses : ${format(pauses)}", M, y + 12, text); y += 16
            c.drawText("Temps travaillé : ${format(worked)}", M, y + 12, bold); y += 22
            c.drawLine(M, y, W - M, y, line); y += 16
        }
        if (sessionCount == 0) c.drawText("Aucune session terminée pour cette journée.", M, y + 12, muted)
        y = H - 70f
        c.drawLine(M, y, W-M, y, line); y += 20
        c.drawText("Total travaillé : ${format(totalWorked)}", M, y, title)
        c.drawText("© HoraTrack — rollback historique.", M, H - 20f, muted)
        pdf.finishPage(page)
        pdf.writeTo(output)
        pdf.close()
    }

    private fun format(ms: Long): String {
        val m = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", m / 60L, m % 60L)
    }
}
