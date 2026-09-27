package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.TimeZone

/**
 * Snapshot de faits V2, avec une preuve distincte d'exhaustivité sur un intervalle fermé.
 * Un lecteur de stockage fiable ne prouve PAS à lui seul cette exhaustivité.
 * sourceId doit identifier l'import/journal confirmé ; aucune confirmation n'est créée ici.
 * Les identifiants employeur sont canoniques, jamais des alias libres ou le nom d'une société.
 */
data class SegmentedPayrollSessionSourceV2(
    val employerId: String,
    val sessions: List<WorkSessionV2>,
    val sourceId: String,
    val reliable: Boolean,
    val exhaustive: Boolean,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val warnings: List<String> = emptyList()
)

/** La tranche entière doit être celle de la timeline canonique, pas seulement le même numéro. */
data class SegmentedPayrollPremiumEvidenceV2(
    val slice: PayrollCalculationSliceV2,
    val sourceId: String,
    val reliable: Boolean,
    val nightRule: NightPremiumRuleV2?,
    val holidayScope: FrenchPublicHolidayCalendarV2.Scope?
)

data class SegmentedPayrollSessionEvidenceResultV2(
    val slices: List<SegmentedPayrollSliceEvidenceV2>,
    val reliable: Boolean,
    val warnings: List<String>,
    val sourceId: String,
    val contributingSessionIds: List<String>
)

/**
 * Faits réels V2 -> preuves B21. Aucune formule de brut ni déduction de pause parallèle.
 * Réutilise la timeline, le scope de sessions, l'allocation payée et les politiques de majoration.
 *
 * Une semaine de bord est lue EN ENTIER. Si elle contient du temps payé hors de la tranche,
 * B21 ne sait pas encore en allouer le montant : on bloque, sans tronquer ses seuils ni payer
 * une seconde fois les minutes extérieures. Cette restriction n'est pas une règle salariale.
 * Le chemin non segmenté existant et les guards B19 ne sont ni remplacés ni assouplis.
 */
object SegmentedPayrollSessionEvidenceBuilderV2 {
    const val SOURCE_WARNING = "Preuves B21 : lecture ou couverture exhaustive des pointages non confirmée."
    const val SCOPE_WARNING = "Preuves B21 : employeur, provenance ou plage de lecture incohérents."
    const val RULE_WARNING = "Preuves B21 : règles temporelles de la tranche absentes ou non confirmées."
    const val SESSION_WARNING = "Preuves B21 : identifiant de pointage absent ou dupliqué."
    const val EDGE_WARNING = "Preuves B21 : temps payé hors de la tranche dans une semaine de bord ; allocation monétaire non prouvée."
    const val HOLIDAY_WARNING = "Preuves B21 : jour férié nécessitant une règle dédiée ou un périmètre confirmé."
    const val CALENDAR_WARNING = "Preuves B21 : dates, fuseau ou durée hors du domaine vérifiable."
    const val TRAVEL_WARNING = "Preuves B21 : déplacements distincts de la session non encore alloués dans cette chaîne."

