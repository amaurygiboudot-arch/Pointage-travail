package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedCashGrossAssemblyV2Test {
    @Test
    fun `B20 fiable plus composantes fixes confirmees donne le cash gross une seule fois`() {
        val result = SegmentedCashGrossAssemblerV2.assemble(
            worked = worked(1_000.0),
            fixed = ConfirmedCashGrossComponentsV2(
                components = listOf(
                    ConfirmedCashGrossComponentV2("seniority", 50.0, true),
                    ConfirmedCashGrossComponentV2("company-premium", 25.0, true)
                ),
                exhaustive = true,
                sourceId = "fixed-2026-09"
            )
        )
        assertTrue(result.reliable)
        assertEquals(1_000.0, result.workedGross!!, 0.0001)
        assertEquals(75.0, result.additionalCashGross!!, 0.0001)
        assertEquals(1_075.0, result.cashGross!!, 0.0001)
    }

    @Test
    fun `liste vide vaut zero seulement si elle est explicitement exhaustive`() {
        val confirmed = SegmentedCashGrossAssemblerV2.assemble(
            worked(800.0),
            ConfirmedCashGrossComponentsV2(emptyList(), true, "none-confirmed")
        )
        assertTrue(confirmed.reliable)
        assertEquals(800.0, confirmed.cashGross!!, 0.0001)

        val unknown = SegmentedCashGrossAssemblerV2.assemble(
            worked(800.0),
            ConfirmedCashGrossComponentsV2(emptyList(), false, "")
        )
        assertFalse(unknown.reliable)
        assertNull(unknown.cashGross)
    }

    @Test
    fun `B20 non fiable bloque tout cash gross`() {
        val result = SegmentedCashGrossAssemblerV2.assemble(
            worked(1_000.0, reliable = false),
            ConfirmedCashGrossComponentsV2(emptyList(), true, "fixed")
        )
        assertFalse(result.reliable)
        assertNull(result.cashGross)
    }

    @Test
    fun `doublon ou composante invalide bloque sans sous total partiel`() {
        val duplicate = SegmentedCashGrossAssemblerV2.assemble(
            worked(1_000.0),
            ConfirmedCashGrossComponentsV2(
                listOf(
                    ConfirmedCashGrossComponentV2("x", 10.0, true),
                    ConfirmedCashGrossComponentV2("x", 20.0, true)
                ), true, "fixed"
            )
        )
        assertFalse(duplicate.reliable)
        assertNull(duplicate.cashGross)

        val invalid = SegmentedCashGrossAssemblerV2.assemble(
            worked(1_000.0),
            ConfirmedCashGrossComponentsV2(
                listOf(ConfirmedCashGrossComponentV2("bad", -1.0, true)), true, "fixed"
            )
        )
        assertFalse(invalid.reliable)
        assertNull(invalid.cashGross)
    }

    @Test
    fun `warnings globaux et composantes sont conserves sans doublon`() {
        val result = SegmentedCashGrossAssemblerV2.assemble(
            worked(1_000.0, warnings = listOf("trace")),
            ConfirmedCashGrossComponentsV2(
                listOf(ConfirmedCashGrossComponentV2("x", 10.0, true, listOf("trace"))),
                true,
                "fixed",
                warnings = listOf("fixed-warning")
            )
        )
        assertEquals(1, result.warnings.count { it == "trace" })
        assertTrue(result.warnings.contains("fixed-warning"))
    }

    private fun worked(
        amount: Double,
        reliable: Boolean = true,
        warnings: List<String> = emptyList()
    ): SegmentedWorkedGrossProductionResultV2 {
        val source = SegmentedPayrollSessionSourceV2(
            employerId = "company", sessions = emptyList(), sourceId = "source",
            reliable = reliable, exhaustive = reliable, coveredStartEpochDay = 0,
            coveredEndEpochDay = 0, checkedAtMs = 1, timeZoneId = "UTC", warnings = warnings
        )
        val variables = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = emptyList(), reliable = reliable, warnings = warnings
        )
        val assembled = SegmentedWorkedGrossAssemblyResultV2(
            baseGross = if (reliable) amount else null,
            variableGross = if (reliable) 0.0 else null,
            workedGross = if (reliable) amount else null,
            reliable = reliable,
            warnings = warnings
        )
        return SegmentedWorkedGrossProductionResultV2(
            source, variables, assembled, reliable, warnings
        )
    }
}
