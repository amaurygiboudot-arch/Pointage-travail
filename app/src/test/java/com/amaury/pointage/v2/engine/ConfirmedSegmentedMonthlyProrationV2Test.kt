package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedSegmentedMonthlyProrationV2Test {
    @Test
    fun `un changement de taux est prorate uniquement avec des minutes planifiees confirmees`() {
        val segments = listOf(
            segment("v1", 0, 14, fullTime(rate = 10.0)),
            segment("v2", 15, 30, fullTime(rate = 20.0))
        )
        val rules = mapOf(
            "v1" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
            "v2" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
        )
        val proration = confirmed(segments[0] to 4_200, segments[1] to 4_200)

        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(segments, rules, proration)

        assertTrue(result.reliable)
        assertEquals(2, result.pieces.size)
        assertEquals(0.5, result.pieces[0].factor, 0.000001)
        assertEquals(0.5, result.pieces[1].factor, 0.000001)
        assertEquals(2275.0, result.baseGross!!, 0.0001)
    }

    @Test
    fun `sans base de proratisation aucun montant nest invente`() {
        val only = segment("v1", 0, 30, fullTime(rate = 14.0))
        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = listOf(only),
            rulesByVersionId = mapOf("v1" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60)),
            proration = null
        )

        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.MISSING_PRORATION_WARNING))
    }

    @Test
    fun `la base confirmee doit couvrir exactement toutes les versions`() {
        val segments = listOf(
            segment("v1", 0, 14, fullTime(rate = 10.0)),
            segment("v2", 15, 30, fullTime(rate = 20.0))
        )
        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = segments,
            rulesByVersionId = mapOf(
                "v1" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
                "v2" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
            ),
            proration = confirmed(segments[0] to 8_400)
        )

        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.INVALID_PRORATION_WARNING))
    }

    @Test
    fun `les bornes de la confirmation doivent rester identiques aux segments`() {
        val segments = listOf(
            segment("v1", 0, 14, fullTime(rate = 10.0)),
            segment("v2", 15, 30, fullTime(rate = 20.0))
        )
        val stale = ConfirmedSegmentedMonthlyProrationV2(
            sourceId = "planning-confirme",
            checkedAtMs = 1L,
            segments = listOf(
                ConfirmedProrationSegmentV2("v1", 0, 13, 4_200),
                ConfirmedProrationSegmentV2("v2", 14, 30, 4_200)
            )
        )
        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = segments,
            rulesByVersionId = mapOf(
                "v1" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
                "v2" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
            ),
            proration = stale
        )

        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.INVALID_PRORATION_WARNING))
    }

    @Test
    fun `un trou entre deux segments bloque la proratisation`() {
        val segments = listOf(
            segment("v1", 0, 10, fullTime(rate = 10.0)),
            segment("v2", 12, 30, fullTime(rate = 20.0))
        )
        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = segments,
            rulesByVersionId = mapOf(
                "v1" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
                "v2" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
            ),
            proration = confirmed(segments[0] to 4_200, segments[1] to 4_200)
        )

        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.INVALID_PRORATION_WARNING))
    }

    @Test
    fun `un temps plein 39h exige les paliers structurels confirmes`() {
        val contract = fullTime(rate = 10.0, weeklyMinutes = 39 * 60)
        val only = segment("v1", 0, 30, contract)

        val blocked = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = listOf(only),
            rulesByVersionId = mapOf("v1" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60)),
            proration = confirmed(only to 8_400)
        )
        assertFalse(blocked.reliable)
        assertNull(blocked.baseGross)

        val calculated = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = listOf(only),
            rulesByVersionId = mapOf(
                "v1" to PayrollRulesV2(
                    weeklyRegularMinutes = 35 * 60,
                    overtimeTiers = listOf(OvertimeTierV2(35 * 60, 43 * 60, 1.25))
                )
            ),
            proration = confirmed(only to 8_400)
        )
        assertTrue(calculated.reliable)
        assertEquals(1733.333333, calculated.baseGross!!, 0.0001)
        assertEquals(1040.0, calculated.pieces.single().proratedStructuralOvertimeMinutes, 0.0001)
        assertEquals(216.666667, calculated.pieces.single().proratedStructuralOvertimeGross, 0.0001)
    }

    @Test
    fun `un temps partiel utilise uniquement sa duree et son taux confirmes`() {
        val contract = ContractV2(
            id = "part",
            employerId = "company",
            type = ContractTypeV2.PART_TIME,
            contractualWeeklyMinutes = 20 * 60,
            grossHourlyRate = 12.0,
            hireDateEpochDay = 0L
        )
        val only = segment("v1", 0, 30, contract)
        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = listOf(only),
            rulesByVersionId = emptyMap(),
            proration = confirmed(only to 4_800)
        )

        assertTrue(result.reliable)
        assertEquals(1040.0, result.baseGross!!, 0.0001)
    }

    @Test
    fun `un forfait nest jamais prorate par des minutes sans regle specifique`() {
        val forfait = ContractV2(
            id = "c1",
            employerId = "company",
            type = ContractTypeV2.FORFAIT_DAYS,
            contractualWeeklyMinutes = null,
            grossHourlyRate = null,
            hireDateEpochDay = 0,
            forfaitAnnualDays = 218.0,
            monthlyGrossSalary = 3_000.0
        )
        val only = segment("v1", 0, 30, forfait)

        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = listOf(only),
            rulesByVersionId = emptyMap(),
            proration = confirmed(only to 8_400)
        )

        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.UNSUPPORTED_CONTRACT_WARNING))
    }

    @Test
    fun `un segment sans heure planifiee peut avoir un facteur nul si la source le confirme`() {
        val segments = listOf(
            segment("v1", 0, 1, fullTime(rate = 10.0)),
            segment("v2", 2, 30, fullTime(rate = 20.0))
        )
        val result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = segments,
            rulesByVersionId = mapOf(
                "v1" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
                "v2" to PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
            ),
            proration = confirmed(segments[0] to 0, segments[1] to 8_400)
        )

        assertTrue(result.reliable)
        assertEquals(0.0, result.pieces.first().factor, 0.0)
        assertEquals(3033.333333, result.baseGross!!, 0.0001)
    }

    private fun confirmed(vararg values: Pair<EmploymentContractCoverageSegmentV2, Int>) =
        ConfirmedSegmentedMonthlyProrationV2(
            sourceId = "planning-confirme",
            checkedAtMs = 1L,
            segments = values.map { (segment, minutes) ->
                ConfirmedProrationSegmentV2(
                    versionId = segment.snapshot.versionId,
                    startEpochDay = segment.startEpochDay,
                    endEpochDay = segment.endEpochDay,
                    scheduledMinutes = minutes
                )
            }
        )

    private fun segment(
        versionId: String,
        start: Long,
        end: Long,
        contract: ContractV2
    ) = EmploymentContractCoverageSegmentV2(
        startEpochDay = start,
        endEpochDay = end,
        snapshot = EmploymentContractSnapshotV2(
            versionId = versionId,
            sourceId = "test",
            effectiveFromEpochDay = start,
            effectiveToEpochDay = end,
            contract = contract,
            checkedAtMs = 1L,
            note = null
        )
    )

    private fun fullTime(
        rate: Double,
        weeklyMinutes: Int = 35 * 60
    ) = ContractV2(
        id = "contract-$rate-$weeklyMinutes",
        employerId = "company",
        type = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = weeklyMinutes,
        grossHourlyRate = rate,
        hireDateEpochDay = 0L
    )
}
