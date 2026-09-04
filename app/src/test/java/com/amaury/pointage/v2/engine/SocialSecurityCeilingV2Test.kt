package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SocialSecurityCeilingV2Test {
    @Test
    fun `temps plein sur mois complet conserve le PMSS 2026`() {
        val result = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = 2026,
                referenceDate = LocalDate.of(2026, 1, 31),
                contractType = ContractTypeV2.FULL_TIME,
                contractualWeeklyMinutes = 35 * 60,
                entryDate = LocalDate.of(2020, 1, 1)
            )
        )

        assertEquals(4005.0, result.applicableMonthly, 0.001)
        assertEquals(32040.0, result.eightTimesApplicable, 0.001)
    }

    @Test
    fun `entree en cours de mois reduit le plafond selon les jours calendaires`() {
        val result = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = 2026,
                referenceDate = LocalDate.of(2026, 1, 31),
                contractType = ContractTypeV2.FULL_TIME,
                contractualWeeklyMinutes = 35 * 60,
                entryDate = LocalDate.of(2026, 1, 16)
            )
        )

        assertEquals(4005.0 * 16.0 / 31.0, result.applicableMonthly, 0.001)
    }

    @Test
    fun `temps partiel 28 heures applique quatre vingt pour cent du plafond`() {
        val result = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = 2026,
                referenceDate = LocalDate.of(2026, 3, 31),
                contractType = ContractTypeV2.PART_TIME,
                contractualWeeklyMinutes = 28 * 60,
                complementaryMinutes = 0,
                entryDate = LocalDate.of(2020, 1, 1)
            )
        )

        assertEquals(3204.0, result.applicableMonthly, 0.001)
        assertEquals(0.8, result.workTimeRatio, 0.000001)
    }

    @Test
    fun `heures complementaires remontent le plafond sans depasser le PMSS`() {
        val result = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = 2026,
                referenceDate = LocalDate.of(2026, 3, 31),
                contractType = ContractTypeV2.PART_TIME,
                contractualWeeklyMinutes = 28 * 60,
                complementaryMinutes = 35 * 60,
                entryDate = LocalDate.of(2020, 1, 1)
            )
        )

        assertTrue(result.applicableMonthly <= 4005.0)
        assertTrue(result.applicableMonthly > 3204.0)
    }

    @Test
    fun `forfait 215 jours utilise la reference de 218 jours`() {
        val result = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = 2026,
                referenceDate = LocalDate.of(2026, 3, 31),
                contractType = ContractTypeV2.FORFAIT_DAYS,
                contractualWeeklyMinutes = null,
                entryDate = LocalDate.of(2020, 1, 1),
                forfaitAnnualDays = 215.0
            )
        )

        assertEquals(4005.0 * 215.0 / 218.0, result.applicableMonthly, 0.001)
    }
}
