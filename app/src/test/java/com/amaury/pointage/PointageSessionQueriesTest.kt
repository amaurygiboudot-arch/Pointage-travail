package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointageSessionQueriesTest {
    @Test
    fun fallsBackWhenLastEntryIsInFuture() {
        val now = 2_000L
        val valid = JSONObject().put("entry", 1_000L).put("exit", 1_500L)
        val future = JSONObject().put("entry", 3_000L).put("exit", JSONObject.NULL)
        val data = JSONArray().put(valid).put(future)

        val result = PointageSessionQueries.latestValidSession(data, now)

        assertEquals(1_000L, result?.optLong("entry"))
    }

    @Test
    fun fallsBackWhenLastExitIsInFuture() {
        val now = 5_000L
        val valid = JSONObject().put("entry", 1_000L).put("exit", 2_000L)
        val futureExit = JSONObject().put("entry", 4_000L).put("exit", 6_000L)
        val data = JSONArray().put(valid).put(futureExit)

        val result = PointageSessionQueries.latestValidSession(data, now)

        assertEquals(1_000L, result?.optLong("entry"))
    }

    @Test
    fun fallsBackWhenLastSessionHasNoExitKey() {
        val now = 5_000L
        val valid = JSONObject().put("entry", 1_000L).put("exit", 2_000L)
        val malformed = JSONObject().put("entry", 4_000L)
        val data = JSONArray().put(valid).put(malformed)

        val result = PointageSessionQueries.latestValidSession(data, now)

        assertEquals(1_000L, result?.optLong("entry"))
    }

    @Test
    fun skipsMalformedLastSession() {
        val now = 5_000L
        val valid = JSONObject().put("entry", 1_000L).put("exit", 2_000L)
        val malformed = JSONObject().put("entry", 4_000L).put("exit", 3_500L)
        val data = JSONArray().put(valid).put(malformed)

        val result = PointageSessionQueries.latestValidSession(data, now)

        assertEquals(1_000L, result?.optLong("entry"))
    }

    @Test
    fun acceptsLatestOpenSessionThatAlreadyStarted() {
        val now = 5_000L
        val open = JSONObject().put("entry", 4_000L).put("exit", JSONObject.NULL)
        val data = JSONArray().put(open)

        val result = PointageSessionQueries.latestValidSession(data, now)

        assertEquals(4_000L, result?.optLong("entry"))
    }

    @Test
    fun returnsNullWhenNoValidSessionExists() {
        val data = JSONArray()
            .put(JSONObject().put("entry", -1L).put("exit", JSONObject.NULL))
            .put(JSONObject().put("entry", 9_000L).put("exit", JSONObject.NULL))

        assertNull(PointageSessionQueries.latestValidSession(data, 5_000L))
    }
}
