package com.amaury.pointage.v2.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.PdfVisualStyle
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeStore
import java.io.OutputStream
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Génère une vraie page PDF d'estimation, visuellement structurée comme un bulletin. */
object SalaryExamplePdfV2 {
    enum class Field { COMPANY, CONTRACT, HOURS, PAUSES, ESTIMATED_GROSS, COUNTERS, SOURCES }

    /**
     * Point d'entrée historique conservé pendant la migration.
     * Les nouveaux écrans multi-entreprises doivent utiliser la surcharge avec [company].
     */
    fun write(context: Context, year: Int, month: Int, fields: Set<Field>, output: OutputStream) {
        writeInternal(context, null, year, month, fields, output)
    }

    /** Génère le PDF depuis l'entreprise V2 explicitement sélectionnée. */
    fun write(
        context: Context,
        company: SalaryCompanyStore.Company,
        year: Int,
        month: Int,
        fields: Set<Field>,
        output: OutputStream
    ) {
        writeInternal(context, company, year, month, fields, output)
    }

    private fun writeInternal(
        context: Context,
        company: SalaryCompanyStore.Company?,
        year: Int,
        month: Int,
        fields: Set<Field>,
        output: OutputStream
    ) {
        val legacyProfile = if (company == null) V2ProfileStore.load(context, 1) else null
        val legacyContract = legacyProfile?.contract
        val legacyEmployer = legacyProfile?.employer
        val companyPrefs = company?.let { SalaryCompanyStore.prefs(context, it.id) }
        val legacyPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)

