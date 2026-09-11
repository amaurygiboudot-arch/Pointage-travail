package com.amaury.pointage.v2.ui

import com.amaury.pointage.v2.engine.DefaultTimeEngineV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryTextFormatterV2Test {
    private val minute = 60_000L
    private val zone = ZoneId.systemDefault()

    @Test
    fun `rendu historique utilise la valeur dentree corrigee et les details de pause`() {
        val session = WorkSessionV2(
            id = "history-1",
            employerId = "employer",
            realArrivalMs = at(6, 8),
            countedEntryMs = at(6, 15),
            countedExitMs = at(13, 37),
            realExitMs = at(13, 37),
            pauses = listOf(
                PauseV2(at(10, 0), at(10, 30), false, EventSourceV2.MANUAL)
            ),
            status = SessionStatusV2.CLOSED,
            placeLabel = "Atelier"
        )

        val text = HistoryTextFormatterV2.format(
            sessions = listOf(session),
            engine = DefaultTimeEngineV2,
            nowMs = at(14, 0),
            options = HistoryTextFormatterV2.Options(
                employerNames = mapOf("employer" to "Oceplast")
            )
        )

        assertTrue(text.contains("06:00  ENTRÉE COMPTÉE (corrigée)"))
        assertFalse(text.contains("06:15  ENTRÉE COMPTÉE"))
        assertTrue(text.contains("🏢 Oceplast"))
        assertTrue(text.contains("Créneau retenu : MATIN"))
        assertTrue(text.contains("Pause 1 : 10:00 → 10:30 [NON PAYÉE, MANUELLE]"))
        assertTrue(text.contains("Temps retenu : 07h 37m"))
        assertTrue(text.contains("Temps payé : 07h 37m"))
    }

    @Test
    fun `duree non certifiable reste a confirmer au lieu de devenir zero`() {
        val session = WorkSessionV2(
            id = "history-2",
            employerId = "employer",
            realArrivalMs = at(8, 0),
            countedEntryMs = null,
            countedExitMs = at(16, 0),
            realExitMs = at(16, 0),
            status = SessionStatusV2.CLOSED
        )

        val text = HistoryTextFormatterV2.format(
            sessions = listOf(session),
            engine = DefaultTimeEngineV2,
            nowMs = at(17, 0)
        )

        assertTrue(text.contains("Temps payé : À CONFIRMER"))
        assertTrue(text.contains("Entrée comptée manquante"))
        assertFalse(text.contains("Temps payé : 00h 00m"))
    }

    @Test
    fun `selection filtre par jour lieu et trie du plus recent au plus ancien`() {
        val older = WorkSessionV2(
            id = "older",
            employerId = "employer",
            realArrivalMs = at(7, 0),
            countedEntryMs = at(7, 0),
            countedExitMs = at(15, 0),
            realExitMs = at(15, 0),
            placeLabel = "Atelier",
            status = SessionStatusV2.CLOSED
        )
        val newer = older.copy(
            id = "newer",
            realArrivalMs = at(9, 0),
            countedEntryMs = at(9, 0),
            countedExitMs = at(17, 0),
            realExitMs = at(17, 0),
            placeLabel = "Chantier"
        )

        val selected = HistoryTextFormatterV2.selectSessions(
            sessions = listOf(older, newer),
            nowMs = at(18, 0),
            todayOnly = true,
            query = "chantier"
        )

        assertTrue(selected.map { it.id } == listOf("newer"))
    }

    private fun at(hour: Int, minuteOfHour: Int): Long =
        LocalDate.of(2026, 9, 4)
            .atTime(hour, minuteOfHour)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
