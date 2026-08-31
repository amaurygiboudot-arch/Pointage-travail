package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreRegressionV2Test {
    private val minute = 60_000L

    @Test
    fun `repos journalier utilise les bornes comptees et trie les sessions`() {
        val first = session("first", 60 * minute, 8 * 60 * minute)
        val second = session("second", 20 * 60 * minute, 28 * 60 * minute)
        val overlapping = session("overlap", 27 * 60 * minute, 30 * 60 * minute)

        val rests = RestEngineV2.dailyRests(listOf(second, first, overlapping))

        assertEquals(1, rests.size)
        assertEquals("first", rests.single().previousSessionId)
        assertEquals("second", rests.single().nextSessionId)
        assertEquals(12 * 60 * minute, rests.single().restMs)
    }

    @Test
    fun `analyse additionne presence et temps paye depuis le moteur temps`() {
        val first = session("first", 60 * minute, 3 * 60 * minute)
        val second = session("second", 4 * 60 * minute, 7 * 60 * minute)

        val analytics = AnalyticsEngineV2.summarize(
            listOf(first, second),
            DefaultTimeEngineV2,
            nowMs = 8 * 60 * minute
        )

        assertEquals(5 * 60 * minute, analytics.totalPresenceMs)
        assertEquals(5 * 60 * minute, analytics.totalPaidMs)
        assertEquals(2, analytics.sessions)
        assertEquals(0, analytics.warnings)
    }

    @Test
    fun `pdf exemple ne contient que les rubriques choisies`() {
        val document = PdfEngineV2.salaryExample(
            PdfSelectionV2(setOf(PdfFieldV2.COMPANY, PdfFieldV2.HOURS)),
            mapOf(
                PdfFieldV2.COMPANY to "Entreprise test",
                PdfFieldV2.HOURS to "35h00",
                PdfFieldV2.SOURCES to "Source non sélectionnée"
            )
        )

        assertEquals("FICHE DE PAIE EXEMPLE", document.title)
        assertEquals("ESTIMATION HORATRACK", document.subtitle)
        assertEquals(2, document.sections.size)
        assertTrue(document.sections.any { it.first == PdfFieldV2.COMPANY.name })
        assertTrue(document.sections.any { it.first == PdfFieldV2.HOURS.name })
        assertTrue(document.sections.none { it.first == PdfFieldV2.SOURCES.name })
        assertEquals("© HoraTrack", document.footer)
    }

    private fun session(id: String, start: Long, end: Long) = WorkSessionV2(
        id = id,
        employerId = "employer",
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        status = SessionStatusV2.CLOSED
    )
}
