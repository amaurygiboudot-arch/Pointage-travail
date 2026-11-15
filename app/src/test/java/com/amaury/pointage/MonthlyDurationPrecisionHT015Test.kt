package com.amaury.pointage

import com.amaury.pointage.v2.engine.CollectivePremiumLegalArbitrationBridgeV2
import com.amaury.pointage.v2.engine.FrenchPublicHolidayCalendarV2
import com.amaury.pointage.v2.engine.NightPremiumRuleV2
import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import com.amaury.pointage.v2.engine.PublicHolidayPremiumRuleV2
import com.amaury.pointage.v2.engine.WeekdayPremiumKindV2
import com.amaury.pointage.v2.engine.WeekdayPremiumRuleV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.TimeBasisV2
import com.amaury.pointage.v2.model.WorkSegmentKindV2
import com.amaury.pointage.v2.model.WorkSegmentV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** Exercises the production monthly adapter, including the monetary consumers of its weeks.
 * Rates and premium snapshots are deliberately fictional confirmed inputs, not legal defaults.
 */
class MonthlyDurationPrecisionHT015Test {
    private lateinit var previousTimeZone: TimeZone
    private val september = LocalDate.of(2026, 9, 1)
    private val sourceMethod = V2SalaryAdapter::class.java.declaredMethods.single {
        it.name == "calculateCore"
    }.apply { isAccessible = true }

    @Before fun setTimeZone() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @After fun restoreTimeZone() = TimeZone.setDefault(previousTimeZone)

    @Test fun historicalTwoHourExampleHasTheSamePaidMinutesAndGrossAfterFragmentation() {
        val whole = session("2026-09-07T09:00:00", 7_280_000L)
        val one = calculate(listOf(whole))
        val split = calculate(fragments(whole, 3_640_000L))
        assertEquivalent(one, split)
        assertEquals(7_280_000L, split.totalWorkedMs)
        assertEquals(121L * 60_000L, split.regularMs)
        assertEquals(121.0, split.regularGross, 0.000001)
        assertTrue(split.paidTimeReliable)
    }

    @Test fun twoFortySecondSessionsInOneWeekProduceOnePayableMinute() {
        val first = session("2026-09-07T09:00:00", 40_000L)
        val second = session("2026-09-08T10:00:00", 40_000L)
        val result = calculate(listOf(first, second))
        assertEquals(80_000L, result.totalWorkedMs)
        assertEquals(60_000L, result.regularMs)
        assertEquals(1.0, result.monthlyEstimatedGross, 0.000001)
    }

    @Test fun millisecondFragmentsAlsoAccumulateBeforeConversion() {
        val whole = session("2026-09-07T09:00:00", 60_001L)
        val result = calculate(fragments(whole, 20_000L, 40_000L))
        assertEquivalent(calculate(listOf(whole)), result)
        assertEquals(60_001L, result.totalWorkedMs)
        assertEquals(1.0, result.regularGross, 0.000001)
    }

    @Test fun saturdayPremiumIsInvariantToFragmentation() = assertPremium("2026-09-12T09:00:00", Premium.SATURDAY)
    @Test fun sundayPremiumIsInvariantToFragmentation() = assertPremium("2026-09-13T09:00:00", Premium.SUNDAY)
    @Test fun nightPremiumIsInvariantToFragmentation() = assertPremium("2026-09-08T21:00:00", Premium.NIGHT)
    @Test fun holidayPremiumIsInvariantToFragmentation() = assertPremium("2026-11-11T09:00:00", Premium.HOLIDAY)

    @Test fun subMinutePremiumRemaindersAreNotTransferredBetweenCategories() {
        val saturday = session("2026-09-12T09:00:00", 40_000L)
        val sunday = session("2026-09-13T09:00:00", 40_000L)
        val result = calculate(listOf(saturday, sunday), premiums = setOf(Premium.SATURDAY, Premium.SUNDAY))
        assertEquals(80_000L, result.totalWorkedMs)
        assertEquals(40_000L, result.saturdayMs)
        assertEquals(40_000L, result.sundayMs)
        assertEquals(1.0, result.regularGross, 0.000001)
        assertEquals(0.0, result.premiumsGross, 0.000001)
    }

    @Test fun fractionsOnOppositeSidesOfMondayRemainInTheirOwnWeeks() {
        val crossing = session("2026-09-13T23:59:20", 80_000L)
        val result = calculate(listOf(crossing))
        assertEquivalent(result, calculate(fragments(crossing, 40_000L)))
        assertEquals(80_000L, result.totalWorkedMs)
        assertEquals(0L, result.regularMs)
        assertEquals(0.0, result.monthlyEstimatedGross, 0.000001)
    }

