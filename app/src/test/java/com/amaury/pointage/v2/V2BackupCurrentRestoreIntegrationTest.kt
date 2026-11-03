package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [RecoveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class V2BackupCurrentRestoreIntegrationTest {
    private lateinit var context: Context
    private val runtime = "horatrack_v2_test_runtime"
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        val auth = Mockito.mock(FirebaseAuth::class.java)
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("restore-test")
        Mockito.`when`(auth.currentUser).thenReturn(user)
        RecoveryAuthShadow.auth = auth
        listOf(runtime, "pointage", "horatrack_v2_legacy_recovery_claim",
            V2RuntimeAccountScope.fileName(runtime, "uid:restore-test")!!,
            V2RuntimeAccountScope.fileName("horatrack_v2_migration", "uid:restore-test")!!)
            .forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    private fun backup(): String {
        fun typed(t: String, v: Any) = JSONObject().put("t", t).put("v", v)
        val journal = JSONObject().put("history", typed("s", "[]"))
            .put("session_id", typed("s", "current"))
            .put("real_entry", typed("l", 10_000L)).put("counted_entry", typed("l", 10_000L))
            .put("expected_end", typed("l", 90_000L)).put("time_basis", typed("s", "REAL_FACTS"))
            .put("pauses", typed("s", "[]")).put("pause_start", typed("l", 20_000L))
            .put("pause_source", typed("s", "MANUAL")).put("pause_paid", typed("b", false))
            .put("legacy_recovery_receipt", typed("s", "remote-proof"))
        return JSONObject().put("formatVersion", 5).put("accountScope", "uid:restore-test")
            .put("preferences", JSONObject().put(runtime, journal)).toString()
    }
    @Test fun restoreWritesCurrentPauseAndInertProvenanceAndIsIdempotent() {
        val result = V2BackupManager.restoreFromJson(context, backup())
        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        val prefs = V2RuntimeAccountScope.preferences(context, runtime)
        assertEquals("current", prefs.getString("session_id", null))
        assertEquals(10_000L, prefs.getLong("real_entry", 0))
        assertEquals(20_000L, prefs.getLong("pause_start", 0))
        assertEquals(90_000L, prefs.getLong("expected_end", 0))
        assertEquals("MANUAL", prefs.getString("pause_source", null))
        assertFalse(prefs.getBoolean("pause_paid", true))
        assertFalse(prefs.contains("legacy_recovery_receipt"))
        assertEquals("remote-proof", JSONArray(prefs.getString("restored_legacy_provenance", null)).getString(0))
        val before = prefs.all
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        assertEquals(before, V2RuntimeAccountScope.preferences(context, runtime).all)
        V2RuntimeAccountScope.preferences(context, runtime).edit().putString("legacy_recovery_receipt", "local-proof").commit()
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        assertEquals("local-proof", V2RuntimeAccountScope.preferences(context, runtime).getString("legacy_recovery_receipt", null))
    }
    @Test fun restoreConflictingCurrentLeavesEveryLocalFieldUnchanged() {
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        val prefs = V2RuntimeAccountScope.preferences(context, runtime)
        prefs.edit().putString("session_id", "local-other").commit()
        val before = prefs.all
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isFailure)
        assertEquals(before, prefs.all)
    }

    private fun closed(id: String, start: Long, end: Long) = mapOf<String, Any>(
        "session_id" to id, "real_entry" to start, "counted_entry" to start,
        "real_exit" to end, "counted_exit" to end, "time_basis" to "REAL_FACTS",
        "expected_end" to end + 1_000L, "pauses" to "[]",
        "history" to """[{"id":"$id","realEntry":$start,"countedEntry":$start,"realExit":$end,"countedExit":$end,"employerId":null,"timeBasis":"REAL_FACTS","pauses":[]}]"""
    )
    private fun backup(values: Map<String, *>) = JSONObject().put("formatVersion", 5)
        .put("accountScope", "uid:restore-test")
        .put("preferences", JSONObject().put(runtime, V2PreferenceValues.encode(values))).toString()

    @Test fun restoreReconcilesClosedCachesThroughAtomicTransactionAndRemainsIdempotent() {
        val local = closed("local", 30_000L, 40_000L) + ("legacy_recovery_receipt" to "local-proof")
        val remote = closed("remote", 10_000L, 20_000L) + ("legacy_recovery_receipt" to "remote-proof")
        assertTrue(V2PreferenceValues.replace(V2RuntimeAccountScope.preferences(context, runtime), local))
        val result = V2BackupManager.restoreFromJson(context, backup(remote))
        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        val saved = V2RuntimeAccountScope.preferences(context, runtime).all
        assertEquals(local - "history", saved.filterKeys { it != "history" && it != "restored_legacy_provenance" })
        assertEquals(2, JSONArray(saved["history"] as String).length())
        assertEquals("remote-proof", JSONArray(saved["restored_legacy_provenance"] as String).getString(0))
        assertFalse(java.io.File(context.noBackupFilesDir, "agkgmg_restore_transaction_v1.json").exists())
        assertTrue(V2BackupManager.restoreFromJson(context, backup(remote)).isSuccess)
        assertEquals(saved, V2RuntimeAccountScope.preferences(context, runtime).all)
        assertEquals(2, V2RuntimeStore.allSessions(context).size)
    }

    @Test fun restoreReplacesOnlyProvenClosedCacheAndPreservesOpenPauseAndPlanning() {
        val local = closed("local", 1_000L, 2_000L)
        assertTrue(V2PreferenceValues.replace(V2RuntimeAccountScope.preferences(context, runtime), local))
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        val saved = V2RuntimeAccountScope.preferences(context, runtime).all
        assertEquals("current", saved["session_id"])
        assertEquals(90_000L, saved["expected_end"])
        assertEquals(20_000L, saved["pause_start"])
        assertEquals(false, saved["pause_paid"])
        assertEquals(local["history"], saved["history"])
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        assertEquals(saved, V2RuntimeAccountScope.preferences(context, runtime).all)
    }

    @Test fun restoreRejectsOverlappingClosedArchivesWithoutReplacingLocalCurrent() {
        val local = closed("local", 10_000L, 20_000L)
        assertTrue(V2PreferenceValues.replace(V2RuntimeAccountScope.preferences(context, runtime), local))
        assertTrue(V2BackupManager.restoreFromJson(context, backup(closed("remote", 15_000L, 25_000L))).isFailure)
        assertEquals(local, V2RuntimeAccountScope.preferences(context, runtime).all)
    }
}