    fun build(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        source: SegmentedPayrollSessionSourceV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long
    ): SegmentedPayrollSessionEvidenceResultV2 {
        val warnings = source.warnings.toMutableList()
        fun blocked(message: String) = SegmentedPayrollSessionEvidenceResultV2(
            emptyList(), false, (warnings + message).distinct(), source.sourceId, emptyList()
        )
        if (!source.reliable || !source.exhaustive) return blocked(SOURCE_WARNING)
        val employer = source.employerId.trim()
        if (employer.isEmpty() || employer != contracts.employerId.trim() ||
            source.sourceId.isBlank() || source.checkedAtMs > nowMs ||
            source.coveredEndEpochDay < source.coveredStartEpochDay
        ) return blocked(SCOPE_WARNING)
        val targetFacts = source.sessions.filter { it.employerId?.trim() == employer }
        if (targetFacts.any { it.id.isBlank() } ||
            targetFacts.map { it.id.trim() }.distinct().size != targetFacts.size) return blocked(SESSION_WARNING)
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        warnings += timeline.warnings
        if (!timeline.reliable || timeline.slices.isEmpty()) return blocked(SCOPE_WARNING)
        if (timeline.slices.any { it.contractSnapshot.contract.employerId.trim() != employer }) {
            return blocked(SCOPE_WARNING)
        }
        if (premiums.map { it.slice }.toSet() != timeline.slices.toSet() ||
            premiums.size != timeline.slices.size || premiums.map { it.slice }.distinct().size != premiums.size ||
            premiums.any { !it.reliable || it.sourceId.isBlank() }
        ) return blocked(RULE_WARNING)
        val bySlice = premiums.associateBy { it.slice }
        val output = mutableListOf<SegmentedPayrollSliceEvidenceV2>()
        val usedIds = linkedSetOf<String>()
        try {
            val zone = ZoneId.of(source.timeZoneId)
            val timeZone = TimeZone.getTimeZone(zone)
            for (slice in timeline.slices) {
                val start = LocalDate.ofEpochDay(slice.startEpochDay)
                val end = LocalDate.ofEpochDay(slice.endEpochDay)
                if (start.year !in 1900..2200 || end.year !in 1900..2200 || end < start) {
                    return blocked(CALENDAR_WARNING)
                }
                val context = bySlice.getValue(slice)
                val payrollRules = slice.ruleSnapshot.rules
                if (listOfNotNull(payrollRules.nightMultiplier, payrollRules.saturdayMultiplier,
                        payrollRules.sundayMultiplier, payrollRules.publicHolidayMultiplier).any { !it.isFinite() || it < 1.0 }) {
                    return blocked(RULE_WARNING)
                }
                val nightMultiplier = payrollRules.nightMultiplier
                if (nightMultiplier != null && (context.nightRule == null ||
                        kotlin.math.abs(nightMultiplier - context.nightRule.multiplier) > 0.000_001)) {
                    return blocked(RULE_WARNING)
                }
                val holidayScope = context.holidayScope ?: return blocked(HOLIDAY_WARNING)
                if (!holidayScope.complete) return blocked(HOLIDAY_WARNING)
                val sliceStart = startOfDay(start, zone)
                val sliceEnd = startOfDay(end.plusDays(1), zone)
                var monday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val weeks = mutableListOf<SegmentedPayrollWeekEvidenceV2>()
                val sliceWarnings = mutableListOf<String>()
                while (monday <= end) {
                    val nextMonday = monday.plusWeeks(1)
                    if (monday.toEpochDay() < source.coveredStartEpochDay ||
                        nextMonday.minusDays(1).toEpochDay() > source.coveredEndEpochDay) {
                        return blocked(SOURCE_WARNING)
                    }
                    val from = startOfDay(monday, zone)
                    val to = startOfDay(nextMonday, zone)
                    if (to <= from || to > source.checkedAtMs) return blocked(SOURCE_WARNING)
                    if (monday.year !in 1900..2200 || nextMonday.minusDays(1).year !in 1900..2200) return blocked(CALENDAR_WARNING)
                    val scope = MonthlyPaidWorkScopeV2.resolve(source.sessions, setOf(employer), from, to, nowMs)
                    warnings += scope.warnings
                    if (!scope.reliable) return blocked(SOURCE_WARNING)
                    if (scope.selected.any { it.id.isBlank() } ||
                        scope.selected.map { it.id }.distinct().size != scope.selected.size) {
                        return blocked(SESSION_WARNING)
                    }
                    if (scope.selected.any { it.travels.isNotEmpty() }) return blocked(TRAVEL_WARNING)
                    if (scope.selected.any { (it.countedExitMs ?: Long.MAX_VALUE) > source.checkedAtMs ||
                            (it.realExitMs ?: Long.MAX_VALUE) > source.checkedAtMs }) return blocked(SOURCE_WARNING)
                    if (scope.selected.isNotEmpty() && nightMultiplier != null &&
                        !nightBoundsReliable(monday, zone, context.nightRule!!)) return blocked(CALENDAR_WARNING)
                    // Les API existantes fournissent les durées ; cette couche ne fait que les grouper.
                    var paid = 0L; var night = 0L; var saturday = 0L; var sunday = 0L; var holiday = 0L
                    val years = (monday.year..nextMonday.minusDays(1).year)
                    val holidayDates = years.flatMap { FrenchPublicHolidayCalendarV2.genericHolidays(it, holidayScope) }.toSet()
                    val dedicatedDates = years.map { FrenchPublicHolidayCalendarV2.mayFirst(it) }.toSet()
                    for (session in scope.selected) {
                        val full = PaidWorkAllocationV2.paidOverlapResult(session, from, to)
                        val insideStart = maxOf(from, sliceStart)
                        val insideEnd = minOf(to, sliceEnd)
                        val inside = PaidWorkAllocationV2.paidOverlapResult(session, insideStart, insideEnd)
                        if (!full.reliable || !inside.reliable) return blocked(SOURCE_WARNING)
                        if (full.paidMs != inside.paidMs) return blocked(EDGE_WARNING)
                        if (PublicHolidayPremiumPolicyV2.paidOverlap(session, from, to, dedicatedDates, timeZone) > 0) {
                            return blocked(HOLIDAY_WARNING)
                        }
                        paid = Math.addExact(paid, full.paidMs / 60_000L)
                        if (nightMultiplier != null) night = Math.addExact(night,
                            NightPremiumPolicyV2.paidOverlap(session, from, to, context.nightRule!!, timeZone) / 60_000L)
                        saturday = Math.addExact(saturday, PublicHolidayPremiumPolicyV2.paidOverlap(
                            session, from, to, setOf(monday.plusDays(5)), timeZone) / 60_000L)
                        sunday = Math.addExact(sunday, PublicHolidayPremiumPolicyV2.paidOverlap(
                            session, from, to, setOf(monday.plusDays(6)), timeZone) / 60_000L)
                        holiday = Math.addExact(holiday, PublicHolidayPremiumPolicyV2.paidOverlap(
                            session, from, to, holidayDates, timeZone) / 60_000L)
                        usedIds += session.id
                    }
                    val week = PayrollWeekV2(Math.toIntExact(paid), Math.toIntExact(night),
                        Math.toIntExact(saturday), Math.toIntExact(sunday), Math.toIntExact(holiday))
                    weeks += SegmentedPayrollWeekEvidenceV2(monday.get(WeekFields.ISO.weekBasedYear()),
                        monday.get(WeekFields.ISO.weekOfWeekBasedYear()), week, fullWeekContextReliable = true)
                    sliceWarnings += scope.warnings
                    monday = nextMonday
                }
                output += SegmentedPayrollSliceEvidenceV2(slice.startEpochDay, slice.endEpochDay,
                    slice.contractVersionId, slice.ruleVersionId, weeks, true, true, true, sliceWarnings.distinct())
            }
        } catch (_: java.time.DateTimeException) { return blocked(CALENDAR_WARNING)
        } catch (_: ArithmeticException) { return blocked(CALENDAR_WARNING)
        } catch (_: IllegalArgumentException) { return blocked(CALENDAR_WARNING) }
        return SegmentedPayrollSessionEvidenceResultV2(output, true, warnings.distinct(), source.sourceId, usedIds.toList())
    }

