package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimeEngineV2Test {
    private val minute = 60_000L
    private val zone = ZoneId.systemDefault()
    private val dayBase = at(8, 0)
    private val morningBase = at(6, 0)

    @Test
    fun `entree respecte trente minutes avec dix minutes de grace`() {
        assertEquals(morningBase, DefaultTimeEngineV2.countedEntryFromRealArrival(at(6, 0)))
        assertEquals(morningBase, DefaultTimeEngineV2.countedEntryFromRealArrival(at(6, 10)))
        assertEquals(at(6, 30), DefaultTimeEngineV2.countedEntryFromRealArrival(at(6, 11)))
        assertEquals(at(6, 30), DefaultTimeEngineV2.countedEntryFromRealArrival(at(6, 28)))
    }

    @Test
    fun `ancienne entree quinze cinq connue est reparee sans toucher une autre valeur`() {
        assertEquals(at(6, 0), WorkTimePolicyV2.repairKnownCountedEntry(at(6, 8), at(6, 15)))
        assertEquals(at(6, 7), WorkTimePolicyV2.repairKnownCountedEntry(at(6, 8), at(6, 7)))
    }

    @Test
    fun `poste matin commence aussi a cinq heures et ouvre le panier automatique`() {
        assertEquals(WorkTimePolicyV2.ShiftKind.MORNING, WorkTimePolicyV2.shiftKind(at(5, 0)))
        assertEquals(WorkTimePolicyV2.ShiftKind.MORNING, WorkTimePolicyV2.shiftKind(at(5, 30)))
        assertEquals(WorkTimePolicyV2.ShiftKind.MORNING, WorkTimePolicyV2.shiftKind(at(6, 30)))
        assertEquals(WorkTimePolicyV2.ShiftKind.DAY, WorkTimePolicyV2.shiftKind(at(7, 0)))
        assertTrue(WorkTimePolicyV2.hasAutomaticMorningBasket(at(5, 0)))
    }

    @Test
    fun `ancienne entree cinq heures quinze est reparee et reste un poste matin`() {
        val repaired = WorkTimePolicyV2.repairKnownCountedEntry(at(5, 8), at(5, 15))
        assertEquals(at(5, 0), repaired)
        assertTrue(WorkTimePolicyV2.hasAutomaticMorningBasket(repaired!!))
    }

    @Test
    fun `sortie jusque vingt minutes apres reste heure prevue puis devient reelle`() {
        val expected = at(13, 0)
        assertEquals(expected, DefaultTimeEngineV2.countedExitFromRealExit(expected, expected))
        assertEquals(expected, DefaultTimeEngineV2.countedExitFromRealExit(at(13, 20), expected))
        assertEquals(at(13, 21), DefaultTimeEngineV2.countedExitFromRealExit(at(13, 21), expected))
        assertEquals(at(15, 0), DefaultTimeEngineV2.countedExitFromRealExit(at(15, 0), expected))
    }

    @Test
    fun `sortie avant heure prevue reste heure reelle`() {
        val expected = at(16, 0)
        val actual = at(15, 37)
        assertEquals(actual, DefaultTimeEngineV2.countedExitFromRealExit(actual, expected))
    }

    @Test
    fun `sortie sans horaire prevu reste heure reelle`() {
        val actual = at(13, 37)
        assertEquals(actual, DefaultTimeEngineV2.countedExitFromRealExit(actual, null))
    }

    @Test
    fun `pause non payee de jour est deduite et pause payee reste travail paye`() {
        val session = closedSession(
            baseMs = dayBase,
            pauses = listOf(
                PauseV2(dayBase + 2 * 60 * minute, dayBase + 150 * minute, false, EventSourceV2.MANUAL),
                PauseV2(dayBase + 3 * 60 * minute, dayBase + 195 * minute, true, EventSourceV2.MANUAL)
            )
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(8 * 60 * minute, result.countedSpanMs)
        assertEquals(30 * minute, result.unpaidPauseMs)
        assertEquals(15 * minute, result.paidPauseMs)
        assertEquals(7 * 60 * minute + 30 * minute, result.paidWorkMs)
    }

    @Test
    fun `pause de trente minutes du poste matin reste temps paye`() {
        val session = closedSession(
            baseMs = morningBase,
            pauses = listOf(
                PauseV2(morningBase + 4 * 60 * minute, morningBase + 270 * minute, false, EventSourceV2.MANUAL)
            )
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(0L, result.unpaidPauseMs)
        assertEquals(30 * minute, result.paidPauseMs)
        assertEquals(8 * 60 * minute, result.paidWorkMs)
        assertTrue(result.warnings.any { it.contains("Pause d'équipe") })
    }

    @Test
    fun `journee signalee six huit treize trente sept compte sept heures trente sept`() {
        val session = WorkSessionV2(
            id = "reported-day",
            employerId = "employer",
            realArrivalMs = at(6, 8),
            countedEntryMs = at(6, 15),
            countedExitMs = at(13, 37),
            realExitMs = at(13, 37),
            pauses = listOf(
                PauseV2(at(10, 0), at(10, 30), false, EventSourceV2.MANUAL)
            ),
            status = SessionStatusV2.CLOSED
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(7 * 60 * minute + 37 * minute, result.countedSpanMs)
        assertEquals(0L, result.unpaidPauseMs)
        assertEquals(30 * minute, result.paidPauseMs)
        assertEquals(7 * 60 * minute + 37 * minute, result.paidWorkMs)
        assertTrue(WorkTimePolicyV2.hasAutomaticMorningBasket(at(6, 0)))
    }

    @Test
    fun `pause equipe de quarante cinq minutes ne deduit que quinze minutes`() {
        val session = closedSession(
            baseMs = morningBase,
            pauses = listOf(
                PauseV2(morningBase + 4 * 60 * minute, morningBase + 285 * minute, false, EventSourceV2.MANUAL)
            )
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(15 * minute, result.unpaidPauseMs)
        assertEquals(30 * minute, result.paidPauseMs)
        assertEquals(8 * 60 * minute - 15 * minute, result.paidWorkMs)
    }

    @Test
    fun `poste matin donne droit au panier automatique mais pas le poste jour`() {
        assertTrue(WorkTimePolicyV2.hasAutomaticMorningBasket(morningBase))
        assertEquals(false, WorkTimePolicyV2.hasAutomaticMorningBasket(dayBase))
    }

    @Test
    fun `pause a confirmer nest pas deduite et produit un avertissement`() {
        val session = closedSession(
            baseMs = dayBase,
            pauses = listOf(
                PauseV2(
                    startMs = dayBase + 2 * 60 * minute,
                    endMs = dayBase + 150 * minute,
                    paid = false,
                    source = EventSourceV2.MANUAL,
                    status = DecisionStatusV2.TO_CONFIRM
                )
            )
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(0L, result.unpaidPauseMs)
        assertEquals(8 * 60 * minute, result.paidWorkMs)
        assertTrue(result.warnings.any { it.contains("pause(s) à confirmer") })
    }

    @Test
    fun `pauses non payees de jour qui se chevauchent ne sont deduites quune fois`() {
        val session = closedSession(
            baseMs = dayBase,
            pauses = listOf(
                PauseV2(dayBase + 120 * minute, dayBase + 150 * minute, false, EventSourceV2.MANUAL),
                PauseV2(dayBase + 135 * minute, dayBase + 165 * minute, false, EventSourceV2.MANUAL)
            )
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(45 * minute, result.unpaidPauseMs)
        assertEquals(8 * 60 * minute - 45 * minute, result.paidWorkMs)
    }

    @Test
    fun `deduction historique fixe est conservee et bornee a la session`() {
        val normal = closedSession(baseMs = dayBase, legacyFixedUnpaidPauseMs = 45 * minute)
        val normalResult = DefaultTimeEngineV2.calculate(normal)
        assertEquals(45 * minute, normalResult.unpaidPauseMs)
        assertEquals(8 * 60 * minute - 45 * minute, normalResult.paidWorkMs)
        assertTrue(normalResult.warnings.contains("Déduction fixe historique importée"))

        val excessive = closedSession(baseMs = dayBase, legacyFixedUnpaidPauseMs = 12 * 60 * minute)
        val excessiveResult = DefaultTimeEngineV2.calculate(excessive)
        assertEquals(8 * 60 * minute, excessiveResult.unpaidPauseMs)
        assertEquals(0L, excessiveResult.paidWorkMs)
    }

    private fun closedSession(
        baseMs: Long,
        pauses: List<PauseV2> = emptyList(),
        legacyFixedUnpaidPauseMs: Long = 0L
    ) = WorkSessionV2(
        id = "time-test",
        employerId = "employer",
        realArrivalMs = baseMs,
        countedEntryMs = baseMs,
        countedExitMs = baseMs + 8 * 60 * minute,
        realExitMs = baseMs + 8 * 60 * minute,
        pauses = pauses,
        status = SessionStatusV2.CLOSED,
        legacyFixedUnpaidPauseMs = legacyFixedUnpaidPauseMs
    )

    private fun at(hour: Int, minuteOfHour: Int): Long =
        LocalDate.of(2026, 9, 4)
            .atTime(hour, minuteOfHour)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
