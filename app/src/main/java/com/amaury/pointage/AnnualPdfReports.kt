package com.amaury.pointage

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2LegacyPolicy
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeStore
import org.json.JSONArray
import java.io.OutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object AnnualPdfReports {

    /**
     * Bilan annuel du temps de travail.
     * Quand le moteur actuel est actif, aucun calcul WorkReportCalculator n'est utilisé.
     * Le JSONArray n'est conservé que pour le rollback legacy lorsque ce moteur est désactivé.
     */
    fun writeWork(context: Context, data: JSONArray, year: Int, out: OutputStream) {
        if (!HoraTrackV2.ENABLED) {
            writeWorkLegacy(context, data, year, out)
            return
        }

        val sessions = V2RuntimeStore.allSessions(context).filter { session ->
            val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
            Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }.get(Calendar.YEAR) == year
        }

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        PdfVisualStyle.header(canvas, 595, "BILAN ANNUEL DU TEMPS DE TRAVAIL — $year", "HoraTrack • Synthèse annuelle")

        val header = PdfVisualStyle.boldPaint(9.2f)
        val normal = PdfVisualStyle.bodyPaint(8.8f)
        val fill = Paint().apply { color = PdfVisualStyle.panel }
        val xs = floatArrayOf(34f, 142f, 190f, 270f, 350f, 435f, 525f)
        var y = 82f
        canvas.drawRect(30f, y, 565f, y + 24, fill)
        arrayOf("Mois", "Jours", "Présence", "Temps payé", "Pause payée", "Pause déduite", "Sessions")
            .forEachIndexed { i, value -> canvas.drawText(value, xs[i], y + 16, header) }
        y += 30f

        var totalDays = 0
        var totalPresence = 0L
        var totalPaid = 0L
        var totalPaidPause = 0L
        var totalUnpaidPause = 0L
        var totalSessions = 0

        for (month in 0..11) {
            val monthSessions = sessions.filter { session ->
                val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
                Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }.get(Calendar.MONTH) == month
            }
            val results = monthSessions.map { HoraTrackV2.time.calculate(it) }
            val days = monthSessions.mapNotNull { session ->
                val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@mapNotNull null
                Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }.let {
                    it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR)
                }
            }.distinct().size
            val presence = results.sumOf { it.presenceMs }
            val paid = results.sumOf { it.paidWorkMs }
            val paidPause = results.sumOf { it.paidPauseMs }
            val unpaidPause = results.sumOf { it.unpaidPauseMs }
            val label = monthLabel(year, month)
            val values = arrayOf(
                label,
                days.toString(),
                dur(presence),
                dur(paid),
                dur(paidPause),
                dur(unpaidPause),
                monthSessions.size.toString()
            )
            values.forEachIndexed { i, value -> canvas.drawText(value, xs[i], y + 13, normal) }
            y += 23f

            totalDays += days
            totalPresence += presence
            totalPaid += paid
            totalPaidPause += paidPause
            totalUnpaidPause += unpaidPause
            totalSessions += monthSessions.size
        }

        y += 8f
        canvas.drawRect(30f, y, 565f, y + 62, fill)
        canvas.drawText("TOTAL ANNÉE", 38f, y + 17, header)
        canvas.drawText("$totalDays jours • présence ${dur(totalPresence)} • payé ${dur(totalPaid)}", 38f, y + 35, header)
        canvas.drawText("pauses payées ${dur(totalPaidPause)} • pauses déduites ${dur(totalUnpaidPause)} • $totalSessions sessions", 38f, y + 52, header)
        PdfVisualStyle.footer(canvas, 595, 842, 1)
        pdf.finishPage(page)
        pdf.writeTo(out)
        pdf.close()
    }

    /**
     * Estimation annuelle de rémunération.
     * Les heures sont limitées à l'employeur principal pour ne jamais mélanger
     * deux contrats différents. Aucune convention inconnue n'est remplacée par
     * une convention par défaut.
     */
    fun writeSalary(context: Context, data: JSONArray, year: Int, out: OutputStream) {
        if (!HoraTrackV2.ENABLED) {
            writeSalaryLegacy(context, data, year, out)
            return
        }

        val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val profile = V2ProfileStore.load(context, 1)
        val employerId = profile.employer?.id
        val rate = profile.contract?.grossHourlyRate ?: prefDouble(prefs.all["hourly_rate"])
        val idcc = profile.employer?.collectiveAgreementId
            ?: prefs.getString("company_idcc", "").orEmpty().ifBlank { prefs.getString("convention_idcc", "").orEmpty() }
        val convention = idcc.takeIf { it.isNotBlank() }?.let(ConventionCatalog::findByIdcc)
        val euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        PdfVisualStyle.header(canvas, 595, "ESTIMATION ANNUELLE DE RÉMUNÉRATION — $year", "Document indicatif • HoraTrack")
        val header = PdfVisualStyle.boldPaint(9.2f)
        val normal = PdfVisualStyle.bodyPaint(8.7f)
        val small = PdfVisualStyle.bodyPaint(7.9f)
        val fill = Paint().apply { color = PdfVisualStyle.panel }
        var y = 82f

        canvas.drawText("Cette estimation ne remplace pas un bulletin de paie.", 30f, y, normal)
        y += 18f
        canvas.drawRect(30f, y, 565f, y + 24, fill)
        val xs = floatArrayOf(34f, 150f, 255f, 350f, 455f)
        arrayOf("Mois", "Heures payées", "Heures sup.", "Brut estimé", "État des règles")
            .forEachIndexed { i, value -> canvas.drawText(value, xs[i], y + 16, header) }
        y += 30f

        var annualPaid = 0L
        var annualOvertime = 0L
        var annualGross = 0.0
        var grossMonths = 0
        var ruleWarnings = 0

        for (month in 0..11) {
            val monthSessions = V2RuntimeStore.allSessions(context).filter { session ->
                val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
                val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }
                val correctEmployer = employerId == null || session.employerId == employerId
                correctEmployer && c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month && session.realExitMs != null
            }
            val paid = monthSessions.sumOf { HoraTrackV2.time.calculate(it).paidWorkMs }

            val salary = if (rate != null && rate > 0.0 && convention != null && profile.contract != null) {
                V2SalaryAdapter.calculate(context, year, month, rate, convention)
            } else null
            val overtime = salary?.overtimeTiers?.sumOf { it.durationMs } ?: 0L
            val gross = salary?.monthlyEstimatedGross
            val state = when {
                profile.contract == null -> "Contrat à compléter"
                rate == null || rate <= 0.0 -> "Taux manquant"
                convention == null -> "Convention à confirmer"
                salary?.warnings?.isNotEmpty() == true -> "À confirmer"
                else -> "OK"
            }
            if (state != "OK") ruleWarnings++

            val values = arrayOf(
                monthLabel(year, month),
                dur(paid),
                if (salary != null) dur(overtime) else "—",
                gross?.let(euro::format) ?: "—",
                state
            )
            values.forEachIndexed { i, value -> canvas.drawText(value, xs[i], y + 13, if (i == 4) small else normal) }
            y += 23f

            annualPaid += paid
            annualOvertime += overtime
            if (gross != null) {
                annualGross += gross
                grossMonths++
            }
        }

        y += 8f
        canvas.drawRect(30f, y, 565f, y + 64, fill)
        canvas.drawText("TOTAL ANNÉE", 38f, y + 17, header)
        canvas.drawText("Temps payé : ${dur(annualPaid)} • heures sup. confirmées : ${dur(annualOvertime)}", 38f, y + 35, header)
        canvas.drawText(
            if (grossMonths > 0) "Brut estimé cumulé sur $grossMonths mois calculables : ${euro.format(annualGross)}" else "Brut annuel non calculé : fiche Salaire ou règles à compléter",
            38f,
            y + 52,
            header
        )
        y += 80f
        if (ruleWarnings > 0) {
            canvas.drawText("$ruleWarnings mois comportent une règle manquante ou à confirmer : HoraTrack n'a appliqué aucune valeur par défaut.", 30f, y, small)
        } else {
            canvas.drawText("Calcul basé uniquement sur le contrat, la convention confirmée et les sessions HoraTrack de l'employeur.", 30f, y, small)
        }
        PdfVisualStyle.footer(canvas, 595, 842, 1)
        pdf.finishPage(page)
        pdf.writeTo(out)
        pdf.close()
    }

    /** Ancien moteur conservé uniquement si le moteur actuel est explicitement désactivé pour rollback. */
    private fun writeWorkLegacy(context: Context, data: JSONArray, year: Int, out: OutputStream) {
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PDF)
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        PdfVisualStyle.header(canvas, 595, "BILAN ANNUEL DU TEMPS DE TRAVAIL — $year", "HoraTrack • mode rollback")
        val header = PdfVisualStyle.boldPaint(9.2f)
        val normal = PdfVisualStyle.bodyPaint(8.8f)
        var y = 82f
        var paid = 0L
        for (month in 0..11) {
            val days = WorkReportCalculator.month(context, data, year, month)
            val monthPaid = days.sumOf { it.paidWorkMs }
            canvas.drawText("${monthLabel(year, month)} : ${dur(monthPaid)}", 34f, y, normal)
            y += 22f
            paid += monthPaid
        }
        canvas.drawText("TOTAL : ${dur(paid)}", 34f, y + 12f, header)
        PdfVisualStyle.footer(canvas, 595, 842, 1)
        pdf.finishPage(page)
        pdf.writeTo(out)
        pdf.close()
    }

    private fun writeSalaryLegacy(context: Context, data: JSONArray, year: Int, out: OutputStream) {
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PAYROLL)
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PDF)
        val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val rate = prefDouble(prefs.all["hourly_rate"]) ?: 0.0
        val idcc = prefs.getString("company_idcc", "").orEmpty()
        val convention = ConventionCatalog.findByIdcc(idcc)
        val euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        PdfVisualStyle.header(canvas, 595, "ESTIMATION ANNUELLE DE RÉMUNÉRATION — $year", "HoraTrack • mode rollback")
        val normal = PdfVisualStyle.bodyPaint(8.8f)
        var y = 82f
        var gross = 0.0
        for (month in 0..11) {
            val result = if (rate > 0.0 && convention != null) SalaryCalculator.calculate(data, year, month, rate, convention) else null
            val value = result?.monthlyEstimatedGross
            canvas.drawText("${monthLabel(year, month)} : ${value?.let(euro::format) ?: "—"}", 34f, y, normal)
            y += 22f
            if (value != null) gross += value
        }
        canvas.drawText("Total estimé : ${euro.format(gross)}", 34f, y + 12f, PdfVisualStyle.boldPaint(9.2f))
        PdfVisualStyle.footer(canvas, 595, 842, 1)
        pdf.finishPage(page)
        pdf.writeTo(out)
        pdf.close()
    }

    private fun monthLabel(year: Int, month: Int): String = SimpleDateFormat("MMMM", Locale.FRANCE)
        .format(Calendar.getInstance(Locale.FRANCE).apply { set(year, month, 1) }.time)
        .replaceFirstChar { it.uppercase() }

    private fun prefDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it > 0.0 }
        is String -> value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        else -> null
    }

    private fun dur(ms: Long): String {
        val min = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", min / 60L, min % 60L)
    }
}
