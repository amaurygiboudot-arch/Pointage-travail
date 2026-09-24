package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedWorkedGrossAssemblyV2Test {
    @Test
    fun provenBaseAndVariablesProduceWorkedGross() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
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
    fun explicitReliableZeroVariableIsAccepted() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
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
    fun missingVariablePieceNeverBecomesImplicitZero() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
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
    fun variableOrderDoesNotChangeResult() {
        val result = SegmentedWorkedGrossAssemblerV2.assemble(
            base = base(),
            variables = listOf(
                variable("v2", 15, 30, 80.0),
                variable("v1", 0, 14, 120.0)
            )
        )

        assertTrue(result.reliable)
        assertEquals(1_700.0, result.workedGross!!, 0.0001)
    }

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
        warnings: List<String> = emptyList()
    ) = SegmentedWorkedVariableGrossPieceV2(
        versionId = versionId,
        startEpochDay = start,
        endEpochDay = end,
        variableGross = amount,
        reliable = reliable,
        warnings = warnings
    )
}
