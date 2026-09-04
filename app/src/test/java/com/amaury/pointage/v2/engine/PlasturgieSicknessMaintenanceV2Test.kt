package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PlasturgieSicknessMaintenanceV2Test {
    private val zone = ZoneId.of("Europe/Paris")

    private fun sickness(id:String,start:LocalDate,days:Int,employer:String="company-a") = AbsenceV2(
        id=id,
        employerId=employer,
        type=AbsencePayrollImpactV2.TYPE_SICKNESS,
        startMs=start.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMs=start.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
        fullDay=true,
        status=DecisionStatusV2.CONFIRMED
    )

    @Test
    fun `moins d un an anciennete n ouvre pas le maintien conventionnel`() {
        val current=sickness("current",LocalDate.of(2026,9,10),10)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "0292",current,listOf(current),LocalDate.of(2026,1,1),setOf("company-a"),zone
        )
        assertTrue(result.applicable)
        assertTrue(result.eligibilityConfirmed)
        assertEquals(0,result.currentIndemnifiableDays)
        assertTrue(result.bands.isEmpty())
    }

    @Test
    fun `premier arret de l annee sans carence avant cinq ans`() {
        val current=sickness("current",LocalDate.of(2026,9,10),10)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "292",current,listOf(current),LocalDate.of(2022,5,1),setOf("company-a"),zone
        )
        assertEquals(true,result.firstRecordedStopOfYear)
        assertEquals(0,result.employerWaitingDays)
        assertEquals(105,result.annualLimitDays)
        assertEquals(10,result.currentIndemnifiableDays)
        assertEquals(10,result.bands.single().calendarDays)
        assertEquals(1.0,result.bands.single().targetNetRate,0.0001)
    }

    @Test
    fun `deuxieme arret applique trois jours de carence`() {
        val first=sickness("first",LocalDate.of(2026,2,1),5)
        val current=sickness("current",LocalDate.of(2026,9,10),10)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "292",current,listOf(first,current),LocalDate.of(2022,5,1),setOf("company-a"),zone
        )
        assertEquals(false,result.firstRecordedStopOfYear)
        assertEquals(3,result.employerWaitingDays)
        assertEquals(5,result.alreadyConsumedIndemnifiedDays)
        assertEquals(7,result.currentIndemnifiableDays)
    }

    @Test
    fun `premier arret de deux jours limite la carence du deuxieme a deux jours`() {
        val first=sickness("first",LocalDate.of(2026,2,1),2)
        val current=sickness("current",LocalDate.of(2026,9,10),10)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "292",current,listOf(first,current),LocalDate.of(2022,5,1),setOf("company-a"),zone
        )
        assertEquals(2,result.employerWaitingDays)
        assertEquals(2,result.alreadyConsumedIndemnifiedDays)
        assertEquals(8,result.currentIndemnifiableDays)
    }

    @Test
    fun `jours deja consommes font basculer le nouvel arret vers 75 pour cent`() {
        val first=sickness("first",LocalDate.of(2026,1,1),45)
        val current=sickness("current",LocalDate.of(2026,9,10),10)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "292",current,listOf(first,current),LocalDate.of(2022,5,1),setOf("company-a"),zone
        )
        assertEquals(45,result.alreadyConsumedIndemnifiedDays)
        assertEquals(7,result.currentIndemnifiableDays)
        assertEquals(1,result.bands.size)
        assertEquals(0.75,result.bands.single().targetNetRate,0.0001)
    }

    @Test
    fun `cinq ans anciennete porte les limites a soixante et soixante quinze jours`() {
        val current=sickness("current",LocalDate.of(2026,9,10),70)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "292",current,listOf(current),LocalDate.of(2020,9,1),setOf("company-a"),zone
        )
        assertEquals(135,result.annualLimitDays)
        assertEquals(70,result.currentIndemnifiableDays)
        assertEquals(60,result.bands[0].calendarDays)
        assertEquals(1.0,result.bands[0].targetNetRate,0.0001)
        assertEquals(10,result.bands[1].calendarDays)
        assertEquals(0.75,result.bands[1].targetNetRate,0.0001)
    }

    @Test
    fun `absence autre entreprise ne consomme pas le plafond`() {
        val other=sickness("other",LocalDate.of(2026,1,1),45,"company-b")
        val current=sickness("current",LocalDate.of(2026,9,10),10)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "292",current,listOf(other,current),LocalDate.of(2022,5,1),setOf("company-a"),zone
        )
        assertEquals(0,result.alreadyConsumedIndemnifiedDays)
        assertEquals(10,result.currentIndemnifiableDays)
    }

    @Test
    fun `autre convention est ignoree`() {
        val current=sickness("current",LocalDate.of(2026,9,10),10)
        val result=PlasturgieSicknessMaintenanceV2.calculate(
            "9999",current,listOf(current),LocalDate.of(2020,1,1),setOf("company-a"),zone
        )
        assertFalse(result.applicable)
    }
}
