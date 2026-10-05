package com.amaury.pointage.v2

import android.content.SharedPreferences
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2MigrationManagerTest {
    @Test
    fun `legacy migration is required before current version`() {
        assertTrue(V2MigrationManager.migrationRequired(0))
        assertTrue(V2MigrationManager.migrationRequired(4))
    }

    @Test
    fun `legacy migration is not replayed once v5 is already complete`() {
        assertFalse(V2MigrationManager.migrationRequired(5))
        assertFalse(V2MigrationManager.migrationRequired(6))
    }
    @Test fun `failed marker commit restores absent version and allows retry`() {
        val prefs = MemoryPrefs(mapOf("unrelated" to "kept"))
        prefs.failCommit = true
        assertFalse(V2MigrationManager.persistCompletion(prefs, 1, 2, 100L))
        assertFalse(prefs.contains("version"))
        assertTrue(V2MigrationManager.migrationRequired(prefs.getInt("version", 0)))
        assertEquals(mapOf("unrelated" to "kept"), prefs.data)
        prefs.failCommit = false
        assertTrue(V2MigrationManager.persistCompletion(prefs, 1, 2, 101L))
        assertFalse(V2MigrationManager.migrationRequired(prefs.getInt("version", 0)))
        assertEquals(2, prefs.getInt("v2_count", 0))
    }

    @Test fun `failed marker preserves exact previous values despite memory mutation`() {
        val original = mapOf<String, Any?>("version" to 4, "legacy_count" to 7, "v2_count" to 9,
            "checked_at" to 42L, "unrelated" to "kept")
        val prefs = MemoryPrefs(original)
        prefs.failCommit = true
        assertFalse(V2MigrationManager.persistCompletion(prefs, 10, 12, 200L))
        assertEquals(original, prefs.data)
        assertEquals(2, prefs.commits)
        assertTrue(V2MigrationManager.migrationRequired(prefs.getInt("version", 0)))
    }

    @Test fun `malformed marker is rejected before any write`() {
        val prefs = MemoryPrefs(mapOf("version" to "invalid"))
        assertFalse(V2MigrationManager.persistCompletion(prefs, 1, 2, 100L))
        assertEquals(0, prefs.commits)
        assertEquals("invalid", prefs.data["version"])
    }

    @Test fun `retry recognizes legacy session after known counted entry repair`() {
        val realEntry = (6 * 60 + 8) * 60_000L
        val oldCountedEntry = (6 * 60 + 15) * 60_000L
        val repairedEntry = 6 * 60 * 60_000L
        val realExit = 13 * 60 * 60_000L
        assertEquals(
            V2MigrationManager.migrationSignature(realEntry, realExit, oldCountedEntry, realExit),
            V2MigrationManager.migrationSignature(realEntry, realExit, repairedEntry, realExit)
        )
        assertNotEquals(
            V2MigrationManager.migrationSignature(realEntry, realExit, repairedEntry, realExit),
            V2MigrationManager.migrationSignature(realEntry, realExit, (6 * 60 + 7) * 60_000L, realExit)
        )
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
