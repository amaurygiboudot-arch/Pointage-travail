package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerWorkforceContributionsV2Test {
    private val standardTraining = EmployerWorkforceContributionsV2.TrainingTreatment.STANDARD
    private val cappedFnal = EmployerWorkforceContributionsV2.FnalTreatment.CAPPED_0_1_PERCENT
    private val uncappedFnal = EmployerWorkforceContributionsV2.FnalTreatment.UNCAPPED_0_5_PERCENT

    @Test
    fun `under 11 standard case uses capped FNAL and 0 point 55 training`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.UNDER_11,
            fnalTreatment = cappedFnal,
            trainingTreatment = standardTraining
        )

        assertEquals(4.005, result.fnalAmount!!, 0.001)
        assertEquals(27.50, result.trainingAmount!!, 0.001)
        assertTrue(result.complete)
    }

    @Test
    fun `11 to 49 standard case uses capped FNAL and one percent training`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.FROM_11_TO_49,
            fnalTreatment = cappedFnal,
            trainingTreatment = standardTraining
        )

        assertEquals(4.005, result.fnalAmount!!, 0.001)
        assertEquals(50.0, result.trainingAmount!!, 0.001)
        assertTrue(result.complete)
    }

    @Test
    fun `50 or more standard uncapped case uses 0 point 50 FNAL and one percent training`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
            fnalTreatment = uncappedFnal,
            trainingTreatment = standardTraining
        )

        assertEquals(25.0, result.fnalAmount!!, 0.001)
        assertEquals(50.0, result.trainingAmount!!, 0.001)
        assertTrue(result.complete)
    }

    @Test
    fun `50 or more can still use capped 0 point 10 FNAL when legal regime confirms it`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
            fnalTreatment = cappedFnal,
            trainingTreatment = standardTraining
        )

        assertTrue(result.complete)
        assertEquals(4.005, result.fnalAmount!!, 0.001)
        assertEquals(50.0, result.trainingAmount!!, 0.001)
    }

    @Test
    fun `confirmed training exemption is a real zero and not an unknown`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 2500.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.UNDER_11,
            fnalTreatment = cappedFnal,
            trainingTreatment = EmployerWorkforceContributionsV2.TrainingTreatment.EXEMPT_CONFIRMED
        )

        assertTrue(result.complete)
        assertEquals(0.0, result.trainingAmount!!, 0.0)
        assertEquals(2.5, result.fnalAmount!!, 0.001)
    }

    @Test
    fun `unknown FNAL regime is never inferred from workforce band`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 2500.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
            fnalTreatment = null,
            trainingTreatment = standardTraining
        )

        assertFalse(result.complete)
        assertNull(result.fnalAmount)
        assertEquals(25.0, result.trainingAmount!!, 0.001)
        assertNull(result.totalEmployerAmount)
        assertTrue(result.warnings.any { it.contains("FNAL", ignoreCase = true) })
    }

    @Test
    fun `unknown training applicability is never treated as standard`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 2500.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.UNDER_11,
            fnalTreatment = cappedFnal,
            trainingTreatment = null
        )

        assertFalse(result.complete)
        assertEquals(2.5, result.fnalAmount!!, 0.001)
        assertNull(result.trainingAmount)
        assertNull(result.totalEmployerAmount)
        assertTrue(result.warnings.any { it.contains("formation", ignoreCase = true) })
    }

    @Test
    fun `uncapped FNAL does not require an irrelevant social ceiling`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = null,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
            fnalTreatment = uncappedFnal,
            trainingTreatment = standardTraining
        )

        assertTrue(result.complete)
        assertEquals(25.0, result.fnalAmount!!, 0.001)
    }

    @Test
    fun `missing band blocks standard training without erasing known FNAL`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 2500.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = null,
            fnalTreatment = cappedFnal,
            trainingTreatment = standardTraining
        )

        assertFalse(result.complete)
        assertEquals(2.5, result.fnalAmount!!, 0.001)
        assertNull(result.trainingAmount)
        assertTrue(result.warnings.any { it.contains("effectif", ignoreCase = true) })
    }

    @Test
    fun `invalid social gross never produces workforce employer amounts`() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { gross ->
            val result = EmployerWorkforceContributionsV2.calculate(
                grossSocial = gross,
                applicableMonthlyCeiling = 4005.0,
                year = 2026,
                band = EmployerWorkforceContributionsV2.Band.UNDER_11,
                fnalTreatment = cappedFnal,
                trainingTreatment = standardTraining
            )

            assertFalse(result.complete)
            assertNull(result.fnalAmount)
            assertNull(result.trainingAmount)
            assertNull(result.totalEmployerAmount)
            assertTrue(result.warnings.any { it.contains("assiette", ignoreCase = true) })
        }
    }

    @Test
    fun `resolved employer facts require explicit FNAL and training treatments`() {
        val completeRecord = EmployerWorkforceContributionsV2.Record(
            id = "a",
            band = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
            effectiveFrom = YearMonth.of(2026, 1),
            source = "DSN",
            fnalTreatment = cappedFnal,
            trainingTreatment = standardTraining
        )
        val complete = EmployerWorkforceContributionsV2.resolve(
            listOf(completeRecord),
            YearMonth.of(2026, 9)
        )
        val migratedUnknownFnal = EmployerWorkforceContributionsV2.resolve(
            listOf(completeRecord.copy(id = "legacy-fnal", fnalTreatment = null)),
            YearMonth.of(2026, 9)
        )
        val migratedUnknownTraining = EmployerWorkforceContributionsV2.resolve(
            listOf(completeRecord.copy(id = "legacy-training", trainingTreatment = null)),
            YearMonth.of(2026, 9)
        )

        assertTrue(complete.reliable)
        assertEquals(cappedFnal, complete.fnalTreatment)
        assertEquals(standardTraining, complete.trainingTreatment)

        assertFalse(migratedUnknownFnal.reliable)
        assertNull(migratedUnknownFnal.fnalTreatment)
        assertEquals(standardTraining, migratedUnknownFnal.trainingTreatment)
        assertTrue(migratedUnknownFnal.warnings.any { it.contains("FNAL", ignoreCase = true) })

        assertFalse(migratedUnknownTraining.reliable)
        assertEquals(cappedFnal, migratedUnknownTraining.fnalTreatment)
        assertNull(migratedUnknownTraining.trainingTreatment)
        assertTrue(migratedUnknownTraining.warnings.any { it.contains("formation", ignoreCase = true) })
    }

    @Test
    fun `overlapping workforce facts block resolution`() {
        val first = EmployerWorkforceContributionsV2.Record(
            id = "a",
            band = EmployerWorkforceContributionsV2.Band.UNDER_11,
            effectiveFrom = YearMonth.of(2026, 1),
            source = "DSN",
            fnalTreatment = cappedFnal,
            trainingTreatment = standardTraining
        )
        val second = EmployerWorkforceContributionsV2.Record(
            id = "b",
            band = EmployerWorkforceContributionsV2.Band.FROM_11_TO_49,
            effectiveFrom = YearMonth.of(2026, 6),
            source = "DSN",
            fnalTreatment = cappedFnal,
            trainingTreatment = standardTraining
        )
        val result = EmployerWorkforceContributionsV2.resolve(
            listOf(first, second),
            YearMonth.of(2026, 9)
        )

        assertFalse(result.reliable)
        assertNull(result.fnalTreatment)
        assertNull(result.trainingTreatment)
        assertTrue(result.warnings.any { it.contains("chevauchent", ignoreCase = true) })
    }
}