    @Test fun isoWeekYearTransitionDoesNotMoveRemaindersToAnotherWeek() {
        val crossing = session("2025-12-28T23:59:20", 80_000L)
        val result = calculate(listOf(crossing), month = LocalDate.of(2025, 12, 1))
        assertEquivalent(result, calculate(fragments(crossing, 40_000L), month = LocalDate.of(2025, 12, 1)))
        assertEquals(80_000L, result.totalWorkedMs)
        assertEquals(0.0, result.regularGross, 0.000001)
    }

    @Test fun monthClippingPrecedesAggregationAndDoesNotImportThePreviousMonthRemainder() {
        val crossing = session("2026-01-31T23:59:20", 120_000L)
        val split = fragments(crossing, 40_000L, 80_000L)
        val february = LocalDate.of(2026, 2, 1)
        val feb = calculate(split, month = february)
        assertEquivalent(calculate(listOf(crossing), month = february), feb)
        assertEquals(80_000L, feb.totalWorkedMs)
        assertEquals(1.0, feb.regularGross, 0.000001)
        val january = calculate(split, month = LocalDate.of(2026, 1, 1))
        assertEquals(40_000L, january.totalWorkedMs)
        assertEquals(0.0, january.regularGross, 0.000001)
    }

    @Test fun confirmedUnpaidPauseIsDeductedBeforeWeeklyAggregation() = assertPause(paid = false)
    @Test fun confirmedPaidPauseRemainsPaidBeforeWeeklyAggregation() = assertPause(paid = true)

    @Test fun paidInterSiteTravelAccumulatesWithWorkWhilePersonalJourneysRemainExcluded() {
        val plain = session("2026-09-07T09:00:00", 160_000L)
        val start = plain.countedEntryMs!!
        val qualified = plain.copy(workSegments = listOf(
            WorkSegmentV2(start, start + 40_000L, WorkSegmentKindV2.PERSONAL_TRAVEL),
            WorkSegmentV2(start + 40_000L, start + 80_000L, WorkSegmentKindV2.WORK),
            WorkSegmentV2(start + 80_000L, start + 120_000L, WorkSegmentKindV2.PAID_TRAVEL),
            WorkSegmentV2(start + 120_000L, start + 160_000L, WorkSegmentKindV2.PERSONAL_TRAVEL)
        ))
        val result = calculate(fragments(qualified, 80_000L))
        assertEquivalent(calculate(listOf(qualified)), result)
        assertEquals(80_000L, result.totalWorkedMs)
        assertEquals(1.0, result.regularGross, 0.000001)
        assertTrue(result.paidTimeReliable)
    }

    @Test fun genericOvertimeThresholdUsesTheAccumulatedMinute() {
        val whole = session("2026-09-07T09:00:00", 7_280_000L)
        val contract = contract().copy(contractualWeeklyMinutes = 120)
        val result = calculate(fragments(whole, 3_640_000L), contract = contract, overtimeStart = 120)
        assertEquivalent(calculate(listOf(whole), contract = contract, overtimeStart = 120), result)
        assertEquals(120.0, result.regularGross, 0.000001)
        assertEquals(1.25, result.overtimeGross, 0.000001)
        assertEquals(60_000L, result.overtimeTiers.single().durationMs)
    }

    @Test fun fullTimeVariableOvertimeUsesTheAccumulatedMinute() {
        val whole = session("2026-09-07T09:00:00", 7_280_000L)
        val contract = contract(ContractTypeV2.FULL_TIME).copy(contractualWeeklyMinutes = 120)
        val result = calculate(fragments(whole, 3_640_000L), contract = contract, overtimeStart = 120)
        assertEquivalent(calculate(listOf(whole), contract = contract, overtimeStart = 120), result)
        assertEquals(1.25, result.overtimeGross, 0.000001)
        assertEquals(60_000L, result.overtimeTiers.single().durationMs)
    }

    @Test fun partTimeComplementaryHoursUseTheAccumulatedMinute() {
        val whole = session("2026-09-07T09:00:00", 7_280_000L)
        val contract = contract(ContractTypeV2.PART_TIME).copy(contractualWeeklyMinutes = 120)
        val result = calculate(fragments(whole, 3_640_000L), contract = contract)
        assertEquivalent(calculate(listOf(whole), contract = contract), result)
        assertEquals(1, result.complementaryMinutes)
        assertEquals(1.10, result.overtimeGross, 0.000001)
        assertFalse(result.monthlyGrossReliable) // Existing provisional rate warning is preserved.
    }

