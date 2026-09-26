package com.amaury.pointage

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.NetSalaryReferencePolicyV2
import com.amaury.pointage.v2.SalaryNumericInputV2
import com.amaury.pointage.v2.V2LegacyPolicy
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.engine.NetSalaryEngineV2
import com.amaury.pointage.v2.engine.WorkSessionRangeV2
import com.amaury.pointage.v2.engine.TimeResultV2
import com.amaury.pointage.v2.engine.WorkSessionEmployerAssignmentV2
import com.amaury.pointage.v2.engine.WorkSessionOverlapV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import java.io.OutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal data class AnnualSalaryGrossResolutionV2(
    val amount: Double?,
    val state: String
)

internal fun resolveAnnualSalaryGrossV2(
    cashGross: Double,
    cashGrossReliable: Boolean,
    paidTimeReliable: Boolean,
    salaryWarnings: List<String>,
    payroll: NetSalaryEngineV2.Result?,
    socialGrossRequired: Boolean,
    upstreamTimeReliable: Boolean
): AnnualSalaryGrossResolutionV2 {
    if (!upstreamTimeReliable || !paidTimeReliable) {
        return AnnualSalaryGrossResolutionV2(null, "Temps payé à confirmer")
    }
    if (!cashGrossReliable) return AnnualSalaryGrossResolutionV2(null, "Brut à confirmer")

    val amount = if (socialGrossRequired) {
        payroll?.let(NetSalaryReferencePolicyV2::socialGross)
    } else {
        cashGross.takeIf { it.isFinite() && it >= 0.0 }
    }
    val state = when {
        amount == null -> "Brut social à confirmer"
        salaryWarnings.isNotEmpty() -> "À confirmer"
        else -> "OK"
    }
    return AnnualSalaryGrossResolutionV2(amount, state)
}

internal data class AnnualTimeResolutionV2(
    val presenceMs: Long?,
    val paidWorkMs: Long?,
    val paidPauseMs: Long?,
    val unpaidPauseMs: Long?,
    val reliable: Boolean
)

internal fun resolveAnnualTimeV2(
    results: List<TimeResultV2>,
    aggregateReliable: Boolean
): AnnualTimeResolutionV2 {
    if (!aggregateReliable || results.any { !it.reliable }) {
        return AnnualTimeResolutionV2(null, null, null, null, reliable = false)
    }
    return AnnualTimeResolutionV2(
        presenceMs = results.sumOf { it.presenceMs },
        paidWorkMs = results.sumOf { it.paidWorkMs },
        paidPauseMs = results.sumOf { it.paidPauseMs },
        unpaidPauseMs = results.sumOf { it.unpaidPauseMs },
        reliable = true
    )
}

internal fun resolveAnnualOvertimeV2(
    overtimeDurationsMs: List<Long>?,
    monthlyGrossReliable: Boolean,
    paidTimeReliable: Boolean,
    upstreamTimeReliable: Boolean
): Long? = overtimeDurationsMs
    ?.takeIf { monthlyGrossReliable && paidTimeReliable && upstreamTimeReliable }
    ?.sumOf { it.coerceAtLeast(0L) }

internal fun resolveAnnualPaidWorkV2(
    paidWorkMs: Long?,
    companyScoped: Boolean,
    paidTimeReliable: Boolean?
): Long? = paidWorkMs
    ?.takeIf { !companyScoped || paidTimeReliable == true }

internal fun resolveAnnualDurationTotalV2(monthlyDurationsMs: List<Long?>): Long? =
    monthlyDurationsMs
        .takeIf { months -> months.all { it != null } }
        ?.sumOf { it!! }

internal fun crossesAnnualReportBoundaryV2(
    session: WorkSessionV2,
    rangeStartMs: Long,
    rangeEndMs: Long,
    openEndMs: Long?
): Boolean {
    if (!touchesAnnualReportRangeV2(session, rangeStartMs, rangeEndMs, openEndMs)) return false
    val interval = WorkSessionRangeV2.effectiveInterval(session, openEndMs) ?: return true
    return interval.startMs < rangeStartMs || interval.endMs > rangeEndMs
}

