package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SicknessTheoreticalNetV2Test {
    private fun maintenance(
        waitingDays:Int,
        bands:List<PlasturgieSicknessMaintenanceV2.Band>
    ) = PlasturgieSicknessMaintenanceV2.Result(
        applicable = true,
        eligibilityConfirmed = true,
        employerWaitingDays = waitingDays,
        firstRecordedStopOfYear = waitingDays == 0,
        annualLimitDays = 105,
        alreadyConsumedIndemnifiedDays = 0,
        currentIndemnifiableDays = bands.sumOf { it.calendarDays },
        bands = bands,
        exactEmployerAmountAvailable = false,
        warnings = emptyList()
    )

    private fun allowance(dailyNet:Double,payableDays:Int) = SicknessDailyAllowanceV2.Result(
        complete = true,
        dailyGross = dailyNet / 0.933,
        payableDays = payableDays,
        estimatedGrossTotal = dailyNet / 0.933 * payableDays,
        referenceMonths = emptyList(),
        warnings = emptyList(),
        dailyNetBeforeIncomeTax = dailyNet,
        estimatedNetBeforeIncomeTaxTotal = dailyNet * payableDays
    )

    @Test
    fun `prorate le net mensuel en jours calendaires`() {
        val result = SicknessTheoreticalNetV2.calculate(
            absenceStart = LocalDate.of(2026,9,1),
            absenceEndExclusive = LocalDate.of(2026,9,11),
            maintenance = maintenance(0,listOf(PlasturgieSicknessMaintenanceV2.Band(10,1.0,"100 %"))),
            monthlyNetBeforeIncomeTax = mapOf(YearMonth.of(2026,9) to 3000.0),
            allowance = allowance(dailyNet=40.0,payableDays=7)
        )
        assertTrue(result.complete)
        assertEquals(1000.0,result.theoreticalAbsenceNet!!,0.01)
        assertEquals(1000.0,result.theoreticalIndemnifiableNet!!,0.01)
        assertEquals(1000.0,result.targetMaintenanceNet!!,0.01)
        assertEquals(7,result.ijssDaysDeducted)
        assertEquals(280.0,result.ijssNetDeductedOnce!!,0.01)
        assertEquals(720.0,result.employerComplementBeforeProvidentNet!!,0.01)
        assertFalse(result.finalComplementReliable)
        assertNull(result.employerComplementFinalNet)
    }

    @Test
    fun `aucune prevoyance chevauchante confirmee rend le complement final fiable`() {
        val result = SicknessTheoreticalNetV2.calculate(
            LocalDate.of(2026,9,1), LocalDate.of(2026,9,11),
            maintenance(0,listOf(PlasturgieSicknessMaintenanceV2.Band(10,1.0,"100 %"))),
            mapOf(YearMonth.of(2026,9) to 3000.0),
            allowance(40.0,7),
            AbsenceProvidentTreatmentV2.NONE_CONFIRMED,
            null
        )
        assertTrue(result.finalComplementReliable)
        assertEquals(0.0,result.employerProvidentNetDeducted!!,0.01)
        assertEquals(720.0,result.employerComplementFinalNet!!,0.01)
    }

    @Test
    fun `prevoyance nette chevauchante est deduite une seule fois du complement`() {
        val result = SicknessTheoreticalNetV2.calculate(
            LocalDate.of(2026,9,1), LocalDate.of(2026,9,11),
            maintenance(0,listOf(PlasturgieSicknessMaintenanceV2.Band(10,1.0,"100 %"))),
            mapOf(YearMonth.of(2026,9) to 3000.0),
            allowance(40.0,7),
            AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED,
            120.0
        )
        assertTrue(result.finalComplementReliable)
        assertEquals(120.0,result.employerProvidentNetDeducted!!,0.01)
        assertEquals(600.0,result.employerComplementFinalNet!!,0.01)
    }

    @Test
    fun `ne retire pas les ijss pendant les trois jours de carence secu`() {
        val result = SicknessTheoreticalNetV2.calculate(
            LocalDate.of(2026,9,1), LocalDate.of(2026,9,6),
            maintenance(0,listOf(PlasturgieSicknessMaintenanceV2.Band(5,1.0,"100 %"))),
            mapOf(YearMonth.of(2026,9) to 3000.0), allowance(dailyNet=40.0,payableDays=2)
        )
        assertEquals(2,result.ijssDaysDeducted)
        assertEquals(80.0,result.ijssNetDeductedOnce!!,0.01)
        assertEquals(420.0,result.employerComplementBeforeProvidentNet!!,0.01)
    }

    @Test
    fun `applique les bandes 100 puis 75 sans recompter les ijss`() {
        val result = SicknessTheoreticalNetV2.calculate(
            LocalDate.of(2026,9,1), LocalDate.of(2026,9,11),
            maintenance(0,listOf(
                PlasturgieSicknessMaintenanceV2.Band(5,1.0,"100 %"),
                PlasturgieSicknessMaintenanceV2.Band(5,0.75,"75 %")
            )),
            mapOf(YearMonth.of(2026,9) to 3000.0), allowance(dailyNet=40.0,payableDays=7)
        )
        assertEquals(875.0,result.targetMaintenanceNet!!,0.01)
        assertEquals(280.0,result.ijssNetDeductedOnce!!,0.01)
        assertEquals(595.0,result.employerComplementBeforeProvidentNet!!,0.01)
    }

    @Test
    fun `gere un arret qui traverse deux mois`() {
        val result = SicknessTheoreticalNetV2.calculate(
            LocalDate.of(2026,8,30), LocalDate.of(2026,9,3),
            maintenance(0,listOf(PlasturgieSicknessMaintenanceV2.Band(4,1.0,"100 %"))),
            mapOf(YearMonth.of(2026,8) to 3100.0, YearMonth.of(2026,9) to 3000.0),
            allowance(dailyNet=40.0,payableDays=1)
        )
        assertEquals(400.0,result.theoreticalAbsenceNet!!,0.01)
        assertEquals(40.0,result.ijssNetDeductedOnce!!,0.01)
        assertEquals(360.0,result.employerComplementBeforeProvidentNet!!,0.01)
    }

    @Test
    fun `refuse d'inventer une base mensuelle manquante`() {
        val result = SicknessTheoreticalNetV2.calculate(
            LocalDate.of(2026,8,30), LocalDate.of(2026,9,3),
            maintenance(0,listOf(PlasturgieSicknessMaintenanceV2.Band(4,1.0,"100 %"))),
            mapOf(YearMonth.of(2026,8) to 3100.0), allowance(dailyNet=40.0,payableDays=1)
        )
        assertFalse(result.complete)
        assertNull(result.employerComplementBeforeProvidentNet)
        assertTrue(result.warnings.any { it.contains("09/2026") })
    }
}