    @Test fun allCurrentContractTypesKeepAnExactSubMinuteTrackedTotalWithoutChangingTheirPayBasis() {
        val subMinute = session("2026-09-07T09:00:00", 40_000L)
        val types = listOf(ContractTypeV2.OTHER, ContractTypeV2.FULL_TIME, ContractTypeV2.PART_TIME,
            ContractTypeV2.FORFAIT_HOURS, ContractTypeV2.FORFAIT_DAYS)
        for (type in types) {
            val contract = contract(type)
            val empty = calculate(emptyList(), contract = contract)
            val result = calculate(listOf(subMinute), contract = contract)
            assertEquals(type.name, 40_000L, result.totalWorkedMs)
            assertEquals(type.name, empty.regularMs, result.regularMs)
            assertEquals(type.name, empty.monthlyEstimatedGross, result.monthlyEstimatedGross, 0.000001)
            assertEquals(type.name, 0.0, result.overtimeGross, 0.000001)
        }
    }

    @Test fun daylightSavingTransitionUsesElapsedTimeAndKeepsFragmentationInvariant() {
        val whole = session("2026-10-25T01:59:40", 7_280_000L)
        val month = LocalDate.of(2026, 10, 1)
        val result = calculate(fragments(whole, 3_640_000L), month = month, premiums = setOf(Premium.NIGHT))
        assertEquivalent(calculate(listOf(whole), month = month, premiums = setOf(Premium.NIGHT)), result)
        assertEquals(7_280_000L, result.totalWorkedMs)
        assertEquals(30.25, result.premiumsGross, 0.000001)
    }

    private fun assertPremium(at: String, premium: Premium) {
        val whole = session(at, 7_280_000L)
        val month = LocalDate.parse(at.substring(0, 10)).withDayOfMonth(1)
        val result = calculate(fragments(whole, 3_640_000L), month, premiums = setOf(premium))
        assertEquivalent(calculate(listOf(whole), month, premiums = setOf(premium)), result)
        val categoryMs = when (premium) {
            Premium.NIGHT -> result.nightMs
            Premium.SATURDAY -> result.saturdayMs
            Premium.SUNDAY -> result.sundayMs
            Premium.HOLIDAY -> result.publicHolidayMs
        }
        assertEquals(7_280_000L, categoryMs)
        assertEquals(30.25, result.premiumsGross, 0.000001)
        assertEquals(151.25, result.monthlyEstimatedGross, 0.000001)
    }

    private fun assertPause(paid: Boolean) {
        val whole = session("2026-09-07T09:00:00", 200_000L).let { s ->
            val start = s.countedEntryMs!!
            s.copy(pauses = listOf(PauseV2(start + 60_000L,
                start + 80_000L, paid, EventSourceV2.MANUAL)))
        }
        val result = calculate(fragments(whole, 100_000L))
        assertEquivalent(calculate(listOf(whole)), result)
        assertEquals(if (paid) 200_000L else 180_000L, result.totalWorkedMs)
        assertEquals(if (paid) 0L else 20_000L, result.unpaidPauseMs!!)
        assertEquals(3.0, result.regularGross, 0.000001)
        assertTrue(result.paidTimeReliable)
    }

    private fun assertEquivalent(expected: V2SalaryAdapter.Result, actual: V2SalaryAdapter.Result) {
        assertEquals(expected.totalWorkedMs, actual.totalWorkedMs)
        assertEquals(expected.regularMs, actual.regularMs)
        assertEquals(expected.overtimeTiers, actual.overtimeTiers)
        assertEquals(expected.complementaryMinutes, actual.complementaryMinutes)
        assertEquals(expected.regularGross, actual.regularGross, 0.000001)
        assertEquals(expected.overtimeGross, actual.overtimeGross, 0.000001)
        assertEquals(expected.premiumsGross, actual.premiumsGross, 0.000001)
        assertEquals(expected.monthlyEstimatedGross, actual.monthlyEstimatedGross, 0.000001)
        assertEquals(expected.nightMs, actual.nightMs)
        assertEquals(expected.saturdayMs, actual.saturdayMs)
        assertEquals(expected.sundayMs, actual.sundayMs)
        assertEquals(expected.publicHolidayMs, actual.publicHolidayMs)
        assertEquals(expected.paidTimeReliable, actual.paidTimeReliable)
        assertEquals(expected.monthlyGrossReliable, actual.monthlyGrossReliable)
        assertEquals(expected.unpaidPauseMs, actual.unpaidPauseMs)
    }

