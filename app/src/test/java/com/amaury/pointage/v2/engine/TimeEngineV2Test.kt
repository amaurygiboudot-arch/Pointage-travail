package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeEngineV2Test {
    private val minute = 60_000L
    private val base = 60 * minute

    @Test
    fun `entree respecte le quart heure avec cinq minutes de grace`() {
        assertEquals(base, DefaultTimeEngineV2.countedEntryFromRealArrival(base))
        assertEquals(base, DefaultTimeEngineV2.countedEntryFromRealArrival(base + 5 * minute))
        assertEquals(base + 15 * minute, DefaultTimeEngineV2.countedEntryFromRealArrival(base + 6 * minute))
    }

    @Test
    fun `sortie comptee conserve la sortie reelle meme apres heure prevue`() {
        val expected = base
        assertEquals(expected, DefaultTimeEngineV2.countedExitFromRealExit(expected, expected))
        assertEquals(expected + 20 * minute, DefaultTimeEngineV2.countedExitFromRealExit(expected + 20 * minute, expected))
        assertEquals(expected + 23 * minute, DefaultTimeEngineV2.countedExitFromRealExit(expected + 23 * minute, expected))
        assertEquals(expected + 2 * 60 * minute, DefaultTimeEngineV2.countedExitFromRealExit(expected + 2 * 60 * minute, expected))
    }

    @Test
    fun `sortie avant heure prevue reste heure reelle`() {
        val expected = base + 60 * minute
        val actual = base + 37 * minute
        assertEquals(actual, DefaultTimeEngineV2.countedExitFromRealExit(actual, expected))
    }

    @Test
    fun `sortie sans horaire prevu reste heure reelle`() {
        val actual = base + 37 * minute
        assertEquals(actual, DefaultTimeEngineV2.countedExitFromRealExit(actual, null))
    }

    @Test
    fun `pause non payee confirmee est deduite et pause payee reste travail paye`() {
        val session = closedSession(
            pauses = listOf(
                PauseV2(base + 2 * 60 * minute, base + 150 * minute, false, EventSourceV2.MANUAL),
                PauseV2(base + 3 * 60 * minute, base + 195 * minute, true, EventSourceV2.MANUAL)
            )
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(8 * 60 * minute, result.countedSpanMs)
        assertEquals(30 * minute, result.unpaidPauseMs)
        assertEquals(15 * minute, result.paidPauseMs)
        assertEquals(7 * 60 * minute + 30 * minute, result.paidWorkMs)
    }

    @Test
    fun `pause a confirmer nest pas deduite et produit un avertissement`() {
        val session = closedSession(
            pauses = listOf(
                PauseV2(
                    startMs = base + 2 * 60 * minute,
                    endMs = base + 150 * minute,
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
    fun `pauses non payees qui se chevauchent ne sont deduites quune fois`() {
        val session = closedSession(
            pauses = listOf(
                PauseV2(base + 120 * minute, base + 150 * minute, false, EventSourceV2.MANUAL),
                PauseV2(base + 135 * minute, base + 165 * minute, false, EventSourceV2.MANUAL)
            )
        )

        val result = DefaultTimeEngineV2.calculate(session)

        assertEquals(45 * minute, result.unpaidPauseMs)
        assertEquals(8 * 60 * minute - 45 * minute, result.paidWorkMs)
    }

    @Test
    fun `deduction historique fixe est conservee et bornee a la session`() {
        val normal = closedSession(legacyFixedUnpaidPauseMs = 45 * minute)
        val normalResult = DefaultTimeEngineV2.calculate(normal)
        assertEquals(45 * minute, normalResult.unpaidPauseMs)
        assertEquals(8 * 60 * minute - 45 * minute, normalResult.paidWorkMs)
        assertTrue(normalResult.warnings.contains("Déduction fixe historique importée"))

        val excessive = closedSession(legacyFixedUnpaidPauseMs = 12 * 60 * minute)
        val excessiveResult = DefaultTimeEngineV2.calculate(excessive)
        assertEquals(8 * 60 * minute, excessiveResult.unpaidPauseMs)
        assertEquals(0L, excessiveResult.paidWorkMs)
    }

    private fun closedSession(
        pauses: List<PauseV2> = emptyList(),
        legacyFixedUnpaidPauseMs: Long = 0L
    ) = WorkSessionV2(
        id = "time-test",
        employerId = "employer",
        realArrivalMs = base,
        countedEntryMs = base,
        countedExitMs = base + 8 * 60 * minute,
        realExitMs = base + 8 * 60 * minute,
        pauses = pauses,
        status = SessionStatusV2.CLOSED,
        legacyFixedUnpaidPauseMs = legacyFixedUnpaidPauseMs
    )
}
