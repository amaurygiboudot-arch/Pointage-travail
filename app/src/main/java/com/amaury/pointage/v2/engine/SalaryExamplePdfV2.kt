package com.amaury.pointage.v2.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.PdfVisualStyle
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.V2SalaryNetBridgeV2
import com.amaury.pointage.V2SalaryNetPresentationV2
import com.amaury.pointage.v2.BoccPayrollSourceStoreV2
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.NetSalaryReferencePolicyV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import com.amaury.pointage.v2.V2EmploymentContractPayrollBridge
import com.amaury.pointage.v2.V2SalaryCalculationRoute
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.V2SegmentedSalaryCanonicalBridge
import com.amaury.pointage.v2.V2RightsStore
import java.io.OutputStream
import java.text.DateFormatSymbols
import java.time.ZoneId
import java.util.Locale

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

    internal data class ContractDisplayValues(
        val typeLabel: String?,
        val contractualWeeklyMinutes: Int?,
        val grossHourlyRate: Double?,
        val monthlyGrossSalary: Double?
    )

    internal fun contractDisplayValues(contract: ContractV2?): ContractDisplayValues =
        ContractDisplayValues(
            typeLabel = contract?.type?.name,
            contractualWeeklyMinutes = contract?.contractualWeeklyMinutes,
            grossHourlyRate = contract?.grossHourlyRate,
            monthlyGrossSalary = contract?.monthlyGrossSalary
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
        val periodContract = if (company != null) {
            V2EmploymentContractPayrollBridge.resolve(
                context = context,
                companyId = company.id,
                year = year,
                monthZeroBased = month
            ).resolution.contract
        } else {
            legacyContract
        }
        val contractDisplay = contractDisplayValues(periodContract)
        val rate = contractDisplay.grossHourlyRate

        val companyName = company?.name?.takeIf { it.isNotBlank() }
            ?: legacyEmployer?.name?.takeIf { it.isNotBlank() }
            ?: "À compléter"
        val companySiret = company?.siret?.takeIf { it.isNotBlank() }
            ?: legacyEmployer?.siret?.takeIf { it.isNotBlank() }
            ?: "À compléter"
        val idcc = if (company != null) {
            company.idcc.trim()
        } else legacyEmployer?.collectiveAgreementId?.trim().orEmpty()
        val convention = idcc.takeIf { it.isNotBlank() }
            ?.let { ConventionCatalog.findByIdcc(context, it) }
            ?.takeIf { it.idcc.isNotBlank() }

        val route = company?.let { V2SalaryCalculationRoute.resolve(context,it,year,month) }
        val segmentedConsumer = company != null && route != V2SalaryCalculationRoute.Route.MONTHLY
        val canonical = when {
            company == null || !HoraTrackV2.ENABLED || route != V2SalaryCalculationRoute.Route.SEGMENTED -> null
            else -> runCatching {
                V2SegmentedSalaryCanonicalBridge.calculateForCompany(
                    context = context,
                    company = company,
                    year = year,
                    monthZeroBased = month,
                    timeZoneId = ZoneId.systemDefault().id
                ).output
            }.getOrNull()
        }
        val salaryNet = if (company != null && convention != null && HoraTrackV2.ENABLED &&
            route == V2SalaryCalculationRoute.Route.MONTHLY) runCatching {
                V2SalaryNetBridgeV2.calculateForCompany(context,company,year,month,convention)
            }.getOrNull() else null
        val salary = when {
            salaryNet != null -> salaryNet.salary
            company != null || !HoraTrackV2.ENABLED || convention == null -> null
            rate != null -> runCatching {
                V2SalaryAdapter.calculate(context, year, month, rate, convention)
            }.getOrNull()
            else -> null
        }
        val payrollReferenceDate = PayrollPeriodV2.month(year, month).referenceDate
        val payroll = canonical?.net?.projection?.payroll ?: salaryNet?.payroll

        val timeSection = if (segmentedConsumer) timeSectionValues(canonical)
            else timeSectionValues(salary, salary?.unpaidPauseMs)
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

        val monthName = DateFormatSymbols(Locale.FRANCE).months.getOrNull(month).orEmpty().replaceFirstChar { it.uppercase() }
        val sections = buildList {
            if (Field.COMPANY in fields) add(PdfSection("EMPLOYEUR", listOf(
                "Entreprise" to companyName,
                "SIRET" to companySiret,
                "Convention / régime" to if (convention != null) convention.displayName else "À confirmer"
            )))

            if (Field.CONTRACT in fields) {
                add(PdfSection("CONTRAT", buildList {
                    add("Type" to (contractDisplay.typeLabel?.replace('_', ' ') ?: "À confirmer"))
                    add("Durée hebdomadaire" to (contractDisplay.contractualWeeklyMinutes?.let { "%dh%02d".format(Locale.FRANCE, it / 60, it % 60) } ?: "À confirmer"))
                    add("Taux horaire brut" to (contractDisplay.grossHourlyRate?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "À confirmer"))
                    contractDisplay.monthlyGrossSalary?.let {
                        add("Salaire brut mensuel convenu" to String.format(Locale.FRANCE, "%.2f €", it))
                    }
                }))
            }

            if (Field.HOURS in fields) add(PdfSection("TEMPS DE TRAVAIL", listOf(
                "Sessions terminées" to timeSection.completedSessions,
                "Temps payé" to timeSection.paidTime,
                "Heures normales" to timeSection.regularHours,
                "Heures supplémentaires" to timeSection.overtimeHours
            )))

            if (Field.PAUSES in fields) add(PdfSection("PAUSES", listOf(
                "Pauses non payées déduites" to timeSection.unpaidPauses,
                "Méthode" to "Intervalles V2 confirmés sur l'entreprise sélectionnée"
            )))

            if (Field.ESTIMATED_GROSS in fields) {
                add(PdfSection("ESTIMATION DE RÉMUNÉRATION",
                    if (segmentedConsumer) estimatedGrossLines(canonical) else estimatedGrossLines(salary, salaryNet)))
                add(PdfSection("COTISATIONS — SALARIÉ / EMPLOYEUR", contributionLines(payroll?.takeIf {
                    if (segmentedConsumer) canonical?.cashGrossReliable == true
                    else salaryNet?.salary?.let { it.monthlyGrossReliable && it.paidTimeReliable } == true
                })))
            }

            if (Field.COUNTERS in fields) {
                add(PdfSection("COMPTEURS", if (counters.isEmpty()) listOf("Droits" to "Aucun compteur renseigné") else counters.flatMap { balance ->
                    buildList {
                        balance.acquired?.let { add("${balance.label} — acquis" to "${fmt(it)} ${balance.unit}") }
                        balance.available?.let { add("${balance.label} — disponible" to "${fmt(it)} ${balance.unit}") }
                        balance.taken?.let { add("${balance.label} — pris" to "${fmt(it)} ${balance.unit}") }
                        balance.anticipated?.let { add("${balance.label} — anticipé" to "${fmt(it)} ${balance.unit}") }
                        balance.remaining?.let { add("${balance.label} — restant" to "${fmt(it)} ${balance.unit}") }
                    }
                }))
            }

            if (Field.SOURCES in fields) {
                val warningSections = warningSections(
                    salaryWarnings = if (segmentedConsumer) canonical?.warnings.orEmpty()
                        else salaryNet?.warnings ?: salary?.warnings.orEmpty(),
                    payrollWarnings = emptyList(),
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
                add(PdfSection("SOURCES & CONTRÔLES", buildList {
                    add("Source des heures" to "Moteur AGKGMG V2")
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
                }))
            }
        }

        val body = PdfVisualStyle.bodyPaint(7.5f)
        val tableSections = presentationSections(sections)
        val wrappedTables = tableSections.map { section ->
            wrapTableRows(section) { text, width -> wrapText(text, body, width) }
        }
        // The existing planner splits even very long wrapped rows without discarding cells.
        val tablesByName = wrappedTables.associateBy { it.name }
        val pages = paginateSections(wrappedTables.map { section ->
            PdfSection(section.name, section.rows.indices.map { it.toString() to "" })
        }, sectionHeaderHeight = 42f, rowHeight = 14f, sectionTailHeight = 14f, keepSectionsTogether = false)
        val pdf = PdfDocument()
        val heading = PdfVisualStyle.boldPaint(8.5f).apply { color = Color.WHITE }
        val columnHeading = PdfVisualStyle.boldPaint(7f)
        val muted = PdfVisualStyle.bodyPaint(8f).apply { color = Color.rgb(95, 95, 95) }
        val green = Paint().apply { color = Color.rgb(11, 119, 119) }
        val stripe = Paint().apply { color = Color.rgb(244, 246, 246) }
        val rule = Paint().apply { color = Color.rgb(210, 218, 218); strokeWidth = 0.5f }
        try {
            pages.forEachIndexed { pageIndex, plannedPage ->
                val pageNumber = pageIndex + 1
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                val canvas = page.canvas
                PdfVisualStyle.header(canvas, 595, "FICHE DE SALAIRE — ESTIMATION", "")
                canvas.drawText("$monthName $year • document personnel non officiel • page $pageNumber/${pages.size}", 28f, 82f, muted)
                var y = PDF_CONTENT_TOP
                plannedPage.fragments.forEach { fragment ->
                    val table = tablesByName.getValue(fragment.name)
                    canvas.drawRect(28f, y - 11f, 567f, y + 9f, green)
                    canvas.drawText(if (fragment.continuation) "${fragment.name} (suite)" else fragment.name, 36f, y + 2f, heading)
                    y += 25f
                    var x = 28f
                    table.headers.forEachIndexed { index, label ->
                        canvas.drawText(label, x + 6f, y, columnHeading)
                        x += table.widths[index]
                    }
                    canvas.drawLine(28f, y + 5f, 567f, y + 5f, rule)
                    y += 17f
                    fragment.lines.forEachIndexed { rowIndex, (rowId, _) ->
                        val cells = table.rows[rowId.toInt()]
                        val netPay = cells.first().startsWith("Net estimé après PAS")
                        if (netPay || rowIndex % 2 == 0) canvas.drawRect(28f, y - 10f, 567f, y + 4f,
                            if (netPay) Paint().apply { color = Color.rgb(223, 240, 238) } else stripe)
                        val cellPaint = if (netPay) PdfVisualStyle.boldPaint(7.5f) else body
                        x = 28f
                        cells.forEachIndexed { index, cell ->
                            canvas.drawText(cell, x + 6f, y, cellPaint)
                            if (index > 0) canvas.drawLine(x, y - 10f, x, y + 4f, rule)
                            x += table.widths[index]
                        }
                        canvas.drawLine(28f, y + 4f, 567f, y + 4f, rule)
                        y += 14f
                    }
                    y += 14f
                }
                PdfVisualStyle.footer(canvas, 595, 842, pageNumber)
                pdf.finishPage(page)
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    internal data class PresentationTable(
        val name: String,
        val headers: List<String>,
        val widths: List<Float>,
        val rows: List<List<String>>
    )

    internal fun wrapTableRows(
        table: PresentationTable,
        wrap: (String, Float) -> List<String>
    ): PresentationTable = table.copy(rows = table.rows.flatMap { cells ->
        require(cells.size == table.widths.size)
        val wrapped = cells.mapIndexed { index, cell -> wrap(cell, table.widths[index] - 12f) }
        List(wrapped.maxOf { it.size }) { index -> wrapped.map { it.getOrElse(index) { "" } } }
    })

    internal fun presentationSections(sections: List<PdfSection>): List<PresentationTable> {
        fun simple(name: String, rows: List<Pair<String, String>>) = PresentationTable(
            name, listOf("Rubrique", "Montant / information"), listOf(300f, 239f),
            rows.map { listOf(it.first, it.second) }
        )
        val tables = sections.flatMap { section ->
            when (section.name) {
                "ESTIMATION DE RÉMUNÉRATION" -> {
                    val remuneration = mutableListOf<Pair<String, String>>()
                    val expenses = mutableListOf<Pair<String, String>>()
                    val net = mutableListOf<Pair<String, String>>()
                    val employer = mutableListOf<Pair<String, String>>()
                    section.lines.forEach { row ->
                        when {
                            row.first.startsWith("Paniers") -> expenses += row
                            row.first.startsWith("Net") || row.first.startsWith("Prélèvement") ||
                                row.first.startsWith("Avantages en nature non") -> net += row
                            row.first.startsWith("Réductions") || row.first.startsWith("Sous-total patronal") -> employer += row
                            else -> remuneration += row
                        }
                    }
                    listOf(simple("RÉMUNÉRATION BRUTE", remuneration),
                        simple("PANIERS ET FRAIS", expenses), simple("SYNTHÈSE DU NET", net),
                        simple("SYNTHÈSE EMPLOYEUR", employer)).filter { it.rows.isNotEmpty() }
                }
                "COTISATIONS — SALARIÉ / EMPLOYEUR" -> {
                    val details = section.lines.filterNot { it.first == "Lecture des montants" ||
                        it.first.startsWith("Fiabilité") || it.first == "Bases et taux détaillés" ||
                        it.first.startsWith("Sous-total") || it.first.startsWith("Réductions") }
                    val notes = section.lines.filter { it.first.startsWith("Fiabilité") || it.first == "Bases et taux détaillés" }
                    listOf(PresentationTable(section.name,
                        listOf("Rubrique", "Base", "Taux sal.", "Part sal.", "Taux emp.", "Part emp."),
                        listOf(209f, 66f, 66f, 66f, 66f, 66f),
                        details.map { contributionCells(it.first, it.second) }),
                        simple("FIABILITÉ DES COTISATIONS", notes))
                }
                else -> listOf(simple(section.name, section.lines))
            }
        }.toMutableList()
        val employerTotals = sections.filter { it.name == "COTISATIONS — SALARIÉ / EMPLOYEUR" }
            .flatMap { it.lines }.filter { it.first.startsWith("Sous-total") || it.first.startsWith("Réductions") }
        if (employerTotals.isNotEmpty()) {
            val existingIndex = tables.indexOfFirst { it.name == "SYNTHÈSE EMPLOYEUR" }
            if (existingIndex < 0) tables += simple("SYNTHÈSE EMPLOYEUR", employerTotals)
            else {
                val existing = tables[existingIndex]
                val labels = existing.rows.map { it.first() }.toSet()
                tables[existingIndex] = existing.copy(rows = existing.rows + employerTotals
                    .filter { it.first !in labels }.map { listOf(it.first, it.second) })
            }
        }
        val salaryNames = listOf("RÉMUNÉRATION BRUTE", "COTISATIONS — SALARIÉ / EMPLOYEUR",
            "PANIERS ET FRAIS", "SYNTHÈSE DU NET", "SYNTHÈSE EMPLOYEUR", "FIABILITÉ DES COTISATIONS")
        val salaryTables = tables.filter { it.name in salaryNames }.sortedBy { salaryNames.indexOf(it.name) }
        val firstSalary = tables.indexOfFirst { it.name in salaryNames }
        if (firstSalary < 0) return tables
        return tables.take(firstSalary) + salaryTables + tables.drop(firstSalary).filter { it.name !in salaryNames }
    }

    internal fun contributionCells(label: String, value: String): List<String> {
        val pair = value.split(" / ", limit = 2)
        val employeeOnly = label == "Retenues propres à l'entreprise"
        val employee = if (pair.size == 2 || employeeOnly) pair[0] else "—"
        val employer = if (pair.size == 2) pair[1] else if (employeeOnly) "—" else value
        return listOf(label, "À confirmer", "À confirmer", employee,
            "À confirmer", employer)
    }

    private fun wrapText(text: String, paint: Paint, width: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            var remaining = paragraph
            while (paint.measureText(remaining) > width) {
                val count = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
                val space = remaining.lastIndexOf(' ', count - 1)
                val end = if (space > 0) space else count
                lines += remaining.substring(0, end)
                remaining = remaining.substring(end).trimStart()
            }
            lines += remaining
        }
        return lines
    }

    internal fun contributionLines(payroll: NetSalaryEngineV2.Result?): List<Pair<String, String>> {
        fun amount(value: Double?, confirmed: Boolean = payroll?.employerCostComplete == true): String = value?.takeIf {
            confirmed && payroll?.grossReliable == true && it.isFinite() && it >= 0.0
        }?.let(::money) ?: "À confirmer"
        fun row(label: String, employee: Double?, employer: Double?) =
            label to "${amount(employee, payroll?.complete == true)} / ${amount(employer)}"
        return listOf(
            "Lecture des montants" to "Part salarié / part employeur",
            "Socle légal (sécurité sociale, CSG/CRDS)" to "${amount(payroll?.statutory, payroll?.complete == true)} / ${amount(payroll?.statutoryEmployerContributions)}",
            row("Retraite complémentaire", payroll?.complementaryRetirement, payroll?.complementaryRetirementEmployer),
            row("Prévoyance conventionnelle", payroll?.conventionProvidentEmployee, payroll?.conventionProvidentEmployer),
            "Retenues propres à l'entreprise" to amount(payroll?.companyEmployeeDeductions, payroll?.complete == true),
            "Cotisations patronales liées au statut" to amount(payroll?.employerStatusContributions),
            "Accidents du travail / maladies professionnelles" to amount(payroll?.employerAtMpContribution),
            "Versement mobilité (employeur)" to amount(payroll?.employerMobilityContribution),
            "Assurance chômage (employeur)" to amount(payroll?.employerUnemploymentContribution),
            "AGS (employeur)" to amount(payroll?.employerAgsContribution),
            "FNAL (employeur)" to amount(payroll?.employerFnalContribution),
            "Formation professionnelle (employeur)" to amount(payroll?.employerTrainingContribution),
            "Maladie (employeur)" to amount(payroll?.employerHealthContribution),
            "Famille (employeur)" to amount(payroll?.employerFamilyContribution),
            "Apprentissage — part principale" to amount(payroll?.employerApprenticeshipPrincipalContribution),
            "Apprentissage — provision du solde" to amount(payroll?.employerApprenticeshipBalanceAccrual),
            "Réductions / exonérations patronales" to amount(payroll?.confirmedEmployerReductions),
            "Sous-total patronal connu avant réductions" to amount(payroll?.knownEmployerContributions),
            "Sous-total patronal connu après réductions" to amount(payroll?.knownEmployerContributionsAfterReductions),
            "Fiabilité des retenues salarié" to if (payroll?.complete == true) "Calcul complet" else "À confirmer — calcul partiel",
            "Fiabilité du coût employeur" to if (payroll?.employerCostComplete == true) "Calcul complet" else "À confirmer — sous-total partiel",
            "Bases et taux détaillés" to "Non exposés par ce moteur ; à confirmer"
        )
    }

    internal data class TimeSectionValues(
        val completedSessions: String,
        val paidTime: String,
        val regularHours: String,
        val overtimeHours: String,
        val unpaidPauses: String
    )

    internal data class PdfSection(
        val name: String,
        val lines: List<Pair<String, String>>
    )

    internal data class PdfSectionFragment(
        val name: String,
        val lines: List<Pair<String, String>>,
        val continuation: Boolean
    )

    internal data class PdfPagePlan(val fragments: List<PdfSectionFragment>)

    internal fun paginateSections(
        sections: List<PdfSection>,
        contentTop: Float = PDF_CONTENT_TOP,
        contentBottom: Float = PDF_CONTENT_BOTTOM,
        sectionHeaderHeight: Float = PDF_SECTION_HEADER_HEIGHT,
        rowHeight: Float = PDF_ROW_HEIGHT,
        sectionTailHeight: Float = PDF_SECTION_TAIL_HEIGHT,
        keepSectionsTogether: Boolean = true
    ): List<PdfPagePlan> {
        require(contentBottom > contentTop)
        require(sectionHeaderHeight > 0f && rowHeight > 0f && sectionTailHeight >= 0f)
        val pageCapacity = contentBottom - contentTop
        require(sectionHeaderHeight + rowHeight + sectionTailHeight <= pageCapacity)

        val pages = mutableListOf<PdfPagePlan>()
        var fragments = mutableListOf<PdfSectionFragment>()
        var usedHeight = 0f

        fun finishPage() {
            if (fragments.isNotEmpty()) pages += PdfPagePlan(fragments.toList())
            fragments = mutableListOf()
            usedHeight = 0f
        }

        sections.filter { it.lines.isNotEmpty() }.forEach { section ->
            val wholeHeight = sectionHeaderHeight + section.lines.size * rowHeight + sectionTailHeight
            if (keepSectionsTogether && wholeHeight <= pageCapacity) {
                if (fragments.isNotEmpty() && usedHeight + wholeHeight > pageCapacity) finishPage()
                fragments += PdfSectionFragment(section.name, section.lines, continuation = false)
                usedHeight += wholeHeight
            } else {
                var firstLine = 0
                while (firstLine < section.lines.size) {
                    val available = pageCapacity - usedHeight
                    val maxRows = ((available - sectionHeaderHeight - sectionTailHeight) / rowHeight).toInt()
                    if (maxRows < 1) {
                        finishPage()
                        continue
                    }
                    val endExclusive = (firstLine + maxRows).coerceAtMost(section.lines.size)
                    fragments += PdfSectionFragment(
                        name = section.name,
                        lines = section.lines.subList(firstLine, endExclusive),
                        continuation = firstLine > 0
                    )
                    usedHeight += sectionHeaderHeight + (endExclusive - firstLine) * rowHeight + sectionTailHeight
                    firstLine = endExclusive
                    if (firstLine < section.lines.size) finishPage()
                }
            }
        }
        finishPage()
        return pages.ifEmpty { listOf(PdfPagePlan(emptyList())) }
    }

    private const val PDF_CONTENT_TOP = 100f
    private const val PDF_CONTENT_BOTTOM = 790f
    private const val PDF_SECTION_HEADER_HEIGHT = 15f
    private const val PDF_ROW_HEIGHT = 14f
    private const val PDF_SECTION_TAIL_HEIGHT = 23f

    internal fun estimatedGrossLines(
        canonical: SegmentedSalaryCanonicalOutputV2?
    ): List<Pair<String, String>> {
        val payroll = canonical?.net?.projection?.payroll
        val reliablePayrollGross =
            canonical?.cashGrossReliable == true &&
                payroll?.grossReliable == true
        val socialGross = payroll
            ?.takeIf { reliablePayrollGross }
            ?.let(NetSalaryReferencePolicyV2::socialGross)

        return buildList {
            add(
                "Brut social estimé AGKGMG hors paniers" to
                    (socialGross?.let(::money) ?: "À confirmer")
            )
            if (reliablePayrollGross && (payroll?.benefitsInKindDeduction ?: 0.0) > 0.0) {
                add("Dont avantages en nature" to money(payroll!!.benefitsInKindDeduction))
            }
            add(
                "Majoration heures supplémentaires" to
                    (canonical?.overtimeGross
                        ?.takeIf { canonical.workedGrossReliable && it.isFinite() && it >= 0.0 }
                        ?.let(::money)
                        ?: "À confirmer")
            )

            // Le contrat segmenté ne porte pas encore les paniers : ne jamais relire l'ancien moteur
            // pour compléter ce champ dans un PDF autrement canonique.
            add("Paniers hors brut" to "À confirmer")

            if (reliablePayrollGross && (payroll?.benefitsInKindDeduction ?: 0.0) > 0.0) {
                add("Avantages en nature non versés en espèces" to "-${money(payroll!!.benefitsInKindDeduction)}")
            }

            val netComplete = canonical?.netBeforeIncomeTaxComplete == true
            add("Net estimé avant impôt" to
                (canonical?.netBeforeIncomeTax?.takeIf { netComplete }?.let(::money) ?: "À confirmer"))
            add("Net imposable estimé" to
                (canonical?.netTaxable?.takeIf { netComplete }?.let(::money) ?: "À confirmer"))
            add("Prélèvement à la source" to
                (canonical?.incomeTax?.takeIf { netComplete }?.let { "-${money(it)}" } ?: "À confirmer"))
            add("Net estimé après PAS" to
                (canonical?.netAfterIncomeTax?.takeIf { netComplete }?.let(::money) ?: "À confirmer"))

            add(
                "Réductions / exonérations patronales" to
                    (payroll
                        ?.takeIf { reliablePayrollGross }
                        ?.confirmedEmployerReductions
                        ?.let(::money)
                        ?: "À confirmer")
            )
            add(
                "Sous-total patronal connu après réductions" to
                    (payroll
                        ?.takeIf { reliablePayrollGross }
                        ?.knownEmployerContributionsAfterReductions
                        ?.let(::money)
                        ?: "À confirmer")
            )
        }
    }

    internal fun estimatedGrossLines(
        salary: V2SalaryAdapter.Result?,
        salaryNet: V2SalaryNetBridgeV2.Result?
    ): List<Pair<String, String>> {
        val resolvedSalary = salaryNet?.salary ?: salary
        val payroll = salaryNet?.payroll
        val reliableSalary = resolvedSalary?.monthlyGrossReliable == true && resolvedSalary.paidTimeReliable
        val reliablePayrollGross = reliableSalary && payroll?.grossReliable == true
        val presentation = salaryNet?.let(V2SalaryNetPresentationV2::from)
        return buildList {
            add(
                "Brut social estimé AGKGMG hors paniers" to
                    (payroll
                        ?.takeIf { reliablePayrollGross }
                        ?.let(NetSalaryReferencePolicyV2::socialGross)
                        ?.let(::money)
                        ?: "À confirmer")
            )
            if (reliablePayrollGross && (payroll?.benefitsInKindDeduction ?: 0.0) > 0.0) {
                add("Dont avantages en nature" to money(payroll!!.benefitsInKindDeduction))
            }
            add(
                "Majoration heures supplémentaires" to
                    (resolvedSalary?.takeIf { reliableSalary }?.overtimeGross?.let(::money) ?: "À confirmer")
            )
            if (resolvedSalary?.paidTimeReliable != true) {
                add("Paniers hors brut" to "À confirmer")
            } else {
                resolvedSalary.mealBasketTotal?.let { total ->
                    val amount = resolvedSalary.mealBasketAmount
                    add(
                        "Paniers hors brut" to
                            if (amount != null) "${resolvedSalary.mealBasketCount} × ${money(amount)} = ${money(total)}"
                            else money(total)
                    )
                }
            }
            if (reliablePayrollGross && (payroll?.benefitsInKindDeduction ?: 0.0) > 0.0) {
                add("Avantages en nature non versés en espèces" to "-${money(payroll!!.benefitsInKindDeduction)}")
            }

            add("Net estimé avant impôt" to (presentation?.primaryAmount?.let(::money) ?: "À confirmer"))
            add("Net imposable estimé" to (presentation?.taxableAmount?.let(::money) ?: "À confirmer"))
            add("Prélèvement à la source" to (presentation?.incomeTaxAmount?.let { "-${money(it)}" } ?: "À confirmer"))
            add("Net estimé après PAS" to (presentation?.secondaryAmount?.let(::money) ?: "À confirmer"))

            add(
                "Réductions / exonérations patronales" to
                    (payroll
                        ?.takeIf { reliablePayrollGross }
                        ?.confirmedEmployerReductions
                        ?.let(::money)
                        ?: "À confirmer")
            )
            add(
                "Sous-total patronal connu après réductions" to
                    (payroll
                        ?.takeIf { reliablePayrollGross }
                        ?.knownEmployerContributionsAfterReductions
                        ?.let(::money)
                        ?: "À confirmer")
            )
        }
    }

    internal fun timeSectionValues(
        canonical: SegmentedSalaryCanonicalOutputV2?
    ): TimeSectionValues {
        if (canonical?.paidTimeReliable != true || canonical.paidMinutes == null) {
            return TimeSectionValues(
                completedSessions = "À confirmer",
                paidTime = "À confirmer",
                regularHours = "À confirmer",
                overtimeHours = "À confirmer",
                unpaidPauses = "À confirmer"
            )
        }
        return TimeSectionValues(
            completedSessions = canonical.worked.evidence.contributingSessionIds.size.toString(),
            paidTime = duration(canonical.paidMinutes.toLong() * 60_000L),
            regularHours = "À confirmer",
            overtimeHours = "À confirmer",
            unpaidPauses = "À confirmer"
        )
    }

    internal fun timeSectionValues(
        salary: V2SalaryAdapter.Result?,
        unpaidPauseMs: Long?
    ): TimeSectionValues {
        if (salary?.paidTimeReliable != true || unpaidPauseMs == null) {
            return TimeSectionValues(
                completedSessions = "À confirmer",
                paidTime = "À confirmer",
                regularHours = "À confirmer",
                overtimeHours = "À confirmer",
                unpaidPauses = "À confirmer"
            )
        }
        return TimeSectionValues(
            completedSessions = salary.completedSessions.toString(),
            paidTime = duration(salary.totalWorkedMs),
            regularHours = duration(salary.regularMs),
            overtimeHours = salary.overtimeTiers
                .joinToString { "${it.label}: ${duration(it.durationMs)}" }
                .ifBlank { "Aucune règle confirmée applicable" },
            unpaidPauses = duration(unpaidPauseMs)
        )
    }

    private fun duration(ms: Long): String {
        val m = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh%02d", m / 60L, m % 60L)
    }

    private fun money(value: Double): String = String.format(Locale.FRANCE, "%.2f €", value)

    private fun fmt(v: Double): String = String.format(Locale.FRANCE, "%.2f", v)
}
