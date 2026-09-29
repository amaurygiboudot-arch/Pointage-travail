package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveDeliveryNpcBrainTest {
    @Test
    fun staffRoutinesMatchRolesTasksAndTheirWorkAreas() {
        val scene = ObjectiveDeliverySceneCatalog.forChapter(6)
        val production = scene.people.indexOfFirst { it.role == "Production" }
        val quality = scene.people.indexOfFirst { it.role == "Qualité" }
        val logistics = scene.people.indexOfFirst { it.role == "Logistique" }
        val direction = ObjectiveDeliveryNpcBrain.directorIndex(scene)

        assertEquals(3, ObjectiveDeliveryNpcBrain.homeZone(scene, production))
        assertEquals(3, ObjectiveDeliveryNpcBrain.homeZone(scene, quality))
        assertEquals(5, ObjectiveDeliveryNpcBrain.homeZone(scene, logistics))
        assertTrue(ObjectiveDeliveryNpcBrain.routine(scene, direction).isEmpty())

        val productionRoutine = ObjectiveDeliveryNpcBrain.routine(scene, production)
        assertEquals(ObjectiveDeliveryNpcAction.WORK, productionRoutine.first().action)
        assertTrue(productionRoutine.any { it.action == ObjectiveDeliveryNpcAction.COLLABORATE })
        assertTrue(productionRoutine.all { it.destinationZone in 0..5 })
        assertFalse(productionRoutine.any { it.partnerIndex == production })
    }

    @Test
    fun clientFollowsTheStoryFromReceptionToDelivery() {
        val salesScene = ObjectiveDeliverySceneCatalog.forChapter(1)
        val deliveryScene = ObjectiveDeliverySceneCatalog.forChapter(7)
        val salesClient = salesScene.people.indexOfFirst { it.role == "Client" }
        val deliveryClient = deliveryScene.people.indexOfFirst { it.role == "Client" }

        assertEquals(0, ObjectiveDeliveryNpcBrain.homeZone(salesScene, salesClient))
        assertEquals(5, ObjectiveDeliveryNpcBrain.homeZone(deliveryScene, deliveryClient))
        assertTrue(
            ObjectiveDeliveryNpcBrain.routine(deliveryScene, deliveryClient).any {
                it.action == ObjectiveDeliveryNpcAction.WAIT && it.destinationZone == 5
            }
        )
    }

    @Test
    fun onlyAnAffectedNonPlayerCharacterSeeksTheDirection() {
        (1..10).forEach { chapter ->
            val scene = ObjectiveDeliverySceneCatalog.forChapter(chapter, isTroubled = true)
            val director = ObjectiveDeliveryNpcBrain.directorIndex(scene)
            val troubled = scene.troubledPersonIndex
            val troubledRoutine = ObjectiveDeliveryNpcBrain.routine(scene, troubled)
            val seekingStep = troubledRoutine.firstOrNull {
                it.action == ObjectiveDeliveryNpcAction.SEEK_DIRECTION
            }

            if (troubled == director) {
                assertTrue(seekingStep == null)
            } else {
                assertEquals(director, seekingStep?.partnerIndex)
            }
            scene.people.indices
                .filter { it != troubled }
                .forEach { personIndex ->
                    assertFalse(
                        ObjectiveDeliveryNpcBrain.routine(scene, personIndex).any {
                            it.action == ObjectiveDeliveryNpcAction.SEEK_DIRECTION
                        }
                    )
                }
        }
    }
}
