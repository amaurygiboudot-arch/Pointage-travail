package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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
            val place = item.optString("zoneAddress").trim().ifBlank { "Pointage manuel" }
            c.drawText("Session ${sessionCount}", M, y + 12, head); y += 20
            c.drawText("Lieu : $place", M, y + 12, text); y += 18
            c.drawText("Entrée : ${timeF.format(Date(entry))}", M, y + 12, text); y += 16
            c.drawText("Sortie : ${timeF.format(Date(exit))}", M, y + 12, text); y += 16
            c.drawText("Pauses : ${format(pauses)}", M, y + 12, text); y += 16
            c.drawText("Temps travaillé : ${format(worked)}", M, y + 12, bold); y += 22

            val pa = item.optJSONArray("pauses")
            if (pa != null && pa.length() > 0) {
                c.drawText("Détail des pauses", M + 12, y + 11, bold); y += 16
                for (j in 0 until pa.length()) {
                    val p = pa.optJSONObject(j) ?: continue
                    val s = p.optLong("start", -1L)
                    val e = if (p.isNull("end")) -1L else p.optLong("end", -1L)
                    if (s > 0L) {
                        val label = if (e > s) "${timeF.format(Date(s))} → ${timeF.format(Date(e))} (${format(e-s)})" else "${timeF.format(Date(s))} → non terminée"
                        c.drawText("• $label", M + 20, y + 11, text); y += 15
                    }
                }
            }
            c.drawLine(M, y, W - M, y, line); y += 16
        }
        if (sessionCount == 0) c.drawText("Aucune session terminée pour cette journée.", M, y + 12, muted)
        y = H - 70f
        c.drawLine(M, y, W-M, y, line); y += 20
        c.drawText("Total travaillé : ${format(totalWorked)}", M, y, title)
        c.drawText("© 2026 HP Travail — Tous droits réservés.", M, H - 20f, muted)
        pdf.finishPage(page)
        pdf.writeTo(output)
        pdf.close()
    }

    private fun format(ms: Long): String {
        val m = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", m / 60L, m % 60L)
    }
}