    private fun startOfDay(day: LocalDate, zone: ZoneId): Long {
        val value = day.atStartOfDay(zone)
        require(value.toLocalDate() == day) { "Date civile inexistante dans ce fuseau" }
        return value.toInstant().toEpochMilli()
    }

    // Aligner le refus des bornes locales ambiguës/inexistantes sur la politique iOS existante.
    private fun nightBoundsReliable(monday: LocalDate, zone: ZoneId, rule: NightPremiumRuleV2): Boolean {
        for (offset in -1L..8L) {
            val day = monday.plusDays(offset)
            val start = day.atTime(rule.startMinute / 60, rule.startMinute % 60)
            val endDay = if (rule.endMinute <= rule.startMinute) day.plusDays(1) else day
            val end = endDay.atTime(rule.endMinute / 60, rule.endMinute % 60)
            if (zone.rules.getValidOffsets(start).size != 1 || zone.rules.getValidOffsets(end).size != 1) return false
        }
        return true
    }

    /** Le résultat global est transporté : transmettre seulement pieces perdrait ses avertissements. */
    fun calculateVariables(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        source: SegmentedPayrollSessionSourceV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long
    ): SegmentedWorkedVariableGrossSourceResultV2 {
        val proof = build(contracts, rules, source, premiums, nowMs)
        if (!proof.reliable) return SegmentedWorkedVariableGrossSourceResultV2(emptyList(), false, proof.warnings)
        val result = SegmentedWorkedVariableGrossSourceV2.calculate(contracts, rules, proof.slices)
        return result.copy(warnings = (proof.warnings + result.warnings).distinct())
    }
}
