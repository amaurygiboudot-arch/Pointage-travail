package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsTransitionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsActiveZoneTransitionV2Test {
    @Test
    fun `entree simultanee ambiguë conserve toutes les zones pour resolution`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = emptySet(),
            triggeredZoneIds = listOf("zone-b", "zone-a"),
            transition = GpsTransitionV2.ENTER,
            entryResolutionPending = false
        )

        assertEquals(setOf("zone-a", "zone-b"), plan.activeZoneIds)
        assertEquals(
            GpsActiveZoneTransitionV2.Action.ResolveEntry(listOf("zone-a", "zone-b")),
            plan.action
        )
    }

    @Test
    fun `sortie d une zone reévalue la zone restante apres entree ambiguë`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a", "zone-b"),
            triggeredZoneIds = listOf("zone-b"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = true
        )

        assertEquals(setOf("zone-a"), plan.activeZoneIds)
        assertTrue(plan.entryResolutionPending)
        assertEquals(
            GpsActiveZoneTransitionV2.Action.ResolveEntry(listOf("zone-a")),
            plan.action
        )
    }

    @Test
    fun `sortie complete annule l entree ambiguë sans fabriquer de sortie`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a", "zone-b"),
            triggeredZoneIds = listOf("zone-a", "zone-b"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = true
        )

        assertTrue(plan.activeZoneIds.isEmpty())
        assertFalse(plan.entryResolutionPending)
        assertEquals(GpsActiveZoneTransitionV2.Action.None, plan.action)
    }

    @Test
    fun `ambiguite persistante apres une sortie reste a resoudre`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a", "zone-b", "zone-c"),
            triggeredZoneIds = listOf("zone-c"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = true
        )

        assertEquals(setOf("zone-a", "zone-b"), plan.activeZoneIds)
        assertTrue(plan.entryResolutionPending)
        assertEquals(
            GpsActiveZoneTransitionV2.Action.ResolveEntry(listOf("zone-a", "zone-b")),
            plan.action
        )
    }

    @Test
    fun `ordre et doublons de sortie donnent le meme plan`() {
        val first = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a", "zone-b"),
            triggeredZoneIds = listOf("zone-b", "zone-a", "zone-b"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = false
        )
        val second = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-b", "zone-a"),
            triggeredZoneIds = listOf("zone-a", "zone-b"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = false
        )

        assertEquals(first, second)
        assertEquals(
            GpsActiveZoneTransitionV2.Action.ResolveExit(listOf("zone-a", "zone-b")),
            first.action
        )
    }

    @Test
    fun `sorties successives conservent toutes les zones pour l arbitrage final`() {
        fun sequential(first: String, second: String): Pair<
            GpsActiveZoneTransitionV2.Plan,
            GpsActiveZoneTransitionV2.Plan
        > {
            val firstExit = GpsActiveZoneTransitionV2.plan(
                activeZoneIds = setOf("zone-a", "zone-b"),
                triggeredZoneIds = listOf(first),
                transition = GpsTransitionV2.EXIT,
                entryResolutionPending = false
            )
            val finalExit = GpsActiveZoneTransitionV2.plan(
                activeZoneIds = firstExit.activeZoneIds,
                triggeredZoneIds = listOf(second),
                transition = GpsTransitionV2.EXIT,
                entryResolutionPending = firstExit.entryResolutionPending,
                pendingExitZoneIds = firstExit.pendingExitZoneIds
            )
            return firstExit to finalExit
        }

        val (firstExit, finalExit) = sequential("zone-a", "zone-b")
        val (_, reversedFinalExit) = sequential("zone-b", "zone-a")

        assertEquals(setOf("zone-a"), firstExit.pendingExitZoneIds)
        assertEquals(GpsActiveZoneTransitionV2.Action.None, firstExit.action)
        assertEquals(emptySet<String>(), finalExit.pendingExitZoneIds)
        assertEquals(
            GpsActiveZoneTransitionV2.Action.ResolveExit(listOf("zone-a", "zone-b")),
            finalExit.action
        )
        assertEquals(finalExit.action, reversedFinalExit.action)
    }

    @Test
    fun `une zone reentree est retiree des sorties en attente`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-b"),
            triggeredZoneIds = listOf("zone-a"),
            transition = GpsTransitionV2.ENTER,
            entryResolutionPending = false,
            pendingExitZoneIds = setOf("zone-a")
        )

        assertEquals(setOf("zone-a", "zone-b"), plan.activeZoneIds)
        assertEquals(emptySet<String>(), plan.pendingExitZoneIds)
    }

    @Test
    fun `entrees separees restent pending jusqu a la resolution differee`() {
        val first = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = emptySet(),
            triggeredZoneIds = listOf("zone-a"),
            transition = GpsTransitionV2.ENTER,
            entryResolutionPending = false
        )
        val second = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = first.activeZoneIds,
            triggeredZoneIds = listOf("zone-b"),
            transition = GpsTransitionV2.ENTER,
            entryResolutionPending = true,
            pendingExitZoneIds = first.pendingExitZoneIds
        )

        assertEquals(setOf("zone-a", "zone-b"), second.activeZoneIds)
        assertTrue(second.entryResolutionPending)
        assertEquals(GpsActiveZoneTransitionV2.Action.None, second.action)
    }

    @Test
    fun `etats de presence GPS ne sont jamais transferables`() {
        GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach { key ->
            assertFalse(GpsPresenceStateKeysV2.isTransferablePreferenceKey("gps_settings", key))
        }
        assertTrue(
            GpsPresenceStateKeysV2.isTransferablePreferenceKey("gps_settings", "zones")
        )
        assertTrue(
            GpsPresenceStateKeysV2.isTransferablePreferenceKey("other_prefs", "active_zones")
        )
    }

    @Test
    fun `un ancien timer ne peut pas resoudre un nouveau cycle d entree`() {
        assertFalse(
            GpsPresenceStateKeysV2.isCurrentEntryResolution(
                expectedToken = "cycle-a",
                entryResolutionPending = true,
                storedToken = "cycle-b"
            )
        )
        assertTrue(
            GpsPresenceStateKeysV2.isCurrentEntryResolution(
                expectedToken = "cycle-b",
                entryResolutionPending = true,
                storedToken = "cycle-b"
            )
        )
        assertFalse(
            GpsPresenceStateKeysV2.isCurrentEntryResolution(
                expectedToken = "cycle-b",
                entryResolutionPending = false,
                storedToken = "cycle-b"
            )
        )
    }

    @Test
    fun `sortie partielle normale ne cree ni entree ni sortie`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a", "zone-b"),
            triggeredZoneIds = listOf("zone-a"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = false
        )

        assertEquals(setOf("zone-b"), plan.activeZoneIds)
        assertEquals(GpsActiveZoneTransitionV2.Action.None, plan.action)
    }

    @Test
    fun `sortie stale d une zone inactive ne pollue pas l arbitrage final`() {
        val staleExit = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a"),
            triggeredZoneIds = listOf("zone-b"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = false
        )
        val realExit = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = staleExit.activeZoneIds,
            triggeredZoneIds = listOf("zone-a"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = staleExit.entryResolutionPending,
            pendingExitZoneIds = staleExit.pendingExitZoneIds
        )

        assertEquals(setOf("zone-a"), staleExit.activeZoneIds)
        assertTrue(staleExit.pendingExitZoneIds.isEmpty())
        assertEquals(GpsActiveZoneTransitionV2.Action.None, staleExit.action)
        assertEquals(
            GpsActiveZoneTransitionV2.Action.ResolveExit(listOf("zone-a")),
            realExit.action
        )
    }

    @Test
    fun `sortie stale ne resout pas une entree encore dans sa fenetre de batch`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a"),
            triggeredZoneIds = listOf("zone-b"),
            transition = GpsTransitionV2.EXIT,
            entryResolutionPending = true,
            pendingExitZoneIds = setOf("zone-old")
        )

        assertEquals(setOf("zone-a"), plan.activeZoneIds)
        assertEquals(setOf("zone-old"), plan.pendingExitZoneIds)
        assertTrue(plan.entryResolutionPending)
        assertEquals(GpsActiveZoneTransitionV2.Action.None, plan.action)
        assertTrue(GpsActiveZoneTransitionV2.needsDeferredEntryResolution(plan))
    }

    @Test
    fun `entree repetee dans une zone active ne redemarre pas une session`() {
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = setOf("zone-a"),
            triggeredZoneIds = listOf("zone-a", "zone-a"),
            transition = GpsTransitionV2.ENTER,
            entryResolutionPending = false
        )

        assertEquals(setOf("zone-a"), plan.activeZoneIds)
        assertEquals(GpsActiveZoneTransitionV2.Action.None, plan.action)
    }
}
