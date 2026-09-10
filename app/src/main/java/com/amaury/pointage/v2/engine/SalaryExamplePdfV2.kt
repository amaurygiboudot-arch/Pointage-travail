package com.amaury.pointage.v2.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.PdfVisualStyle
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.BoccPayrollSourceStoreV2
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.NetSalaryReferencePolicyV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeReader
import java.io.OutputStream
import java.text.DateFormatSymbols
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Génère une vraie page PDF d'estimation, visuellement structurée comme un bulletin. */
object SalaryExamplePdfV2 {
    enum class Field { COMPANY, CONTRACT, HOURS, PAUSES, ESTIMATED_GROSS, COUNTERS, SOURCES }

    internal data class WarningSections(
        val salaryAndNet: List<String>,
        val employerCost: List<String>
    )

    internal data class SourceStatus(
        val summary: String,
        val references: String
    )

    internal fun warningSections(
        salaryWarnings: List<String>,
        payrollWarnings: List<String>,
        employerCostWarnings: List<String>
    ): WarningSections = WarningSections(
        salaryAndNet = (salaryWarnings + payrollWarnings).distinct(),
        employerCost = employerCostWarnings.distinct()
    )

    internal fun legalSourceStatus(
        reliable: Boolean,
        coveredTopics: Int,
        totalTopics: Int,
        references: List<String>
    ): SourceStatus {
        if (!reliable) {
            return SourceStatus(
                summary = "Stockage local incohérent — aucune référence fiable utilisée",
                references = "Indisponibles : stockage LEGI non fiable"
            )
        }
        if (references.isEmpty()) {
            return SourceStatus(
                summary = "Non vérifié pour cette date",
                references = "Non vérifié pour la date de paie"
            )
        }
        return SourceStatus(
            summary = "$coveredTopics/$totalTopics thèmes vérifiés",
            references = if (references.size <= 6) {
                references.joinToString(", ")
            } else {
                references.take(6).joinToString(", ") + " +${references.size - 6}"
            }
        )
    }

