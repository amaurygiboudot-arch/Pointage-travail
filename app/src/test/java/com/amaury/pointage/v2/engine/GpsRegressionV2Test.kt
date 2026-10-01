package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsRegressionV2Test {
    @Test
    fun `runtime non fiable bloque toute transition GPS y compris une entree ambigue`() {
        assertFalse(GpsWorkStateCoordinatorV2.canRouteWithRuntime(false))
        assertTrue(GpsWorkStateCoordinatorV2.canRouteWithRuntime(true))
    }

    @Test
    fun `transition ambigue exige une session ouverte et un etat de pause compatible`() {
        val open = session()
        val paused = session(
            pauses = listOf(PauseV2(2_000L, null, paid = true, source = EventSourceV2.GPS))
        )

        assertFalse(GpsWorkStateCoordinatorV2.canQueueAmbiguous(null, GpsTransitionV2.ENTER))
        assertFalse(
            GpsWorkStateCoordinatorV2.canQueueAmbiguous(
                session(status = SessionStatusV2.CLOSED, realExitMs = 3_000L),
                GpsTransitionV2.ENTER
            )
        )
        assertTrue(GpsWorkStateCoordinatorV2.canQueueAmbiguous(open, GpsTransitionV2.ENTER))
        assertFalse(GpsWorkStateCoordinatorV2.canQueueAmbiguous(open, GpsTransitionV2.EXIT))
        assertFalse(GpsWorkStateCoordinatorV2.canQueueAmbiguous(paused, GpsTransitionV2.ENTER))
        assertTrue(GpsWorkStateCoordinatorV2.canQueueAmbiguous(paused, GpsTransitionV2.EXIT))
    }

    @Test
    fun `une sortie chantier remplace une ambiguite mais aucune autre confirmation`() {
        val exit = pending(atMs = 10_000L)
        val ambiguous = exit.copy(
            kind = GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS,
            pointType = GpsPointTypeV2.PARKING,
            transition = GpsTransitionV2.ENTER
        )

        assertTrue(
            GpsWorkStateCoordinatorV2.canQueuePending(
                null,
                GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS
            )
        )
        assertFalse(
            GpsWorkStateCoordinatorV2.canQueuePending(
                exit,
                GpsWorkStateCoordinatorV2.Pending.Kind.EXIT_WORKSITE
            )
        )
        assertFalse(
            GpsWorkStateCoordinatorV2.canQueuePending(
                exit,
                GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS
            )
        )
        assertFalse(
            GpsWorkStateCoordinatorV2.canQueuePending(
                ambiguous,
                GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS
            )
        )
        assertTrue(
            GpsWorkStateCoordinatorV2.canQueuePending(
                ambiguous,
                GpsWorkStateCoordinatorV2.Pending.Kind.EXIT_WORKSITE
            )
        )
    }

    @Test
    fun `un ancien dialogue ne peut pas agir sur le pending qui l a remplace`() {
        val oldPrompt = pending(atMs = 10_000L).copy(
            id = "ambiguous-old",
            kind = GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS
        )
        val replacement = pending(atMs = 20_000L).copy(id = "exit-new")

        assertTrue(GpsWorkStateCoordinatorV2.matchesPendingId(oldPrompt, "ambiguous-old"))
        assertFalse(GpsWorkStateCoordinatorV2.matchesPendingId(replacement, "ambiguous-old"))
        assertTrue(GpsWorkStateCoordinatorV2.matchesPendingId(replacement, "exit-new"))
    }

    @Test
    fun `anti rebond ignore meme evenement avant trente secondes`() {
        val engine = GpsEngineV2()
        val first = event(id = "1", atMs = 1_000L)
        val duplicate = event(id = "2", atMs = 30_999L)

        assertTrue(engine.ingest(first).accepted)
        val decision = engine.ingest(duplicate)
        assertFalse(decision.accepted)
        assertTrue(decision.duplicate)
    }

    @Test
    fun `anti rebond accepte exactement a trente secondes`() {
        val engine = GpsEngineV2()
        assertTrue(engine.ingest(event(id = "1", atMs = 1_000L)).accepted)
        val decision = engine.ingest(event(id = "2", atMs = 31_000L))
        assertTrue(decision.accepted)
        assertFalse(decision.duplicate)
    }

    @Test
    fun `transition opposee rearme l anti rebond pour un nouveau cycle`() {
        val engine = GpsEngineV2()

        assertTrue(
            engine.ingest(event(id = "exit-1", atMs = 1_000L, transition = GpsTransitionV2.EXIT)).accepted
        )
        assertTrue(
            engine.ingest(event(id = "enter", atMs = 5_000L, transition = GpsTransitionV2.ENTER)).accepted
        )
        val secondExit = engine.ingest(
            event(id = "exit-2", atMs = 10_000L, transition = GpsTransitionV2.EXIT)
        )

        assertTrue(secondExit.accepted)
        assertFalse(secondExit.duplicate)
    }

    @Test
    fun `cycle entree sortie entree est aussi rearme`() {
        val engine = GpsEngineV2()

        assertTrue(engine.ingest(event(id = "enter-1", atMs = 1_000L)).accepted)
        assertTrue(
            engine.ingest(event(id = "exit", atMs = 5_000L, transition = GpsTransitionV2.EXIT)).accepted
        )
        assertTrue(engine.ingest(event(id = "enter-2", atMs = 10_000L)).accepted)
    }

    @Test
    fun `parking et autre restent ambigus mais poste ne lest pas`() {
        val parking = GpsEngineV2().ingest(event(id = "p", atMs = 1_000L, pointType = GpsPointTypeV2.PARKING))
        val other = GpsEngineV2().ingest(event(id = "o", atMs = 1_000L, pointType = GpsPointTypeV2.OTHER))
        val poste = GpsEngineV2().ingest(event(id = "w", atMs = 1_000L, pointType = GpsPointTypeV2.POSTE))

        assertTrue(parking.requiresConfirmation)
        assertTrue(other.requiresConfirmation)
        assertFalse(poste.requiresConfirmation)
    }

    @Test
    fun `retour au meme poste dans deux minutes annule la sortie`() {
        val pending = pending(atMs = 10_000L)

        assertTrue(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(pending, event(id = "r1", atMs = 10_000L)))
        assertTrue(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(pending, event(id = "r2", atMs = 130_000L)))
        assertFalse(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(pending, event(id = "r3", atMs = 130_001L)))
    }

    @Test
    fun `retour au meme poste apres deux minutes annule aussi la sortie en attente`() {
        val pending = pending(atMs = 10_000L)
        val returnedLater = event(id = "return-later", atMs = 600_000L)

        assertFalse(
            GpsWorkStateCoordinatorV2.canApplyQuickReturn(
                pending,
                returnedLater,
                session()
            )
        )
        assertTrue(
            GpsWorkStateCoordinatorV2.canApplyReturnToPoste(
                pending,
                returnedLater,
                session()
            )
        )
        assertFalse(
            GpsWorkStateCoordinatorV2.canApplyReturnToPoste(
                pending,
                returnedLater.copy(placeId = "autre"),
                session()
            )
        )
    }

    @Test
    fun `retour rapide exige meme poste et vraie entree`() {
        val pending = pending(atMs = 10_000L)

        assertFalse(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(pending, event(id = "x", atMs = 20_000L, placeId = "autre")))
        assertFalse(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(pending, event(id = "x", atMs = 20_000L, transition = GpsTransitionV2.EXIT)))
        assertFalse(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(
            pending.copy(kind = GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS),
            event(id = "x", atMs = 20_000L)
        ))
    }

    @Test
    fun `retour rapide exige que la session concernee soit encore ouverte`() {
        val pending = pending(atMs = 10_000L)
        val event = event(id = "return", atMs = 20_000L)

        assertTrue(
            GpsWorkStateCoordinatorV2.canApplyQuickReturn(
                pending,
                event,
                session()
            )
        )
        assertFalse(
            GpsWorkStateCoordinatorV2.canApplyQuickReturn(
                pending,
                event,
                session(status = SessionStatusV2.CLOSED, realExitMs = 15_000L)
            )
        )
        assertFalse(
            GpsWorkStateCoordinatorV2.canApplyQuickReturn(
                pending.copy(atMs = 500L),
                event,
                session()
            )
        )
    }

    @Test
    fun `pending d une ancienne session est abandonne avant la nouvelle session`() {
        val stale = pending(atMs = 500L)
        val current = session()

        assertTrue(GpsWorkStateCoordinatorV2.shouldDiscardPending(stale, current))
        assertFalse(
            GpsWorkStateCoordinatorV2.shouldDiscardPending(
                pending(atMs = 1_500L),
                current
            )
        )
        assertTrue(
            GpsWorkStateCoordinatorV2.shouldDiscardPending(
                stale,
                session(status = SessionStatusV2.CLOSED, realExitMs = 900L),
                entryStarted = true
            )
        )
    }

    private fun event(
        id: String,
        atMs: Long,
        placeId: String = "poste-a",
        pointType: GpsPointTypeV2 = GpsPointTypeV2.POSTE,
        transition: GpsTransitionV2 = GpsTransitionV2.ENTER
    ) = GpsEventV2(id, atMs, placeId, pointType, transition)

    private fun pending(atMs: Long) = GpsWorkStateCoordinatorV2.Pending(
        id = "pending",
        atMs = atMs,
        placeId = "poste-a",
        pointType = GpsPointTypeV2.POSTE,
        transition = GpsTransitionV2.EXIT,
        kind = GpsWorkStateCoordinatorV2.Pending.Kind.EXIT_WORKSITE
    )

    private fun session(
        status: SessionStatusV2 = SessionStatusV2.OPEN,
        realExitMs: Long? = null,
        pauses: List<PauseV2> = emptyList()
    ) = WorkSessionV2(
        id = "session-a",
        employerId = "company-a",
        realArrivalMs = 1_000L,
        countedEntryMs = 1_000L,
        countedExitMs = realExitMs,
        realExitMs = realExitMs,
        pauses = pauses,
        status = status
    )
}
