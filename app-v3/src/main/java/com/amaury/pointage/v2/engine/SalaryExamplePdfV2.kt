package com.amaury.pointage.v2.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.PdfVisualStyle
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeStore
import java.io.OutputStream
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/** Génère une vraie page PDF d'estimation, visuellement structurée comme un bulletin. */
object SalaryExamplePdfV2 {
    enum class Field { COMPANY, CONTRACT, HOURS, PAUSES, ESTIMATED_GROSS, COUNTERS, SOURCES }

    fun write(context: Context, year: Int, month: Int, fields: Set<Field>, output: OutputStream) {
        val profile = V2ProfileStore.load(context, 1)
        val contract = profile.contract
        val employer = profile.employer
        val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val rate = contract?.grossHourlyRate
        val idcc = employer?.collectiveAgreementId?.trim().orEmpty()
        val convention = idcc.takeIf { it.isNotBlank() }?.let { ConventionCatalog.findByIdcc(context, it) }?.takeIf { it.idcc.isNotBlank() }
        val salary = if (rate != null && convention != null) {
            V2SalaryAdapter.calculate(context, year, month, rate, convention)
        } else null

        val sessions = V2RuntimeStore.allSessions(context).filter { s ->
            val at = s.countedEntryMs ?: s.realArrivalMs ?: return@filter false
            val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = at }
            s.employerId == contract?.employerId && cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month
        }
        val pauseMs = sessions.sumOf { HoraTrackV2.time.calculate(it).unpaidPauseMs }

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = PdfVisualStyle.boldPaint(16f)
        val bold = PdfVisualStyle.boldPaint(10f)
        val body = PdfVisualStyle.bodyPaint(9f)
        val muted = PdfVisualStyle.bodyPaint(8f).apply { color = Color.rgb(95, 95, 95) }
        val line = Paint(1).apply { color = PdfVisualStyle.line; strokeWidth = 0.8f }
        var y = 42f
        val monthName = DateFormatSymbols(Locale.FRANCE).months.getOrNull(month).orEmpty().replaceFirstChar { it.uppercase() }

        c.drawText("FICHE DE PAIE EXEMPLE — ESTIMATION HORATRACK", 28f, y, title)
        y += 18f
        c.drawText("$monthName $year • document personnel d'estimation • non officiel", 28f, y, muted)
        y += 18f
        c.drawLine(28f, y, 567f, y, line)
        y += 22f

        fun section(name: String, lines: List<Pair<String, String>>) {
            if (lines.isEmpty()) return
            c.drawText(name, 28f, y, bold)
            y += 15f
            lines.forEach { (label, value) ->
                c.drawText(label, 38f, y, body)
                c.drawText(value, 315f, y, body)
                y += 14f
            }
            y += 5f
            c.drawLine(28f, y, 567f, y, line)
            y += 18f
        }

        if (Field.COMPANY in fields) {
            section("EMPLOYEUR", listOf(
                "Entreprise" to (employer?.name?.takeIf { it.isNotBlank() } ?: "À compléter"),
                "SIRET" to (employer?.siret ?: "À compléter"),
                "Convention / régime" to if (convention != null) convention.displayName else "À confirmer"
            ))
        }

        if (Field.CONTRACT in fields) {
            section("CONTRAT", listOf(
                "Type" to (contract?.type?.name ?: "À compléter"),
                "Durée hebdomadaire" to (contract?.contractualWeeklyMinutes?.let { "%dh%02d".format(Locale.FRANCE, it / 60, it % 60) } ?: "À confirmer"),
                "Taux horaire brut" to (rate?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À compléter"),
                "Panier déclaré" to (prefs.all["meal_amount"]?.toString()?.let { "$it €" } ?: "Non renseigné")
            ))
        }

        if (Field.HOURS in fields) {
            section("TEMPS DE TRAVAIL", listOf(
                "Sessions terminées" to (salary?.completedSessions?.toString() ?: sessions.count { it.realExitMs != null }.toString()),
                "Temps payé" to duration(salary?.totalWorkedMs ?: sessions.sumOf { HoraTrackV2.time.calculate(it).paidWorkMs }),
                "Heures normales" to duration(salary?.regularMs ?: 0L),
                "Heures supplémentaires" to (salary?.overtimeTiers?.joinToString { "${it.label}: ${duration(it.durationMs)}" }?.ifBlank { "Aucune règle confirmée applicable" } ?: "À confirmer")
            ))
        }

        if (Field.PAUSES in fields) {
            section("PAUSES", listOf(
                "Pauses non payées déduites" to duration(pauseMs),
                "Méthode" to "Intervalles confirmés + déductions historiques conservées"
            ))
        }

        if (Field.ESTIMATED_GROSS in fields) {
            section("ESTIMATION DE RÉMUNÉRATION", listOf(
                "Brut estimé HoraTrack" to (salary?.monthlyEstimatedGross?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À confirmer"),
                "Majoration heures supplémentaires" to (salary?.overtimeGross?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À confirmer"),
                "Cotisations / net" to "Non inventés : à confirmer depuis des sources applicables"
            ))
        }

        if (Field.COUNTERS in fields) {
            val counters = V2RightsStore.all(context)
            section("COMPTEURS", if (counters.isEmpty()) listOf("Droits" to "Aucun compteur renseigné") else counters.flatMap { b ->
                buildList {
                    b.acquired?.let { add("${b.label} — acquis" to "${fmt(it)} ${b.unit}") }
                    b.available?.let { add("${b.label} — disponible" to "${fmt(it)} ${b.unit}") }
                    b.taken?.let { add("${b.label} — pris" to "${fmt(it)} ${b.unit}") }
                    b.anticipated?.let { add("${b.label} — anticipé" to "${fmt(it)} ${b.unit}") }
                    b.remaining?.let { add("${b.label} — restant" to "${fmt(it)} ${b.unit}") }
                }
            })
        }

        if (Field.SOURCES in fields) {
            val warnings = salary?.warnings.orEmpty()
            section("SOURCES & CONTRÔLES", listOf(
                "Source des heures" to "Moteur HoraTrackMotor",
                "Convention" to if (convention != null) "IDCC ${convention.idcc}" else "À confirmer",
                "Éléments à vérifier" to if (warnings.isEmpty()) "Aucun avertissement moteur" else warnings.joinToString(" • ")
            ))
        }

        c.drawText("© HoraTrack • FICHE DE PAIE EXEMPLE — ESTIMATION HORATRACK", 28f, 816f, muted)
        pdf.finishPage(page)
        pdf.writeTo(output)
        pdf.close()
    }

    private fun duration(ms: Long): String {
        val m = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh%02d", m / 60L, m % 60L)
    }
    private fun fmt(v: Double): String = String.format(Locale.FRANCE, "%.2f", v)
}
