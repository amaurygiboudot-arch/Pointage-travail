package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerReductionResolutionV2Test {
    private val month = YearMonth.of(2026, 9)

    private fun manual(
        amount: Double = 300.0,
        source: String = "DSN 09/2026",
        id: String = "manual",
        targetMonth: YearMonth = month
    ) = EmployerReductionAdjustmentV2.Record(
        id = id,
        month = targetMonth,
        totalReductionAmount = amount,
        source = source
    )

    private fun automatic(
        amount: Double? = 635.60,
        reliable: Boolean = true,
        warnings: List<String> = emptyList()
    ) = EmployerGeneralReduction2026V2.Result(
        amount = amount,
        coefficient = if (amount != null) 0.3178 else null,
        referenceMinimumMonthly = if (amount != null) 1823.03 else null,
        thresholdMonthly = if (amount != null) 5469.09 else null,
        reliable = reliable,
        warnings = warnings
    )

    private fun context(
        fullMonth: Boolean? = true,
        standardCase: Boolean? = true,
        noOtherReduction: Boolean? = true,
        source: String? = "Bulletin 09/2026",
        reliable: Boolean = true,
        warnings: List<String> = emptyList()
    ) = EmployerGeneralReductionContextV2.Snapshot(
        fullMonthPresent = fullMonth,
        standardCommonLawCaseConfirmed = standardCase,
        noOtherEmployerReductionConfirmed = noOtherReduction,
        source = source,
        reliable = reliable,
        warnings = warnings
    )

    @Test
    fun `manual confirmed total always wins over automatic rgdu`() {
        val result = EmployerReductionResolutionV2.resolve(
            month = month,
            manualRecords = listOf(manual(amount = 300.0)),
            automaticRgdu = automatic(amount = 635.60),
            context = context()
        )

        assertTrue(result.reliable)
        assertEquals(EmployerReductionResolutionV2.Mode.MANUAL_CONFIRMED_TOTAL, result.mode)
        assertEquals(300.0, result.totalReductionAmount!!, 0.001)
        assertEquals(635.60, result.automaticRgduAmount!!, 0.001)
        assertEquals("DSN 09/2026", result.source)
    }

    @Test
    fun `manual confirmed zero also wins`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            listOf(manual(amount = 0.0)),
            automatic(),
            context()
        )

        assertTrue(result.reliable)
        assertEquals(0.0, result.totalReductionAmount!!, 0.0)
        assertEquals(EmployerReductionResolutionV2.Mode.MANUAL_CONFIRMED_TOTAL, result.mode)
    }

    @Test
    fun `automatic rgdu can become total only when no other reduction is confirmed`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            emptyList(),
            automatic(amount = 635.60),
            context(noOtherReduction = true)
        )

        assertTrue(result.reliable)
        assertEquals(EmployerReductionResolutionV2.Mode.AUTOMATIC_RGDU_ONLY, result.mode)
        assertEquals(635.60, result.totalReductionAmount!!, 0.001)
        assertTrue(result.source!!.contains("Bulletin 09/2026"))
    }

    @Test
    fun `automatic rgdu remains diagnostic when another reduction may exist`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            emptyList(),
            automatic(amount = 635.60),
            context(noOtherReduction = false)
        )

        assertFalse(result.reliable)
        assertNull(result.totalReductionAmount)
        assertEquals(635.60, result.automaticRgduAmount!!, 0.001)
        assertTrue(result.warnings.any { it.contains("ne peut pas être assimilée au total") })
    }

    @Test
    fun `unknown monthly context blocks automatic total`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            emptyList(),
            automatic(),
            context(
                fullMonth = null,
                standardCase = null,
                noOtherReduction = null,
                source = null,
                reliable = false,
                warnings = listOf("Contexte RGDU à confirmer")
            )
        )

        assertFalse(result.reliable)
        assertNull(result.totalReductionAmount)
        assertTrue(result.warnings.contains("Contexte RGDU à confirmer"))
    }

    @Test
    fun `unreliable automatic core blocks fallback`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            emptyList(),
            automatic(amount = null, reliable = false, warnings = listOf("RGDU 2026 : heures à confirmer")),
            context()
        )

        assertFalse(result.reliable)
        assertNull(result.totalReductionAmount)
        assertTrue(result.warnings.contains("RGDU 2026 : heures à confirmer"))
    }

    @Test
    fun `malformed manual data blocks automatic fallback instead of hiding conflict`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            listOf(manual(amount = -1.0, targetMonth = YearMonth.of(2026, 8))),
            automatic(),
            context()
        )

        assertFalse(result.reliable)
        assertNull(result.totalReductionAmount)
        assertTrue(result.warnings.any { it.contains("incohérente") })
    }

    @Test
    fun `duplicate manual totals for the month block automatic fallback`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            listOf(manual(id = "a"), manual(id = "b")),
            automatic(),
            context()
        )

        assertFalse(result.reliable)
        assertNull(result.totalReductionAmount)
        assertTrue(result.warnings.any { it.contains("plusieurs totaux") })
    }

    @Test
    fun `missing context source blocks automatic total even when booleans are confirmed`() {
        val result = EmployerReductionResolutionV2.resolve(
            month,
            emptyList(),
            automatic(),
            context(source = "   ")
        )

        assertFalse(result.reliable)
        assertNull(result.totalReductionAmount)
        assertTrue(result.warnings.any { it.contains("source du contexte") })
    }
}
