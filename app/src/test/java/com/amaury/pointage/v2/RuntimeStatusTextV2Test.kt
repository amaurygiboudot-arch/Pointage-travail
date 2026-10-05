package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.*
import com.amaury.pointage.v2.ui.RuntimeStatusTextV2
import org.junit.Assert.*
import org.junit.Test

class RuntimeStatusTextV2Test {
    private fun at(hour: Int) = hour * 3_600_000L
    private fun session(pauses: List<PauseV2>, status: SessionStatusV2 = SessionStatusV2.OPEN) = WorkSessionV2(
        id = "current", employerId = null, realArrivalMs = at(8), countedEntryMs = at(8),
        countedExitMs = null, realExitMs = null, pauses = pauses, status = status
    )
    private fun pause(start: Int, end: Int?) = PauseV2(at(start), end?.let(::at), false, EventSourceV2.MANUAL)
    private fun text(session: WorkSessionV2?, hour: Int): String {
        val state = RuntimeObservationV2.assess(session, at(hour))
        return RuntimeStatusTextV2.format(V2RuntimeReader.CurrentRead(
            V2RuntimeStore.Snapshot(session, null), state.reliable, emptyList(), state
        )) { "${it / 3_600_000L}h" }
    }

    @Test fun `production card shows bounded active pause and its actual start`() {
        val session = session(listOf(pause(9, 11)))
        assertEquals("STATUT ACTUEL\n⏸ PAUSE EN COURS\nDepuis 9h", text(session, 10))
        assertEquals("STATUT ACTUEL\n● ENTRÉE EN COURS\nDepuis 8h", text(session, 11))
    }

    @Test fun `ended pause never overrides current bounded pause start`() {
        val session = session(listOf(pause(9, 11), pause(8, 9)))
        assertEquals("STATUT ACTUEL\n⏸ PAUSE EN COURS\nDepuis 9h", text(session, 10))
    }

    @Test fun `open pause and rollback use same state as bounded pause`() {
        val session = session(listOf(pause(9, null)))
        assertEquals("STATUT ACTUEL\n⏸ PAUSE EN COURS\nDepuis 9h", text(session, 10))
        assertEquals("STATUT ACTUEL\n⚠ DONNÉES À VÉRIFIER", text(session, 8))
    }

    @Test fun `no session closed and to confirm are not shown as working`() {
        assertEquals("STATUT ACTUEL\n○ Aucune entrée en cours", text(null, 10))
        assertEquals("STATUT ACTUEL\n● SESSION TERMINÉE", text(session(emptyList(), SessionStatusV2.CLOSED), 10))
        assertEquals("STATUT ACTUEL\n● SESSION À CONFIRMER", text(session(emptyList(), SessionStatusV2.TO_CONFIRM), 10))
    }
}
