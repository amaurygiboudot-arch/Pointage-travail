package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedWorkedGrossAssemblyV2Test {
    @Test
    fun provenBaseAndVariablesProduceWorkedGross() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v1", 0, 14, 120.0),
                variable("v2", 15, 30, 80.0)
            )
        )

        assertTrue(result.reliable)
        assertEquals(1_500.0, result.baseGross!!, 0.0001)
        assertEquals(200.0, result.variableGross!!, 0.0001)
        assertEquals(1_700.0, result.workedGross!!, 0.0001)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun b21GlobalWarningSurvivesSuccessfulB20Assembly() {
        val source = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = listOf(
                variable("v1", 0, 14, 120.0),
                variable("v2", 15, 30, 80.0)
            ),
            reliable = true,
            warnings = listOf("avertissement global B21")
        )

        val result = SegmentedWorkedGrossAssemblerV2.assembleFromSource(
            contracts = contracts(),
            base = base(),
            source = source
        )

        assertTrue(result.reliable)
        assertEquals(1_700.0, result.workedGross!!, 0.0001)
        assertTrue(result.warnings.contains("avertissement global B21"))
    }

    @Test
    fun unreliableB21ResultBlocksB20WithoutPublishingPartialAmounts() {
        val source = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = listOf(
                variable("v1", 0, 14, 120.0),
                variable("v2", 15, 30, 80.0)
            ),
            reliable = false,
            warnings = listOf("couverture B21 incomplete")
        )

        val result = SegmentedWorkedGrossAssemblerV2.assembleFromSource(
            contracts = contracts(),
            base = base(),
            source = source
        )

        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertNull(result.variableGross)
        assertNull(result.workedGross)
        assertTrue(result.warnings.contains("couverture B21 incomplete"))
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.VARIABLE_RELIABILITY_WARNING
            )
        )
    }

    @Test
    fun explicitReliableZeroVariableIsAccepted() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v1", 0, 14, 0.0),
                variable("v2", 15, 30, 0.0)
            )
        )

        assertTrue(result.reliable)
        assertEquals(0.0, result.variableGross!!, 0.0)
        assertEquals(1_500.0, result.workedGross!!, 0.0001)
    }

    @Test
    fun unreliableB21ResultKeepsGlobalWarningEvenWithNoPieces() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variableSource = SegmentedWorkedVariableGrossSourceResultV2(
                pieces = emptyList(),
                reliable = false,
                warnings = listOf("B21 global : couverture incomplète")
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(result.warnings.contains("B21 global : couverture incomplète"))
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.VARIABLE_RELIABILITY_WARNING
            )
        )
    }

    @Test
    fun reliableB21ResultKeepsGlobalWarningAfterSuccessfulAssembly() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variableSource = SegmentedWorkedVariableGrossSourceResultV2(
                pieces = listOf(
                    variable("v1", 0, 14, 120.0),
                    variable("v2", 15, 30, 80.0)
                ),
                reliable = true,
                warnings = listOf("B21 global : preuve datée conservée")
            )
        )

        assertTrue(result.reliable)
        assertEquals(1_700.0, result.workedGross!!, 0.0001)
        assertTrue(result.warnings.contains("B21 global : preuve datée conservée"))
    }

    @Test
    fun inconsistentB21BreakdownBlocksAssembly() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variableSource = SegmentedWorkedVariableGrossSourceResultV2(
                pieces = listOf(
                    variable("v1", 0, 14, 120.0),
                    variable("v2", 15, 30, 80.0)
                ),
                reliable = true,
                warnings = emptyList(),
                breakdowns = listOf(
                    SegmentedWorkedVariableGrossBreakdownV2("company", "v1", 0, 14, 121.0, 0.0, 0.0),
                    SegmentedWorkedVariableGrossBreakdownV2("company", "v2", 15, 30, 80.0, 0.0, 0.0)
                )
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(result.warnings.contains(SegmentedWorkedGrossAssemblerV2.BREAKDOWN_WARNING))
    }

    @Test
    fun missingVariablePieceNeverBecomesImplicitZero() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v1", 0, 14, 120.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.COVERAGE_WARNING
            )
        )
    }

    @Test
    fun unreliableVariablePieceBlocksAssembly() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v1", 0, 14, 120.0),
                variable(
                    "v2",
                    15,
                    30,
                    80.0,
                    reliable = false,
                    warnings = listOf("preuve variable absente")
                )
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(result.warnings.contains("preuve variable absente"))
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.VARIABLE_RELIABILITY_WARNING
            )
        )
    }

    @Test
    fun duplicateVariableKeyBlocksAssembly() {
        val duplicate = variable("v1", 0, 14, 10.0)
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                duplicate,
                duplicate,
                variable("v2", 15, 30, 20.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.variableGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.COVERAGE_WARNING
            )
        )
    }

    @Test
    fun inconsistentBaseTotalBlocksAssembly() {
        val inconsistent = base().copy(baseGross = 1_499.0)
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = inconsistent,
            variables = listOf(
                variable("v1", 0, 14, 0.0),
                variable("v2", 15, 30, 0.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.BASE_WARNING
            )
        )
    }

    @Test
    fun tamperedBasePieceFactorBlocksAssembly() {
        val original = base()
        val badPiece = original.pieces.first().copy(
            factor = 0.6,
            proratedBaseGross = 1_200.0
        )
        val second = original.pieces[1].copy(
            factor = 0.4,
            proratedBaseGross = 400.0
        )
        val tampered = original.copy(
            pieces = listOf(badPiece, second),
            baseGross = 1_600.0
        )

        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = tampered,
            variables = listOf(
                variable("v1", 0, 14, 0.0),
                variable("v2", 15, 30, 0.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.BASE_WARNING
            )
        )
    }

    @Test
    fun invertedVariableBoundsBlockAssembly() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v1", 14, 0, 0.0),
                variable("v2", 15, 30, 0.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.COVERAGE_WARNING
            )
        )
    }

    @Test
    fun invalidVariableAmountBlocksAssembly() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v1", 0, 14, -1.0),
                variable("v2", 15, 30, 0.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.AMOUNT_WARNING
            )
        )
    }

    @Test
    fun baseThatDoesNotMatchContractTimelineBlocksAssembly() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(secondVersionId = "other-v2"),
            base = base(),
            variables = listOf(
                variable("v1", 0, 14, 0.0),
                variable("v2", 15, 30, 0.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.CONTRACT_WARNING
            )
        )
    }

    @Test
    fun variableFromAnotherEmployerBlocksAssembly() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v1", 0, 14, 0.0, employerId = "other-company"),
                variable("v2", 15, 30, 0.0)
            )
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedGrossAssemblerV2.COVERAGE_WARNING
            )
        )
    }

    @Test
    fun variableOrderDoesNotChangeResult() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts(),
            base = base(),
            variables = listOf(
                variable("v2", 15, 30, 80.0),
                variable("v1", 0, 14, 120.0)
            )
        )

        assertTrue(result.reliable)
        assertEquals(1_700.0, result.workedGross!!, 0.0001)
    }

    private fun contracts(
        secondVersionId: String = "v2"
    ): EmploymentContractPeriodResolutionV2 {
        val segments = listOf(
            contractSegment("v1", 0, 14, 10.0),
            contractSegment(secondVersionId, 15, 30, 20.0)
        )
        return EmploymentContractPeriodResolutionV2(
            employerId = "company",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            coverage = EmploymentContractCoverageV2(
                employerId = "company",
                periodStartEpochDay = 0,
                periodEndEpochDay = 30,
                segments = segments,
                fullyCovered = true
            ),
            contract = null,
            warnings = emptyList()
        )
    }

    private fun contractSegment(
        versionId: String,
        start: Long,
        end: Long,
        rate: Double
    ) = EmploymentContractCoverageSegmentV2(
        startEpochDay = start,
        endEpochDay = end,
        snapshot = EmploymentContractSnapshotV2(
            versionId = versionId,
            sourceId = "test",
            effectiveFromEpochDay = start,
            effectiveToEpochDay = end,
            contract = ContractV2(
                id = versionId,
                employerId = "company",
                type = ContractTypeV2.FULL_TIME,
                contractualWeeklyMinutes = 35 * 60,
                grossHourlyRate = rate,
                hireDateEpochDay = 0L
            ),
            checkedAtMs = 1L,
            note = null
        )
    )

    private fun base(): SegmentedMonthlyBaseResultV2 =
        SegmentedMonthlyBaseResultV2(
            pieces = listOf(
                SegmentedMonthlyBasePieceV2(
                    versionId = "v1",
                    startEpochDay = 0,
                    endEpochDay = 14,
                    scheduledMinutes = 4_200,
                    factor = 0.5,
                    fullMonthBaseGross = 2_000.0,
                    proratedBaseGross = 1_000.0
                ),
                SegmentedMonthlyBasePieceV2(
                    versionId = "v2",
                    startEpochDay = 15,
                    endEpochDay = 30,
                    scheduledMinutes = 4_200,
                    factor = 0.5,
                    fullMonthBaseGross = 1_000.0,
                    proratedBaseGross = 500.0
                )
            ),
            baseGross = 1_500.0,
            reliable = true,
            warnings = emptyList()
        )

    private fun variable(
        versionId: String,
        start: Long,
        end: Long,
        amount: Double,
        reliable: Boolean = true,
        warnings: List<String> = emptyList(),
        employerId: String = "company"
    ) = SegmentedWorkedVariableGrossPieceV2(
        employerId = employerId,
        versionId = versionId,
        startEpochDay = start,
        endEpochDay = end,
        variableGross = amount,
        reliable = reliable,
        warnings = warnings
    )
}