internal fun touchesAnnualReportRangeV2(
    session: WorkSessionV2,
    rangeStartMs: Long,
    rangeEndMs: Long,
    openEndMs: Long?
): Boolean {
    return WorkSessionRangeV2.potentiallyTouches(session, rangeStartMs, rangeEndMs, openEndMs)
}

internal fun annualWorkSessionEndpointsReliableV2(session: WorkSessionV2): Boolean =
    session.realArrivalMs != null &&
        (session.status == SessionStatusV2.OPEN || session.realExitMs != null)

object AnnualPdfReports {

    /** Entrée canonique V2 : aucun chargement ni adaptateur PointageStore. */
    fun writeWork(context: Context, year: Int, out: OutputStream) {
        check(HoraTrackV2.ENABLED) { "L'export annuel V2 exige le moteur V2 actif" }
        writeWorkV2(context, year, out)
    }

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
        writeWorkV2(context, year, out)
    }

    private fun writeWorkV2(context: Context, year: Int, out: OutputStream) {
        val runtimeSessions = V2RuntimeReader.allSessions(context).requireReliable()
        val reportNowMs = System.currentTimeMillis()
        val sessions = runtimeSessions.filter { session ->
            val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
            Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }.get(Calendar.YEAR) == year
        }
        val calculatedSessions = sessions.map { it to HoraTrackV2.time.calculate(it, reportNowMs) }
        val yearRange = yearRange(year)
        val annualBoundaryCrossing = runtimeSessions.any { session ->
            crossesAnnualReportBoundaryV2(
                session = session,
                rangeStartMs = yearRange.first,
                rangeEndMs = yearRange.second,
                openEndMs = reportNowMs
            )
        }
        val annualEndpointsReliable = runtimeSessions.none { session ->
            touchesAnnualReportRangeV2(
                session = session,
                rangeStartMs = yearRange.first,
                rangeEndMs = yearRange.second,
                openEndMs = reportNowMs
            ) && !annualWorkSessionEndpointsReliableV2(session)
        }
        val monthlyBoundaryCrossing = (0..11).any { month ->
            val periodRange = monthRange(year, month)
            runtimeSessions.any { session ->
                crossesAnnualReportBoundaryV2(
                    session = session,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            }
        }
        val annualTime = resolveAnnualTimeV2(
            results = calculatedSessions.map { it.second },
            aggregateReliable = !annualBoundaryCrossing &&
                annualEndpointsReliable &&
                !monthlyBoundaryCrossing &&
                !WorkSessionOverlapV2.hasSameEmployerOverlap(
                    sessions = runtimeSessions,
                    rangeStartMs = yearRange.first,
                    rangeEndMs = yearRange.second,
                    openEndMs = reportNowMs
                )
        )

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        PdfVisualStyle.header(canvas, 595, "BILAN ANNUEL DU TEMPS DE TRAVAIL — $year", "AGKGMG • Synthèse annuelle")

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
        var totalSessions = 0

        for (month in 0..11) {
            val monthSessions = calculatedSessions.filter { (session, _) ->
                val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
                Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }.get(Calendar.MONTH) == month
            }
            val periodRange = monthRange(year, month)
            val monthBoundaryCrossing = runtimeSessions.any { session ->
                crossesAnnualReportBoundaryV2(
                    session = session,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            }
            val monthEndpointsReliable = runtimeSessions.none { session ->
                touchesAnnualReportRangeV2(
                    session = session,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                ) && !annualWorkSessionEndpointsReliableV2(session)
            }
            val monthTime = resolveAnnualTimeV2(
                results = monthSessions.map { it.second },
                aggregateReliable = !monthBoundaryCrossing &&
                    monthEndpointsReliable &&
                    !WorkSessionOverlapV2.hasSameEmployerOverlap(
                    sessions = runtimeSessions,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            )
            val days = monthSessions.mapNotNull { (session, _) ->
                val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@mapNotNull null
                Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }.let {
                    it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR)
                }
            }.distinct().size
            val label = monthLabel(year, month)
            val values = arrayOf(
                label,
                days.toString(),
                monthTime.presenceMs?.let(::dur) ?: "À confirmer",
                monthTime.paidWorkMs?.let(::dur) ?: "À confirmer",
                monthTime.paidPauseMs?.let(::dur) ?: "À confirmer",
                monthTime.unpaidPauseMs?.let(::dur) ?: "À confirmer",
                monthSessions.size.toString()
            )
            values.forEachIndexed { i, value -> canvas.drawText(value, xs[i], y + 13, normal) }
            y += 23f

            totalDays += days
            totalSessions += monthSessions.size
        }

        y += 8f
        canvas.drawRect(30f, y, 565f, y + 62, fill)
        canvas.drawText("TOTAL ANNÉE", 38f, y + 17, header)
        canvas.drawText(
            "$totalDays jours • présence ${annualTime.presenceMs?.let(::dur) ?: "à confirmer"} • payé ${annualTime.paidWorkMs?.let(::dur) ?: "à confirmer"}",
            38f,
            y + 35,
            header
        )
        canvas.drawText(
            "pauses payées ${annualTime.paidPauseMs?.let(::dur) ?: "à confirmer"} • pauses déduites ${annualTime.unpaidPauseMs?.let(::dur) ?: "à confirmer"} • $totalSessions sessions",
            38f,
            y + 52,
            header
        )
        PdfVisualStyle.footer(canvas, 595, 842, 1)
        pdf.finishPage(page)
        pdf.writeTo(out)
        pdf.close()
    }

    /**
     * Entrée historique conservée pour compatibilité. Les nouveaux appels doivent fournir
     * explicitement l'entreprise V2 afin de ne jamais mélanger deux contrats.
     */
    fun writeSalary(context: Context, data: JSONArray, year: Int, out: OutputStream) {
        writeSalary(context, data, year, out, company = null)
    }

    /** Entrée canonique V2 : aucun chargement ni adaptateur PointageStore. */
    fun writeSalary(
        context: Context,
        year: Int,
        out: OutputStream,
        company: SalaryCompanyStore.Company?
    ) {
        check(HoraTrackV2.ENABLED) { "L'export annuel V2 exige le moteur V2 actif" }
        writeSalaryV2(context, year, out, company)
    }

    /** Estimation annuelle de rémunération limitée à l'entreprise V2 sélectionnée. */
    fun writeSalary(
        context: Context,
        data: JSONArray,
        year: Int,
        out: OutputStream,
        company: SalaryCompanyStore.Company?
    ) {
        if (!HoraTrackV2.ENABLED) {
            writeSalaryLegacy(context, data, year, out)
            return
        }
        writeSalaryV2(context, year, out, company)
    }

    private fun writeSalaryV2(
        context: Context,
        year: Int,
        out: OutputStream,
        company: SalaryCompanyStore.Company?
    ) {
        val runtimeSessions = V2RuntimeReader.allSessions(context).requireReliable()
        val reportNowMs = System.currentTimeMillis()
        val legacyPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val legacyProfile = if (company == null) V2ProfileStore.load(context, 1) else null
        val companyPrefs = company?.let { SalaryCompanyStore.prefs(context, it.id) }
        val acceptedEmployerIds = when {
            company != null -> SalaryCompanyStore.acceptedEmployerIds(context, company.id)
            legacyProfile?.employer?.id != null -> setOf(legacyProfile.employer!!.id)
            else -> emptySet()
        }
        val rate = if (company != null) {
            SalaryNumericInputV2.positiveDecimal(companyPrefs?.getString("hourly_rate", "").orEmpty())
        } else {
            legacyProfile?.contract?.grossHourlyRate ?: prefDouble(legacyPrefs.all["hourly_rate"])
        }
        val idcc = if (company != null) {
            company.idcc.ifBlank { companyPrefs?.getString("company_idcc", "").orEmpty() }
        } else {
            legacyProfile?.employer?.collectiveAgreementId
                ?: legacyPrefs.getString("company_idcc", "").orEmpty().ifBlank {
                    legacyPrefs.getString("convention_idcc", "").orEmpty()
                }
        }
        val convention = idcc.takeIf { it.isNotBlank() }?.let(ConventionCatalog::findByIdcc)
        val euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        PdfVisualStyle.header(canvas, 595, "ESTIMATION ANNUELLE DE RÉMUNÉRATION — $year", "Document indicatif • AGKGMG")
        val header = PdfVisualStyle.boldPaint(9.2f)
        val normal = PdfVisualStyle.bodyPaint(8.7f)
        val small = PdfVisualStyle.bodyPaint(7.9f)
        val fill = Paint().apply { color = PdfVisualStyle.panel }
        var y = 82f

        canvas.drawText("Cette estimation ne remplace pas un bulletin de paie.", 30f, y, normal)
        y += 15f
        company?.let {
            canvas.drawText("Entreprise : ${it.name.ifBlank { "Non renseignée" }}${it.siret.takeIf(String::isNotBlank)?.let { s -> " • SIRET $s" }.orEmpty()}", 30f, y, small)
            y += 15f
        }
        y += 3f
        canvas.drawRect(30f, y, 565f, y + 24, fill)
        val xs = floatArrayOf(34f, 150f, 255f, 350f, 455f)
        arrayOf("Mois", "Heures payées", "HS / compl.", "Brut estimé", "État des règles")
            .forEachIndexed { i, value -> canvas.drawText(value, xs[i], y + 16, header) }
        y += 30f

        val monthlyPaid = mutableListOf<Long?>()
        val monthlyOvertime = mutableListOf<Long?>()
        var annualGross = 0.0
        var grossMonths = 0
        var ruleWarnings = 0

        for (month in 0..11) {
            val monthSessions = runtimeSessions.filter { session ->
                val anchor = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
                val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }
                val correctEmployer = acceptedEmployerIds.isEmpty() || session.employerId in acceptedEmployerIds
                correctEmployer && c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month && session.realExitMs != null
            }
            val timeResults = monthSessions.map { HoraTrackV2.time.calculate(it) }
            val periodRange = monthRange(year, month)
            val employerSessions = if (acceptedEmployerIds.isEmpty()) {
                runtimeSessions
            } else {
                runtimeSessions.filter { it.employerId in acceptedEmployerIds }
            }
            val boundaryCrossingSession = employerSessions.any { session ->
                crossesAnnualReportBoundaryV2(
                    session = session,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            }
            val openEmployerSession = employerSessions.any { session ->
                session.status == SessionStatusV2.OPEN && touchesAnnualReportRangeV2(
                    session = session,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            }
            val closedSessionWithoutRealExit = employerSessions.any { session ->
                session.status != SessionStatusV2.OPEN &&
                    session.realExitMs == null &&
                    touchesAnnualReportRangeV2(
                        session = session,
                        rangeStartMs = periodRange.first,
                        rangeEndMs = periodRange.second,
                        openEndMs = reportNowMs
                    )
            }
            val overlappingSessions = if (acceptedEmployerIds.isEmpty()) {
                WorkSessionOverlapV2.hasSameEmployerOverlap(
                    sessions = monthSessions,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            } else {
                WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
                    sessions = runtimeSessions,
                    acceptedEmployerIds = acceptedEmployerIds,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            }
            val unassignedEmployerSession = acceptedEmployerIds.isNotEmpty() &&
                WorkSessionEmployerAssignmentV2.hasUnassignedSession(
                    sessions = runtimeSessions,
                    rangeStartMs = periodRange.first,
                    rangeEndMs = periodRange.second,
                    openEndMs = reportNowMs
                )
            val timeResolution = resolveAnnualTimeV2(
                results = timeResults,
                aggregateReliable = !boundaryCrossingSession &&
                    !openEmployerSession &&
                    !closedSessionWithoutRealExit &&
                    !overlappingSessions &&
                    !unassignedEmployerSession
            )

            val salaryNet = if (company != null && convention != null) {
                runCatching {
                    V2SalaryNetBridgeV2.calculateForCompany(
                        context = context,
                        company = company,
                        year = year,
                        month = month,
                        convention = convention
                    )
                }.getOrNull()
            } else null
            val salary = when {
                company != null -> salaryNet?.salary
                company == null && rate != null && rate > 0.0 && convention != null && legacyProfile?.contract != null -> runCatching {
                    V2SalaryAdapter.calculate(context, year, month, rate, convention)
                }.getOrNull()
                else -> null
            }
            val paid = resolveAnnualPaidWorkV2(
                paidWorkMs = timeResolution.paidWorkMs,
                companyScoped = company != null,
                paidTimeReliable = salary?.paidTimeReliable
            )
            val overtime = resolveAnnualOvertimeV2(
                overtimeDurationsMs = salary?.overtimeTiers?.map { it.durationMs },
                monthlyGrossReliable = salary?.monthlyGrossReliable == true,
                paidTimeReliable = salary?.paidTimeReliable == true,
                upstreamTimeReliable = timeResolution.reliable
            )
            val grossResolution = salary?.let { reliable ->
                resolveAnnualSalaryGrossV2(
                    cashGross = reliable.monthlyEstimatedGross,
                    cashGrossReliable = reliable.monthlyGrossReliable,
                    paidTimeReliable = reliable.paidTimeReliable,
                    salaryWarnings = reliable.warnings,
                    payroll = salaryNet?.payroll,
                    socialGrossRequired = company != null,
                    upstreamTimeReliable = timeResolution.reliable
                )
            }
            val gross = grossResolution?.amount
            val state = when {
                !timeResolution.reliable -> "Temps payé à confirmer"
                convention == null -> "Convention à confirmer"
                salary == null -> "Contrat à compléter"
                else -> grossResolution!!.state
            }
            if (state != "OK") ruleWarnings++

            val values = arrayOf(
                monthLabel(year, month),
                paid?.let(::dur) ?: "À confirmer",
                overtime?.let(::dur) ?: "À confirmer",
                gross?.let(euro::format) ?: "—",
                state
            )
            values.forEachIndexed { i, value -> canvas.drawText(value, xs[i], y + 13, if (i == 4) small else normal) }
            y += 23f

            monthlyPaid += paid
            monthlyOvertime += overtime
            if (gross != null) {
                annualGross += gross
                grossMonths++
            }
        }

        y += 8f
        val annualPaid = resolveAnnualDurationTotalV2(monthlyPaid)
        val annualOvertime = resolveAnnualDurationTotalV2(monthlyOvertime)
        canvas.drawRect(30f, y, 565f, y + 64, fill)
        canvas.drawText("TOTAL ANNÉE", 38f, y + 17, header)
        canvas.drawText(
            "Temps payé : ${annualPaid?.let(::dur) ?: "à confirmer"} • HS / compl. calculées : ${annualOvertime?.let(::dur) ?: "à confirmer"}",
            38f,
            y + 35,
            header
        )
        canvas.drawText(
            if (grossMonths > 0) "Brut social estimé cumulé sur $grossMonths mois calculables : ${euro.format(annualGross)}" else "Brut annuel non calculé : fiche Salaire ou règles à compléter",
            38f,
            y + 52,
            header
        )
        y += 80f
        if (ruleWarnings > 0) {
            canvas.drawText("$ruleWarnings mois comportent une règle manquante ou à confirmer : AGKGMG n'a appliqué aucune valeur par défaut.", 30f, y, small)
        } else {
            canvas.drawText("Calcul basé uniquement sur le contrat, la convention confirmée et les sessions AGKGMG de l'employeur sélectionné.", 30f, y, small)
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
        PdfVisualStyle.header(canvas, 595, "BILAN ANNUEL DU TEMPS DE TRAVAIL — $year", "AGKGMG • mode rollback")
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
        PdfVisualStyle.header(canvas, 595, "ESTIMATION ANNUELLE DE RÉMUNÉRATION — $year", "AGKGMG • mode rollback")
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

    private fun monthRange(year: Int, month: Int): Pair<Long, Long> {
        val start = Calendar.getInstance(Locale.FRANCE).apply {
            clear()
            set(year, month, 1, 0, 0, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    private fun yearRange(year: Int): Pair<Long, Long> {
        val start = Calendar.getInstance(Locale.FRANCE).apply {
            clear()
            set(year, Calendar.JANUARY, 1, 0, 0, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    private fun prefDouble(value: Any?): Double? = when (value) {
        is Number -> SalaryNumericInputV2.positiveDecimal(value.toDouble())
        is String -> SalaryNumericInputV2.positiveDecimal(value)
        else -> null
    }

    private fun dur(ms: Long): String {
        val min = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", min / 60L, min % 60L)
    }
}