    private fun session(at: String, durationMs: Long): WorkSessionV2 {
        val start = LocalDateTime.parse(at).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return WorkSessionV2("session-$start", "employer", start, start, start + durationMs,
            start + durationMs, status = SessionStatusV2.CLOSED, timeBasis = TimeBasisV2.REAL_FACTS)
    }

    private fun fragments(source: WorkSessionV2, vararg offsets: Long): List<WorkSessionV2> {
        val start = source.countedEntryMs!!
        val boundaries = listOf(start) + offsets.map { start + it } + source.countedExitMs!!
        return boundaries.zipWithNext().mapIndexed { index, (from, to) ->
            source.copy(id = "${source.id}-$index", realArrivalMs = from, countedEntryMs = from,
                countedExitMs = to, realExitMs = to,
                pauses = source.pauses.mapNotNull { p ->
                    val a = maxOf(from, p.startMs)
                    val b = minOf(to, p.endMs!!)
                    if (b > a) p.copy(startMs = a, endMs = b) else null
                },
                workSegments = source.workSegments.mapNotNull { p ->
                    val a = maxOf(from, p.startMs)
                    val b = minOf(to, p.endMs!!)
                    if (b > a) p.copy(startMs = a, endMs = b) else null
                })
        }
    }

    private fun contract(type: ContractTypeV2 = ContractTypeV2.OTHER) = ContractV2(
        "contract", "employer", type, 40 * 60, 60.0, LocalDate.of(2020, 1, 1).toEpochDay(),
        forfaitHoursPeriod = ForfaitHoursPeriodV2.MONTH, forfaitHours = 151.0,
        forfaitAnnualDays = 218.0, monthlyGrossSalary = 3000.0
    )

    private fun calculate(
        sessions: List<WorkSessionV2>, month: LocalDate = september,
        contract: ContractV2 = contract(), premiums: Set<Premium> = emptySet(), overtimeStart: Int? = null
    ): V2SalaryAdapter.Result {
        val convention = ConventionCatalog.Convention("HT015", "Fixture", "Fictional test inputs",
            rulesIntegrated = true,
            overtimeTiers = overtimeStart?.let {
                listOf(ConventionCatalog.OvertimeTier(it / 60.0, null, 1.25))
            }.orEmpty())
        return sourceMethod.invoke(V2SalaryAdapter, contract, emptyList<String>(), sessions,
            month.year, month.monthValue - 1, 60.0, convention, null, setOf("employer"),
            null, null, null, snapshot(month, premiums),
            FrenchPublicHolidayCalendarV2.Scope(FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE, true),
            true, LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ) as V2SalaryAdapter.Result
    }

    private enum class Premium { NIGHT, SATURDAY, SUNDAY, HOLIDAY }

    private fun snapshot(date: LocalDate, premiums: Set<Premium>) = CollectivePremiumLegalArbitrationBridgeV2.Snapshot(
        date.withDayOfMonth(date.lengthOfMonth()),
        selection(if (Premium.NIGHT in premiums) NightPremiumRuleV2(21 * 60, 6 * 60, 1.25) else null),
        selection(if (Premium.SATURDAY in premiums) WeekdayPremiumRuleV2(WeekdayPremiumKindV2.SATURDAY, 1.25) else null),
        selection(if (Premium.SUNDAY in premiums) WeekdayPremiumRuleV2(WeekdayPremiumKindV2.SUNDAY, 1.25) else null),
        selection(if (Premium.HOLIDAY in premiums) PublicHolidayPremiumRuleV2(1.25) else null)
    )

    private fun <T> selection(rule: T?): CollectivePremiumLegalArbitrationBridgeV2.Selection<T> {
        val candidate = rule?.let { PayrollLegalArbitratorV2.Candidate("fixture", PayrollLegalArbitratorV2.Source.CONTRACT,
            LocalDate.of(2020, 1, 1), verified = true, scopeConfirmed = true, valueFingerprint = it.toString()) }
        val resolution = PayrollLegalArbitratorV2.Resolution(
            if (candidate == null) PayrollLegalArbitratorV2.State.NO_APPLICABLE_RULE else PayrollLegalArbitratorV2.State.RESOLVED,
            candidate, listOfNotNull(candidate), emptyList(), "Fictional confirmed test input")
        return CollectivePremiumLegalArbitrationBridgeV2.Selection(resolution, rule, candidate?.id, emptyList())
    }
}
