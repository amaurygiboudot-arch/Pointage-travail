package com.amaury.pointage.v2

import android.content.SharedPreferences
import com.amaury.pointage.IconSwitcher
import com.amaury.pointage.v2.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeObservationV2Test {
    private fun hour(h: Int) = h * 3_600_000L
    private fun values(pauses: String = "[]") = mapOf<String, Any?>(
        "session_id" to "current", "real_entry" to hour(8),
        "counted_entry" to hour(8), "pauses" to pauses, "history" to "[]"
    )
    private fun pause(start: Long, end: Long, source: String = "MANUAL") = JSONObject()
        .put("start", start).put("end", end).put("paid", false).put("source", source)
    private fun snapshot(p: MemoryPrefs, now: Long) = V2RuntimeStore.snapshotFromValues(p.getAll(), now)
    private fun replace(p: MemoryPrefs, drafts: List<QualifiedManualPauseV2>, now: Long = hour(10)): Boolean =
        V2RuntimeStore.replaceEditablePauses(p, JSONArray(p.getString("history", "[]")),
            snapshot(p, now), 1L, hour(24), drafts, now)

    @Test fun `entirely future batch preserves all old pauses and writes nothing`() {
        val old = JSONArray().put(pause(hour(9), hour(9) + 600_000)).toString()
        val p = MemoryPrefs(values(old))
        val before = p.getAll()
        assertFalse(replace(p, listOf(QualifiedManualPauseV2(hour(15), hour(16), false))))
        assertEquals(before, p.getAll())
        assertEquals(0, p.commits)
    }

    @Test fun `mixed batch never erases valid old pauses before all targets are validated`() {
        val p = MemoryPrefs(values(JSONArray().put(pause(hour(9), hour(9) + 600_000)).toString()))
        val before = p.getAll()
        assertFalse(replace(p, listOf(QualifiedManualPauseV2(hour(9), hour(10), false),
            QualifiedManualPauseV2(hour(15), hour(16), true))))
        assertEquals(before, p.getAll())
        assertEquals(0, p.commits)
    }

    @Test fun `bounded pause already started replaces day in one commit and drives paused display`() {
        val p = MemoryPrefs(values(JSONArray().put(pause(hour(8), hour(9))).toString()))
        assertTrue(replace(p, listOf(QualifiedManualPauseV2(hour(9), hour(11), true))))
        assertEquals(1, p.commits)
        val session = requireNotNull(snapshot(p, hour(10)).session)
        val state = RuntimeObservationV2.assess(session, hour(10))
        assertTrue(state.reliable)
        assertTrue(state.paused)
        assertTrue(state.hasActiveBoundedPause)
        assertEquals(IconSwitcher.IconState.PAUSED,
            IconSwitcher.resolveV2IconState(state.reliable, session.status, state.paused))
        val before = p.getAll()
        assertFalse(V2RuntimeStore.toggleObservedPause(p, session, hour(10), paid = false))
        assertEquals(before, p.getAll())
        assertEquals(1, p.commits)
        assertFalse(RuntimeObservationV2.assess(session, hour(11)).paused)
        assertTrue(V2RuntimeStore.toggleObservedPause(p, session, hour(11), paid = false))
        assertEquals(hour(11), p.getLong("pause_start", 0))
    }

    @Test fun `clock rollback blocks snapshot and pause mutation then recovers without rewriting events`() {
        val p = MemoryPrefs(values() + mapOf("pause_start" to hour(12),
            "pause_source" to "MANUAL", "pause_paid" to false))
        val valid = requireNotNull(snapshot(p, hour(13)).session)
        val before = p.getAll()
        assertNull(snapshot(p, hour(11)).session)
        assertFalse(V2RuntimeHistoryGuardV2.sourceState().reliable)
        assertFalse(V2RuntimeStore.toggleObservedPause(p, valid, hour(11), paid = false))
        assertEquals(before, p.getAll())
        assertEquals(0, p.commits)
        assertFalse(replace(p, emptyList(), hour(11)))
        assertEquals(before, p.getAll())
        // Each real read validates the history first, clearing transient observation warnings.
        V2RuntimeHistoryGuardV2.publishSourceState(true)
        assertNotNull(snapshot(p, hour(13)).session)
        assertTrue(V2RuntimeStore.toggleObservedPause(p, valid, hour(13)))
        assertEquals(1, p.commits)
        assertFalse(p.contains("pause_start"))
    }

    @Test fun `future arrival or recorded exit never presents a reliable current session`() {
        listOf(values() + ("real_entry" to hour(12)),
            values() + mapOf("real_exit" to hour(12), "counted_exit" to hour(12))).forEach { raw ->
            V2RuntimeHistoryGuardV2.publishSourceState(true)
            assertNull(V2RuntimeStore.snapshotFromValues(raw, hour(10)).session)
            assertFalse(V2RuntimeHistoryGuardV2.sourceState().reliable)
        }
    }

    @Test fun `replacement retains imported pauses and clear day is atomic`() {
        val p = MemoryPrefs(values(JSONArray().put(pause(hour(8), hour(9), "IMPORT"))
            .put(pause(hour(9), hour(10))).toString()))
        assertTrue(replace(p, emptyList()))
        assertEquals(1, p.commits)
        val retained = JSONArray(p.getString("pauses", "[]"))
        assertEquals(1, retained.length())
        assertEquals("IMPORT", retained.getJSONObject(0).getString("source"))
    }
    @Test fun `one day replacement updates historical and current sessions together`() {
        val closed = JSONObject().put("id", "morning").put("realEntry", hour(1))
            .put("realExit", hour(7)).put("countedEntry", hour(1)).put("countedExit", hour(7))
            .put("pauses", JSONArray().put(pause(hour(2), hour(3))))
        val p = MemoryPrefs(values(JSONArray().put(pause(hour(8), hour(9))).toString()) +
            ("history" to JSONArray().put(closed).toString()))
        assertTrue(replace(p, listOf(QualifiedManualPauseV2(hour(3), hour(4), true),
            QualifiedManualPauseV2(hour(9), hour(10), false))))
        assertEquals(1, p.commits)
        assertEquals(hour(3), JSONArray(p.getString("history", "[]"))
            .getJSONObject(0).getJSONArray("pauses").getJSONObject(0).getLong("start"))
        assertEquals(hour(9), JSONArray(p.getString("pauses", "[]")).getJSONObject(0).getLong("start"))
    }

    @Test fun `closed current copy stays identical to its archived pause replacement`() {
        val archived = JSONObject().put("id", "current").put("realEntry", hour(8))
            .put("realExit", hour(12)).put("countedEntry", hour(8)).put("countedExit", hour(12))
            .put("pauses", JSONArray())
        val p = MemoryPrefs(values() + mapOf("real_exit" to hour(12), "counted_exit" to hour(12),
            "history" to JSONArray().put(archived).toString()))
        assertTrue(replace(p, listOf(QualifiedManualPauseV2(hour(9), hour(10), true)), hour(13)))
        assertEquals(1, p.commits)
        assertEquals(JSONArray(p.getString("history", "[]")).getJSONObject(0)
            .getJSONArray("pauses").toString(), p.getString("pauses", null))
    }

    private class MemoryPrefs(initial: Map<String, Any?>) : SharedPreferences {
        val data = initial.toMutableMap()
        var commits = 0
        var failCommit = false
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun contains(key: String?) = data.containsKey(key)
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val next = data.toMutableMap()
            override fun putString(key: String?, value: String?) = apply { next[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { next[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { next[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { next[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { next[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { next[key!!] = value }
            override fun remove(key: String?) = apply { next.remove(key) }
            override fun clear() = apply { next.clear() }
            override fun commit(): Boolean { commits++; data.clear(); data.putAll(next); return !failCommit }
            override fun apply() { commit() }
        }
    }
}
