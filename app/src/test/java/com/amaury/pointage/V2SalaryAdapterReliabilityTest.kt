package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.amaury.pointage.v2.engine.OvertimeTierV2
import org.junit.Test

class V2SalaryAdapterReliabilityTest {
    @Test
    fun `un taux provisoire rend le brut non fiable`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = true,
                arbitrationRequired = false,
                arbitrationResolved = false
            )
        )
    }

    @Test
    fun `un arbitrage requis mais non resolu rend le brut non fiable`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = true,
                arbitrationResolved = false
            )
        )
    }

    @Test
    fun `un arbitrage resolu autorise le brut fiable si les autres controles passent`() {
        assertTrue(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = true,
                arbitrationResolved = true
            )
        )
    }

    @Test
    fun `une autre cause de non fiabilite reste bloquante`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = false,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = true,
                arbitrationResolved = true
            )
        )
    }

    @Test
    fun `un cumul de majorations non confirme rend le brut non fiable`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = true,
                arbitrationResolved = true,
                cumulReviewRequired = true
            )
        )
    }


    @Test
    fun `un bareme variable non fiable rend le brut non fiable`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = false,
                arbitrationResolved = false,
                additionalVariableRatesReliable = false
            )
        )
    }

    @Test
    fun `aucune heure complementaire conserve la fiabilite des autres controles`() {
        assertTrue(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = false,
                arbitrationResolved = false,
                additionalVariableRatesReliable = true
            )
        )
    }

    @Test
    fun `un contrat generique sans palier ne couvre pas les minutes au dela du seuil`() {
        assertFalse(V2SalaryAdapter.overtimeTiersCoverPaidExcess(35 * 60, 36 * 60, emptyList()))
    }

    @Test
    fun `un palier explicite couvre toutes les minutes au dela du seuil`() {
        val tiers = listOf(OvertimeTierV2(35 * 60, 43 * 60, 1.25))
        assertTrue(V2SalaryAdapter.overtimeTiersCoverPaidExcess(35 * 60, 36 * 60, tiers))
    }

    @Test
    fun `un trou entre deux paliers rend la couverture non fiable`() {
        val tiers = listOf(
            OvertimeTierV2(35 * 60, 36 * 60, 1.25),
            OvertimeTierV2(37 * 60, null, 1.50)
        )
        assertFalse(V2SalaryAdapter.overtimeTiersCoverPaidExcess(35 * 60, 38 * 60, tiers))
    }

    @Test
    fun `des paliers qui se chevauchent rendent la couverture ambigue`() {
        val tiers = listOf(
            OvertimeTierV2(35 * 60, 37 * 60, 1.25),
            OvertimeTierV2(36 * 60, null, 1.50)
        )
        assertFalse(V2SalaryAdapter.overtimeTiersCoverPaidExcess(35 * 60, 38 * 60, tiers))
    }

    @Test
    fun `un palier explicite a zero pourcent reste une regle valide`() {
        val tiers = listOf(OvertimeTierV2(35 * 60, null, 1.0))
        assertTrue(V2SalaryAdapter.overtimeTiersCoverPaidExcess(35 * 60, 36 * 60, tiers))
    }

    @Test
    fun `un runtime non fiable rend le brut non fiable`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = false,
                arbitrationResolved = false,
                runtimeReliable = false
            )
        )
    }
}