        val rawContractType = companyPrefs?.getString("contract_type", "").orEmpty().trim()
        val contractualWeeklyMinutes = if (company != null) {
            companyPrefs?.getString("contract_weekly_hours", "").orEmpty()
                .replace(',', '.')
                .toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { (it * 60.0).roundToInt() }
        } else legacyContract?.contractualWeeklyMinutes
        val rate = if (company != null) {
            companyPrefs?.getString("hourly_rate", "").orEmpty()
                .replace(',', '.')
                .toDoubleOrNull()
                ?.takeIf { it > 0.0 }
        } else legacyContract?.grossHourlyRate
        val monthlyGross = companyPrefs?.getString("monthly_gross_salary", "").orEmpty()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it > 0.0 }
        val mealRaw = if (company != null) {
            companyPrefs?.getString("meal_amount", "").orEmpty().trim()
        } else legacyPrefs.all["meal_amount"]?.toString().orEmpty().trim()

        val companyName = company?.name?.takeIf { it.isNotBlank() }
            ?: legacyEmployer?.name?.takeIf { it.isNotBlank() }
            ?: "À compléter"
        val companySiret = company?.siret?.takeIf { it.isNotBlank() }
            ?: legacyEmployer?.siret?.takeIf { it.isNotBlank() }
            ?: "À compléter"
        val idcc = if (company != null) {
            company.idcc.ifBlank { companyPrefs?.getString("company_idcc", "").orEmpty() }.trim()
        } else legacyEmployer?.collectiveAgreementId?.trim().orEmpty()
        val convention = idcc.takeIf { it.isNotBlank() }
            ?.let { ConventionCatalog.findByIdcc(context, it) }
            ?.takeIf { it.idcc.isNotBlank() }

        val salary = when {
            !HoraTrackV2.ENABLED || convention == null -> null
            company != null -> runCatching {
                V2SalaryAdapter.calculateForCompany(context, company, year, month, convention)
            }.getOrNull()
            rate != null -> runCatching {
                V2SalaryAdapter.calculate(context, year, month, rate, convention)
            }.getOrNull()
            else -> null
        }

        val acceptedEmployerIds = if (company != null) {
            SalaryCompanyStore.acceptedEmployerIds(context, company.id)
        } else legacyContract?.let { setOf(it.employerId) }.orEmpty()
        val sessions = V2RuntimeStore.allSessions(context).filter { session ->
            val at = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
            val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = at }
            session.employerId in acceptedEmployerIds &&
                cal.get(Calendar.YEAR) == year &&
                cal.get(Calendar.MONTH) == month
        }
        val pauseMs = sessions.sumOf { HoraTrackV2.time.calculate(it).unpaidPauseMs }
        val counters = if (company != null) V2RightsStore.forCompany(context, company.id) else V2RightsStore.all(context)

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val title = PdfVisualStyle.boldPaint(16f)
        val bold = PdfVisualStyle.boldPaint(10f)
        val body = PdfVisualStyle.bodyPaint(9f)
        val muted = PdfVisualStyle.bodyPaint(8f).apply { color = Color.rgb(95, 95, 95) }
        val line = Paint(1).apply { color = PdfVisualStyle.line; strokeWidth = 0.8f }
        var y = 42f
        val monthName = DateFormatSymbols(Locale.FRANCE).months.getOrNull(month).orEmpty().replaceFirstChar { it.uppercase() }

        canvas.drawText("FICHE DE PAIE EXEMPLE — ESTIMATION HORATRACK", 28f, y, title)
        y += 18f
        canvas.drawText("$monthName $year • document personnel d'estimation • non officiel", 28f, y, muted)
        y += 18f
        canvas.drawLine(28f, y, 567f, y, line)
        y += 22f

        fun section(name: String, lines: List<Pair<String, String>>) {
            if (lines.isEmpty()) return
            canvas.drawText(name, 28f, y, bold)
            y += 15f
            lines.forEach { (label, value) ->
                canvas.drawText(label, 38f, y, body)
                canvas.drawText(value, 315f, y, body)
                y += 14f
            }
            y += 5f
            canvas.drawLine(28f, y, 567f, y, line)
            y += 18f
        }

        if (Field.COMPANY in fields) {
            section("EMPLOYEUR", listOf(
                "Entreprise" to companyName,
                "SIRET" to companySiret,
                "Convention / régime" to if (convention != null) convention.displayName else "À confirmer"
            ))
        }

        if (Field.CONTRACT in fields) {
            val contractLines = buildList {
                add("Type" to if (company != null) rawContractType.ifBlank { "À compléter" }.replace('_', ' ') else (legacyContract?.type?.name ?: "À compléter"))
                add("Durée hebdomadaire" to (contractualWeeklyMinutes?.let { "%dh%02d".format(Locale.FRANCE, it / 60, it % 60) } ?: "À confirmer"))
                add("Taux horaire brut" to (rate?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À compléter"))
                if (monthlyGross != null) add("Salaire brut mensuel convenu" to String.format(Locale.FRANCE, "%.2f €", monthlyGross))
                add("Panier déclaré" to (mealRaw.takeIf { it.isNotBlank() }?.let { "$it €" } ?: "Non renseigné"))
            }
            section("CONTRAT", contractLines)
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
                "Méthode" to "Intervalles V2 confirmés sur l'entreprise sélectionnée"
            ))
        }

        if (Field.ESTIMATED_GROSS in fields) {
            val estimateLines = buildList {
                add("Brut estimé HoraTrack hors paniers" to (salary?.takeIf { it.monthlyGrossReliable }?.monthlyEstimatedGross?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À confirmer"))
                add("Majoration heures supplémentaires" to (salary?.overtimeGross?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À confirmer"))
                salary?.mealBasketTotal?.let { total ->
                    val count = salary.mealBasketCount
                    val amount = salary.mealBasketAmount
                    add("Paniers non cotisables" to if (amount != null) "$count × ${String.format(Locale.FRANCE, "%.2f €", amount)} = ${String.format(Locale.FRANCE, "%.2f €", total)}" else String.format(Locale.FRANCE, "%.2f €", total))
                }
                add("Cotisations / net" to "Affichés uniquement quand leurs sources applicables sont déterminées")
            }
            section("ESTIMATION DE RÉMUNÉRATION", estimateLines)
        }

        if (Field.COUNTERS in fields) {
            section("COMPTEURS", if (counters.isEmpty()) listOf("Droits" to "Aucun compteur renseigné") else counters.flatMap { balance ->
                buildList {
                    balance.acquired?.let { add("${balance.label} — acquis" to "${fmt(it)} ${balance.unit}") }
                    balance.available?.let { add("${balance.label} — disponible" to "${fmt(it)} ${balance.unit}") }
                    balance.taken?.let { add("${balance.label} — pris" to "${fmt(it)} ${balance.unit}") }
                    balance.anticipated?.let { add("${balance.label} — anticipé" to "${fmt(it)} ${balance.unit}") }
                    balance.remaining?.let { add("${balance.label} — restant" to "${fmt(it)} ${balance.unit}") }
                }
            })
        }

        if (Field.SOURCES in fields) {
            val warnings = salary?.warnings.orEmpty()
            section("SOURCES & CONTRÔLES", listOf(
                "Source des heures" to "Moteur HoraTrack V2",
                "Entreprise de calcul" to if (company != null) companyName else "Profil historique principal",
                "Convention" to if (convention != null) "IDCC ${convention.idcc}" else "À confirmer",
                "Éléments à vérifier" to if (warnings.isEmpty()) "Aucun avertissement moteur" else warnings.joinToString(" • ")
            ))
        }

        canvas.drawText("© HoraTrack • FICHE DE PAIE EXEMPLE — ESTIMATION HORATRACK", 28f, 816f, muted)
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
