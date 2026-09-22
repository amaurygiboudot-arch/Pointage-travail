package com.amaury.pointage

import com.amaury.pointage.v2.engine.TimeResultV2
import com.amaury.pointage.v2.engine.WorkSessionOverlapV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
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

    private fun session(
        id: String,
        startMs: Long,
        endMs: Long?,
        status: SessionStatusV2 = SessionStatusV2.CLOSED
    ) = WorkSessionV2(
        id = id,
        employerId = "company-a",
        realArrivalMs = startMs,
        countedEntryMs = startMs,
        countedExitMs = endMs,
        realExitMs = endMs,
        status = status
    )

    @Test
    fun `mois vide reste un total fiable a zero`() {
        val resolution = resolveAnnualTimeV2(emptyList(), aggregateReliable = true)

        assertTrue(resolution.reliable)
        assertEquals(0L, resolution.presenceMs!!)
        assertEquals(0L, resolution.paidWorkMs!!)
        assertEquals(0L, resolution.paidPauseMs!!)
        assertEquals(0L, resolution.unpaidPauseMs!!)
    }

    @Test
    fun `resultats fiables sont sommes`() {
        val resolution = resolveAnnualTimeV2(
            results = listOf(
                time(8_000L, 7_000L, 500L, 1_000L),
                time(6_000L, 5_500L, 250L, 500L)
            ),
            aggregateReliable = true
        )

        assertTrue(resolution.reliable)
        assertEquals(14_000L, resolution.presenceMs!!)
        assertEquals(12_500L, resolution.paidWorkMs!!)
        assertEquals(750L, resolution.paidPauseMs!!)
        assertEquals(1_500L, resolution.unpaidPauseMs!!)
    }

    @Test
    fun `une duree non fiable masque tous les totaux du mois`() {
        val resolution = resolveAnnualTimeV2(
            results = listOf(
                time(8_000L, 7_000L, 500L, 1_000L),
                time(6_000L, 6_000L, 0L, 0L, reliable = false)
            ),
            aggregateReliable = true
        )

        assertFalse(resolution.reliable)
        assertNull(resolution.presenceMs)
        assertNull(resolution.paidWorkMs)
        assertNull(resolution.paidPauseMs)
        assertNull(resolution.unpaidPauseMs)
    }

    @Test
    fun `agregat ambigu masque des sessions individuellement fiables`() {
        val resolution = resolveAnnualTimeV2(
            results = listOf(
                time(8_000L, 7_000L, 500L, 1_000L),
                time(6_000L, 5_500L, 250L, 500L)
            ),
            aggregateReliable = false
        )

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
            resolveAnnualOvertimeV2(
                listOf(3_600_000L, 7_200_000L),
                monthlyGrossReliable = true,
                paidTimeReliable = true,
                upstreamTimeReliable = true
            )!!
        )
    }

    @Test
    fun `paliers techniques restent masques si le salaire nest pas fiable`() {
        assertNull(
            resolveAnnualOvertimeV2(
                listOf(3_600_000L, 7_200_000L),
                monthlyGrossReliable = false,
                paidTimeReliable = true,
                upstreamTimeReliable = true
            )
        )
    }

    @Test
    fun `temps adapte non fiable masque les heures supplementaires annuelles`() {
        assertNull(
            resolveAnnualOvertimeV2(
                listOf(3_600_000L),
                monthlyGrossReliable = true,
                paidTimeReliable = false,
                upstreamTimeReliable = true
            )
        )
    }

    @Test
    fun `mois sans resultat salaire bloque le cumul annuel des heures supplementaires`() {
        val reliableMonth = resolveAnnualOvertimeV2(
            listOf(3_600_000L),
            monthlyGrossReliable = true,
            paidTimeReliable = true,
            upstreamTimeReliable = true
        )
        val missingSalaryMonth = resolveAnnualOvertimeV2(
            null,
            monthlyGrossReliable = false,
            paidTimeReliable = false,
            upstreamTimeReliable = true
        )

        assertNull(resolveAnnualDurationTotalV2(listOf(reliableMonth, missingSalaryMonth)))
    }

    @Test
    fun `temps non fiable bloque aussi le brut mensuel et son cumul`() {
        val gross = resolveAnnualSalaryGrossV2(
            cashGross = 2_500.0,
            cashGrossReliable = true,
            paidTimeReliable = true,
            salaryWarnings = emptyList(),
            payroll = null,
            socialGrossRequired = false,
            upstreamTimeReliable = false
        )

        assertNull(gross.amount)
        assertEquals("Temps payé à confirmer", gross.state)
    }

    @Test
    fun `session ouverte utilise la meme borne pour detecter un chevauchement`() {
        val nowMs = 11_000L
        val sessions = listOf(
            session("closed", startMs = 8_000L, endMs = 10_000L),
            session("open", startMs = 9_000L, endMs = null, status = SessionStatusV2.OPEN)
        )

        assertTrue(
            WorkSessionOverlapV2.hasSameEmployerOverlap(
                sessions = sessions,
                rangeStartMs = 0L,
                rangeEndMs = 20_000L,
                openEndMs = nowMs
            )
        )
    }

    @Test
    fun `session ouverte ignore une ancienne sortie pour les bornes annuelles`() {
        val openWithStoredExit = session(
            id = "open-stale-exit",
            startMs = 12_000L,
            endMs = 30_000L,
            status = SessionStatusV2.OPEN
        )

        assertFalse(
            crossesAnnualReportBoundaryV2(
                session = openWithStoredExit,
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = 15_000L
            )
        )
    }

    @Test
    fun `session exactement contenue respecte les bornes debut inclus fin exclue`() {
        assertFalse(
            crossesAnnualReportBoundaryV2(
                session = session("contained", startMs = 10_000L, endMs = 20_000L),
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = null
            )
        )
        assertFalse(
            crossesAnnualReportBoundaryV2(
                session = session("ends-at-start", startMs = 5_000L, endMs = 10_000L),
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = null
            )
        )
        assertFalse(
            crossesAnnualReportBoundaryV2(
                session = session("starts-at-end", startMs = 20_000L, endMs = 21_000L),
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = null
            )
        )
    }

    @Test
    fun `session traversant le debut ou la fin bloque la periode`() {
        assertTrue(
            crossesAnnualReportBoundaryV2(
                session = session("before", startMs = 9_000L, endMs = 11_000L),
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = null
            )
        )
        assertTrue(
            crossesAnnualReportBoundaryV2(
                session = session("after", startMs = 19_000L, endMs = 21_000L),
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = null
            )
        )
    }

    @Test
    fun `session fermee sans fin bloque seulement sa periode dancrage`() {
        val broken = session("broken", startMs = 15_000L, endMs = null)

        assertTrue(
            crossesAnnualReportBoundaryV2(
                session = broken,
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = null
            )
        )
        assertFalse(
            crossesAnnualReportBoundaryV2(
                session = broken,
                rangeStartMs = 20_000L,
                rangeEndMs = 30_000L,
                openEndMs = null
            )
        )
    }

    @Test
    fun `session ouverte touche le present mais pas une periode future`() {
        val open = session("open", startMs = 15_000L, endMs = null, status = SessionStatusV2.OPEN)
        val reportNowMs = 18_000L

        assertTrue(
            touchesAnnualReportRangeV2(
                session = open,
                rangeStartMs = 10_000L,
                rangeEndMs = 20_000L,
                openEndMs = reportNowMs
            )
        )
        assertFalse(
            touchesAnnualReportRangeV2(
                session = open,
                rangeStartMs = 20_000L,
                rangeEndMs = 30_000L,
                openEndMs = reportNowMs
            )
        )
    }

    @Test
    fun `presence exige une arrivee reelle meme avec des bornes comptees`() {
        val missingRealArrival = session("missing-real-arrival", startMs = 10_000L, endMs = 20_000L)
            .copy(realArrivalMs = null)

        assertFalse(annualWorkSessionEndpointsReliableV2(missingRealArrival))
    }

    @Test
    fun `presence fermee et salaire exigent une sortie reelle`() {
        val missingRealExit = session("missing-real-exit", startMs = 10_000L, endMs = 20_000L)
            .copy(realExitMs = null)

        assertFalse(annualWorkSessionEndpointsReliableV2(missingRealExit))
        assertTrue(
            missingRealExit.status != SessionStatusV2.OPEN &&
                missingRealExit.realExitMs == null &&
                touchesAnnualReportRangeV2(
                    session = missingRealExit,
                    rangeStartMs = 10_000L,
                    rangeEndMs = 30_000L,
                    openEndMs = null
                )
        )
    }

    @Test
    fun `presence ouverte accepte une sortie courante partagee`() {
        val open = session("open-presence", startMs = 10_000L, endMs = null, status = SessionStatusV2.OPEN)

        assertTrue(annualWorkSessionEndpointsReliableV2(open))
    }
}
