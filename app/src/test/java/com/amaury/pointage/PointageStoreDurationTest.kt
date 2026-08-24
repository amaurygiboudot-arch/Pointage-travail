package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PointageStoreDurationTest {
    private val minute = 60_000L
    private val entry = 1_000_000L

    private fun session(
        durationMinutes: Int,
        pauses: List<Pair<Int, Int?>> = emptyList(),
        autoPauseMinutes: Int = 0
    ): JSONObject {
        val exit = entry + durationMinutes * minute
        val array = JSONArray()
        pauses.forEach { (startMinute, endMinute) ->
            val pause = JSONObject()
                .put("start", entry + startMinute * minute)
                .put("end", endMinute?.let { entry + it * minute } ?: JSONObject.NULL)
            array.put(pause)
        }
        return JSONObject()
            .put("entry", entry)
            .put("exit", exit)
            .put("pauses", array)
            .put("autoPauseMinutes", autoPauseMinutes)
    }

    @Test
    fun overlappingPausesAreMergedOnce() {
        val item = session(120, listOf(10 to 30, 20 to 40))
        assertEquals(30 * minute, PointageStore.pauseDuration(item))
        assertEquals(90 * minute, PointageStore.workedDuration(item))
    }

    @Test
    fun adjacentPausesDoNotCreateExtraTime() {
        val item = session(120, listOf(10 to 20, 20 to 30))
        assertEquals(20 * minute, PointageStore.pauseDuration(item))
    }

    @Test
    fun multipleSeparatedPausesAreAdded() {
        val item = session(180, listOf(10 to 20, 60 to 75, 120 to 135))
        assertEquals(40 * minute, PointageStore.pauseDuration(item))
        assertEquals(140 * minute, PointageStore.workedDuration(item))
    }

    @Test
    fun automaticPauseActsAsMinimumNotAdditionalDeduction() {
        val item = session(120, listOf(10 to 20), autoPauseMinutes = 30)
        assertEquals(30 * minute, PointageStore.pauseDuration(item))
        assertEquals(90 * minute, PointageStore.workedDuration(item))
    }

    @Test
    fun recordedPauseLongerThanAutomaticFloorWins() {
        val item = session(120, listOf(10 to 50), autoPauseMinutes = 30)
        assertEquals(40 * minute, PointageStore.pauseDuration(item))
    }

    @Test
    fun openEndedPauseRunsUntilSessionExit() {
        val item = session(120, listOf(60 to null))
        assertEquals(60 * minute, PointageStore.pauseDuration(item))
        assertEquals(60 * minute, PointageStore.workedDuration(item))
    }

    @Test
    fun pauseIntervalsAreClippedToSessionBounds() {
        val item = JSONObject()
            .put("entry", entry)
            .put("exit", entry + 60 * minute)
            .put("pauses", JSONArray().put(
                JSONObject()
                    .put("start", entry - 30 * minute)
                    .put("end", entry + 90 * minute)
            ))
            .put("autoPauseMinutes", 0)

        assertEquals(60 * minute, PointageStore.pauseDuration(item))
        assertEquals(0L, PointageStore.workedDuration(item))
    }
}
