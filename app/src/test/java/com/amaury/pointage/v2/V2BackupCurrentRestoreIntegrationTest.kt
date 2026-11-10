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
        assertEquals(before, prefs.all)
        prefs.edit().putString("legacy_recovery_receipt", "local-proof").commit()
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        assertEquals("local-proof", prefs.getString("legacy_recovery_receipt", null))
    }
    @Test fun restoreConflictingCurrentLeavesEveryLocalFieldUnchanged() {
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        val prefs = V2RuntimeAccountScope.preferences(context, runtime)
        prefs.edit().putString("session_id", "local-other").commit()
        val before = prefs.all
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isFailure)
        assertEquals(before, prefs.all)
    }
}
