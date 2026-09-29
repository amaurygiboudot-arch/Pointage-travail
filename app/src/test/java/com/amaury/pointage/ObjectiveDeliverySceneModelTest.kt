package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveDeliverySceneModelTest {
    @Test
    fun everyChapterHasAReadableStoryAndASelectableMovingTeam() {
        (1..10).forEach { chapter ->
            val scene = ObjectiveDeliverySceneCatalog.forChapter(chapter)

            assertEquals(chapter, scene.chapter)
            assertTrue(scene.headline.isNotBlank())
            assertTrue(scene.story.isNotBlank())
            assertTrue(scene.people.size >= 3)
            assertTrue(scene.people.all { it.route.size >= 2 })
            assertTrue(scene.people.flatMap { it.route }.all {
                it.x in 0f..1f && it.y in 0f..1f
            })
            assertTrue(scene.people.all { it.mood == ObjectiveDeliverySceneMood.FRIENDLY })
        }
    }

    @Test
    fun onlyTheRelevantPersonIsAngryWhenTheStoryMarksAConflict() {
        (1..10).forEach { chapter ->
            val scene = ObjectiveDeliverySceneCatalog.forChapter(
                chapter = chapter,
                isTroubled = true
            )

            assertEquals(1, scene.people.count { it.mood == ObjectiveDeliverySceneMood.ANGRY })
            assertEquals(
                ObjectiveDeliverySceneMood.ANGRY,
                scene.people[scene.troubledPersonIndex].mood
            )
            assertEquals(
                scene.people.size - 1,
                scene.people.count { it.mood == ObjectiveDeliverySceneMood.FRIENDLY }
            )
        }
    }

    @Test
    fun aLostFirstChapterMakesOnlyTheClientAngry() {
        val start = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP)
        val lost = start.copy(
            phase = ObjectiveDeliveryPhase.RESULT,
            outcome = ObjectiveDeliveryOutcome.LOST
        )

        val initialScene = ObjectiveDeliverySceneCatalog.forCampaign(start)
        val resultScene = ObjectiveDeliverySceneCatalog.forCampaign(lost)

        assertTrue(initialScene.people.all { it.mood == ObjectiveDeliverySceneMood.FRIENDLY })
        assertFalse(resultScene.people[0].mood == ObjectiveDeliverySceneMood.FRIENDLY)
        assertEquals(
            ObjectiveDeliverySceneMood.ANGRY,
            resultScene.people[0].mood
        )
        assertTrue(resultScene.people.drop(1).all {
            it.mood == ObjectiveDeliverySceneMood.FRIENDLY
        })
        assertEquals(
            "Le client choisit une autre offre",
            resultScene.headline
        )
    }
}