    internal fun normalizeBoccIdcc(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank() || raw.any { !it.isDigit() }) return null
        return raw.toIntOrNull()?.takeIf { it > 0 }?.toString()
    }

    internal fun boccSourceStatus(
        configurationIssue: String?,
        reliable: Boolean,
        references: List<String>
    ): SourceStatus {
        if (configurationIssue != null) {
            return SourceStatus(
                summary = configurationIssue,
                references = configurationIssue
            )
        }
        if (!reliable) {
            return SourceStatus(
                summary = "Stockage local incohérent — aucune référence fiable utilisée",
                references = "Indisponibles : stockage BOCC non fiable"
            )
        }
        if (references.isEmpty()) {
            return SourceStatus(
                summary = "Non vérifiées pour cette entreprise et cette date",
                references = "Non vérifié pour cette entreprise et cette date"
            )
        }
        return SourceStatus(
            summary = "${references.size} référence(s) PDF officielle(s) vérifiée(s)",
            references = if (references.size <= 4) {
                references.joinToString(", ")
            } else {
                references.take(4).joinToString(", ") + " +${references.size - 4}"
            }
        )
    }

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
        val payrollReferenceDate = PayrollPeriodV2.month(year, month).referenceDate
        val payroll = if (company != null && salary?.monthlyGrossReliable == true) {
            val overrides = CompanyPayrollOverridesV2.load(context, company.id, payrollReferenceDate)
            runCatching {
                NetSalaryEngineV2.calculate(
                    salary.monthlyEstimatedGross,
                    year,
                    overrides,
                    salary.complementaryMinutes
                )
            }.getOrNull()
        } else null

        val acceptedEmployerIds = if (company != null) {
            SalaryCompanyStore.acceptedEmployerIds(context, company.id)
        } else legacyContract?.let { setOf(it.employerId) }.orEmpty()
        val sessions = V2RuntimeReader.allSessions(context).requireReliable().filter { session ->
            val at = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
            val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = at }
            session.employerId in acceptedEmployerIds &&
                cal.get(Calendar.YEAR) == year &&
                cal.get(Calendar.MONTH) == month
        }
        val pauseMs = sessions.sumOf { HoraTrackV2.time.calculate(it).unpaidPauseMs }
        val counters = if (company != null) V2RightsStore.forCompany(context, company.id) else V2RightsStore.all(context)
        val legalReferenceAtMs = payrollReferenceDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val legalSnapshot = LegalPayrollSourceStoreV2.snapshot(context, legalReferenceAtMs)
        val normalizedBoccIdcc = normalizeBoccIdcc(idcc)
        val boccConfigurationIssue = when {
            company == null -> "Entreprise à confirmer"
            idcc.isBlank() -> "IDCC à confirmer"
            normalizedBoccIdcc == null -> "IDCC invalide — à corriger"
            else -> null
        }
        val boccSnapshot = if (boccConfigurationIssue == null) {
            BoccPayrollSourceStoreV2.snapshotResult(
                context,
                company!!.id,
                legalReferenceAtMs,
                normalizedBoccIdcc!!
            )
        } else null

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
                add(
                    "Brut social estimé HoraTrack hors paniers" to
                        (payroll?.gross?.let { String.format(Locale.FRANCE, "%.2f €", it) }
                            ?: salary?.takeIf { it.monthlyGrossReliable }?.monthlyEstimatedGross?.let { String.format(Locale.FRANCE, "%.2f €", it) }
                            ?: "À confirmer")
                )
                if ((payroll?.benefitsInKindDeduction ?: 0.0) > 0.0) {
                    add("Dont avantages en nature" to String.format(Locale.FRANCE, "%.2f €", payroll!!.benefitsInKindDeduction))
                }
                add("Majoration heures supplémentaires" to (salary?.overtimeGross?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À confirmer"))
                salary?.mealBasketTotal?.let { total ->
                    val count = salary.mealBasketCount
                    val amount = salary.mealBasketAmount
                    add("Paniers hors brut" to if (amount != null) "$count × ${String.format(Locale.FRANCE, "%.2f €", amount)} = ${String.format(Locale.FRANCE, "%.2f €", total)}" else String.format(Locale.FRANCE, "%.2f €", total))
                }
                payroll?.let {
                    if (it.benefitsInKindDeduction > 0.0) {
                        add("Avantages en nature non versés en espèces" to "-${String.format(Locale.FRANCE, "%.2f €", it.benefitsInKindDeduction)}")
                    }
                    val reliableNet = NetSalaryReferencePolicyV2.beforeIncomeTax(it)
                    if (reliableNet != null) {
                        add("Net estimé avant impôt" to String.format(Locale.FRANCE, "%.2f €", reliableNet))
                        it.netTaxable?.let { value -> add("Net imposable estimé" to String.format(Locale.FRANCE, "%.2f €", value)) }
                    } else {
                        add("Net estimé avant impôt" to "À confirmer")
                        add("Sous-total net sur retenues connues" to String.format(Locale.FRANCE, "%.2f €", it.netBeforeIncomeTax))
                    }
                    add(
                        "Réductions / exonérations patronales" to
                            (it.confirmedEmployerReductions?.let { value -> String.format(Locale.FRANCE, "%.2f €", value) }
                                ?: "À confirmer")
                    )
                    add(
                        "Sous-total patronal connu après réductions" to
                            (it.knownEmployerContributionsAfterReductions?.let { value -> String.format(Locale.FRANCE, "%.2f €", value) }
                                ?: "À confirmer")
                    )
                } ?: add("Cotisations / net" to "Affichés uniquement quand leurs sources applicables sont déterminées")
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
            val warningSections = warningSections(
                salaryWarnings = salary?.warnings.orEmpty(),
                payrollWarnings = payroll?.warnings.orEmpty(),
                employerCostWarnings = payroll?.employerCostWarnings.orEmpty()
            )
            val legalRefs = legalSnapshot.records
                .map { it.articleNumber?.takeIf(String::isNotBlank) ?: it.articleId }
                .filter(String::isNotBlank)
                .distinct()
            val legalStatus = legalSourceStatus(
                reliable = legalSnapshot.reliable,
                coveredTopics = legalSnapshot.coveredTopics.size,
                totalTopics = OfficialLegalCodeSourceV2.Topic.entries.size,
                references = legalRefs
            )
            val boccRefs = boccSnapshot?.records.orEmpty()
                .mapNotNull { it.bulletinNumber?.takeIf(String::isNotBlank) ?: it.fileName.takeIf(String::isNotBlank) }
                .distinct()
            val boccStatus = boccSourceStatus(
                configurationIssue = boccConfigurationIssue,
                reliable = boccSnapshot?.reliable ?: true,
                references = boccRefs
            )
            section("SOURCES & CONTRÔLES", buildList {
                add("Source des heures" to "Moteur HoraTrack V2")
                add("Entreprise de calcul" to if (company != null) companyName else "Profil historique principal")
                add("Convention" to if (convention != null) "IDCC ${convention.idcc}" else "À confirmer")
                add("Code du travail — LEGI" to legalStatus.summary)
                add("Références LEGI" to legalStatus.references)
                add("Publications conventionnelles — BOCC" to boccStatus.summary)
                add("Références BOCC" to boccStatus.references)
                add(
                    "Contrôles salaire / net" to
                        if (warningSections.salaryAndNet.isEmpty()) "Aucun avertissement moteur"
                        else warningSections.salaryAndNet.joinToString(" • ")
                )
                if (payroll != null) {
                    add(
                        "Contrôles coût employeur" to when {
                            warningSections.employerCost.isNotEmpty() -> warningSections.employerCost.joinToString(" • ")
                            payroll.employerCostComplete -> "Aucun avertissement coût employeur"
                            else -> "Coût employeur non certifié"
                        }
                    )
                }
            })
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
