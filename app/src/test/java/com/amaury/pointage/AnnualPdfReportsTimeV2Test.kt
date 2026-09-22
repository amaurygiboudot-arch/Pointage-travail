package com.amaury.pointage

import com.amaury.pointage.v2.engine.TimeResultV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnualPdfReportsTimeV2Test {
    private fun time(
        presenceMs: Long,
        paidWorkMs: Long,
        paidPauseMs: Long,
        unpaidPauseMs: Long,
        reliable: Boolean = true
    ) = TimeResultV2(
        presenceMs = presenceMs,
        countedSpanMs = paidWorkMs + unpaidPauseMs,
        paidWorkMs = paidWorkMs,
        unpaidPauseMs = unpaidPauseMs,
        paidPauseMs = paidPauseMs,
        reliable = reliable
    )

    @Test
    fun `mois vide reste un total fiable a zero`() {
        val resolution = resolveAnnualTimeV2(emptyList())

        assertTrue(resolution.reliable)
        assertEquals(0L, resolution.presenceMs!!)
        assertEquals(0L, resolution.paidWorkMs!!)
        assertEquals(0L, resolution.paidPauseMs!!)
        assertEquals(0L, resolution.unpaidPauseMs!!)
    }

    @Test
    fun `resultats fiables sont sommes`() {
        val resolution = resolveAnnualTimeV2(listOf(
            time(8_000L, 7_000L, 500L, 1_000L),
            time(6_000L, 5_500L, 250L, 500L)
        ))

        assertTrue(resolution.reliable)
        assertEquals(14_000L, resolution.presenceMs!!)
        assertEquals(12_500L, resolution.paidWorkMs!!)
        assertEquals(750L, resolution.paidPauseMs!!)
        assertEquals(1_500L, resolution.unpaidPauseMs!!)
    }

    @Test
    fun `une duree non fiable masque tous les totaux du mois`() {
        val resolution = resolveAnnualTimeV2(listOf(
            time(8_000L, 7_000L, 500L, 1_000L),
            time(6_000L, 6_000L, 0L, 0L, reliable = false)
        ))

        assertFalse(resolution.reliable)
        assertNull(resolution.presenceMs)
        assertNull(resolution.paidWorkMs)
        assertNull(resolution.paidPauseMs)
        assertNull(resolution.unpaidPauseMs)
    }

    @Test
    fun `un mois non fiable bloque le total annuel au lieu de creer un sous total`() {
        assertNull(resolveAnnualDurationTotalV2(listOf(10_000L, null, 12_000L)))
        assertEquals(22_000L, resolveAnnualDurationTotalV2(listOf(10_000L, 12_000L))!!)
    }

    @Test
    fun `heures supplementaires fiables sont sommes`() {
        assertEquals(
            10_800_000L,
            resolveAnnualOvertimeV2(listOf(3_600_000L, 7_200_000L), salaryReliable = true)!!
        )
    }

    @Test
    fun `paliers techniques restent masques si le salaire nest pas fiable`() {
        assertNull(
            resolveAnnualOvertimeV2(listOf(3_600_000L, 7_200_000L), salaryReliable = false)
        )
    }

    @Test
    fun `mois sans resultat salaire bloque le cumul annuel des heures supplementaires`() {
        val reliableMonth = resolveAnnualOvertimeV2(listOf(3_600_000L), salaryReliable = true)
        val missingSalaryMonth = resolveAnnualOvertimeV2(null, salaryReliable = false)

        assertNull(resolveAnnualDurationTotalV2(listOf(reliableMonth, missingSalaryMonth)))
    }

    @Test
    fun `temps non fiable bloque aussi le brut mensuel et son cumul`() {
        val gross = resolveAnnualSalaryGrossV2(
            cashGross = 2_500.0,
            cashGrossReliable = true,
            salaryWarnings = emptyList(),
            payroll = null,
            socialGrossRequired = false,
            upstreamTimeReliable = false
        )

        assertNull(gross.amount)
        assertEquals("Temps payé à confirmer", gross.state)
    }
}
