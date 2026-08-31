package com.amaury.pointage.v2.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsRegressionV2Test {
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
    fun `retour rapide exige meme poste et vraie entree`() {
        val pending = pending(atMs = 10_000L)

        assertFalse(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(pending, event(id = "x", atMs = 20_000L, placeId = "autre")))
        assertFalse(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(pending, event(id = "x", atMs = 20_000L, transition = GpsTransitionV2.EXIT)))
        assertFalse(GpsWorkStateCoordinatorV2.isQuickReturnToPoste(
            pending.copy(kind = GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS),
            event(id = "x", atMs = 20_000L)
        ))
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
}
