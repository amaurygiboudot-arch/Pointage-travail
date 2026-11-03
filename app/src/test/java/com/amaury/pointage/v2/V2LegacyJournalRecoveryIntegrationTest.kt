package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [RecoveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class V2LegacyJournalRecoveryIntegrationTest {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private val files = mutableMapOf<String, FaultPreferences>()
    private fun file(name: String) = files.getOrPut(name) { FaultPreferences() }
    private fun account(id: String?) {
        val user = id?.let { Mockito.mock(FirebaseUser::class.java).also { u -> Mockito.`when`(u.uid).thenReturn(it) } }
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }
    @Before fun setup() {
        files.clear()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = file(name)
        }
        auth = Mockito.mock(FirebaseAuth::class.java)
        RecoveryAuthShadow.auth = auth
        account("a")
        listOf("horatrack_v2_test_runtime", "pointage", "horatrack_v2_legacy_recovery_claim",
            V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!,
            V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:b")!!).forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        context.getSharedPreferences("horatrack_v2_test_runtime", 0).edit()
            .putString("history", """[{"id":"old","realEntry":1000,"realExit":2000,"countedEntry":1000,"countedExit":2000,"employerId":null,"pauses":[]}]""").commit()
    }
    @Test fun recoveryPreservesOriginalAndDoesNotLeakIntoAnotherAccount() {
        val original = context.getSharedPreferences("horatrack_v2_test_runtime", 0).all
        val preview = V2LegacyJournalRecovery.preview(context)!!
        assertTrue(preview.eligible)
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, false).success)
        assertTrue(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        assertEquals(original, context.getSharedPreferences("horatrack_v2_test_runtime", 0).all)
        assertFalse(V2RuntimeAccountScope.hasUnassignedHistory(context))
        account("b")
        assertFalse(V2RuntimeAccountScope.hasUnassignedHistory(context))
        assertTrue(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").all.isEmpty())
        assertNull(V2LegacyJournalRecovery.preview(context))
    }
    private fun recoverAfterFailure(target: String, failAt: Int, restart: Boolean) {
        val preview = V2LegacyJournalRecovery.preview(context)!!
        val faulty = file(target)
        faulty.failOnCommit = faulty.commits + failAt
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        assertTrue(V2RuntimeAccountScope.hasUnassignedHistory(context))
        assertFalse(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
        account("b")
        assertFalse(V2LegacyJournalRecovery.preview(context)!!.eligible)
        assertTrue(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
        account("a")
        if (restart) files.values.forEach { it.restart() }
        val retry = V2LegacyJournalRecovery.preview(context)!!
        assertTrue(retry.message, retry.eligible)
        assertTrue(V2LegacyJournalRecovery.confirm(context, retry, true).success)
        files.values.forEach { it.restart() }
        assertFalse(V2RuntimeAccountScope.hasUnassignedHistory(context))
        val history = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").getString("history", null)!!
        assertEquals(1, org.json.JSONArray(history).length())
        assertEquals("old", org.json.JSONArray(history).getJSONObject(0).getString("id"))
    }

    @Test fun pendingReservationCommitFalseRetriesWithoutLosingOriginal() =
        recoverAfterFailure("horatrack_v2_legacy_recovery_claim", 1, false)

    @Test fun destinationCommitFalseRetriesCachedReceipt() = recoverAfterFailure(
        V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!, 1, false)

    @Test fun destinationCommitFalseThenCrashRestoresPendingAndRetries() = recoverAfterFailure(
        V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!, 1, true)

    @Test fun completeCommitFalseFreezesWritesAndRetries() =
        recoverAfterFailure("horatrack_v2_legacy_recovery_claim", 2, false)

    @Test fun crashBetweenDestinationAndCompletionRetriesWithoutDuplicate() =
        recoverAfterFailure("horatrack_v2_legacy_recovery_claim", 2, true)

    @Test fun pendingReservationFreezesOnlyItsOwnersWrites() {
        context.getSharedPreferences("horatrack_v2_legacy_recovery_claim", 0).edit()
            .putString("owner", "uid:a").putString("state", "PENDING").commit()
        assertFalse(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
        account("b")
        assertTrue(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
    }

    @Test fun changedAccountDestinationAndGuestCannotUseOldConfirmation() {
        val preview = V2LegacyJournalRecovery.preview(context)!!
        account("b")
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        account(null)
        assertFalse(V2LegacyJournalRecovery.preview(context)!!.eligible)
        account("a")
        V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").edit().putString("history", "[]").commit()
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, true).success)
    }

    @Test fun diagnosticExplainsRefusedConversionOnlyAfterDeclarationWithoutAnyWrite() {
        file("pointage").edit().putString("data", """[{"arrivalTime":3000,"exitTime":6000,"pauses":[{"start":4000,"end":5000}]}]""").commit()
        assertFalse(V2LegacyJournalRecovery.preview(context)!!.eligible)
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = files.values.sumOf { it.commits }
        val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        assertFalse(V2LegacyJournalRecovery.diagnose(context, session, false).contains("PAUSE_UNQUALIFIED"))
        repeat(2) {
            val report = V2LegacyJournalRecovery.diagnose(context, session, true)
            assertTrue(report, report.contains("PAUSE_UNQUALIFIED"))
            assertTrue(report.contains("Ancien journal V1 · pointage n° 1 · pause n° 1"))
            assertTrue(report.contains("Champ : paid"))
        }
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, files.values.sumOf { it.commits })
        files.values.forEach { it.restart() }
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
    }

    @Test fun diagnosticLeaseCannotReviveAfterAccountRoundTripOrSessionInvalidation() {
        val first = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        account("b"); SalaryAccountScope.capture()
        account("a"); SalaryAccountScope.capture()
        assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, first))
        assertTrue(V2LegacyJournalRecovery.diagnose(context, first, true).contains("indisponible"))
        val second = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        SalaryAccountScope.invalidateSession()
        assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, second))
        assertTrue(file("horatrack_v2_legacy_recovery_claim").all.isEmpty())
    }

    @Test fun changedDiagnosticSnapshotAndForeignReservationCannotExposeDetails() {
        val first = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        file("pointage").edit().putString("data", "not-json-sensitive@example.test").commit()
        assertTrue(V2LegacyJournalRecovery.diagnose(context, first, true).contains("indisponible"))
        val second = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!).edit().putString("history", "[]").commit()
        assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, second))
        file("horatrack_v2_legacy_recovery_claim").edit().putString("owner", "uid:b").commit()
        assertNull(V2LegacyJournalRecovery.beginDiagnostic(context))
        account(null)
        assertNull(V2LegacyJournalRecovery.beginDiagnostic(context))
    }

    @Test fun diagnosticPreservesOriginalProvenanceAcrossLegacyMergeAndDestinationConflict() {
        file("pointage").edit().putString("data", """[{"id":"v1","arrivalTime":3000,"exitTime":5000,"pauses":[]}]""").commit()
        file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!).edit()
            .putString("history", """[{"id":"account","realEntry":4000,"realExit":6000,"pauses":[]}]""").commit()
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = files.values.sumOf { it.commits }
        val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        val report = V2LegacyJournalRecovery.diagnose(context, session, true)
        assertTrue(report, report.contains("OVERLAP"))
        assertTrue(report, report.contains("Ancien journal V1 · pointage n° 1"))
        assertTrue(report, report.contains("Journal du compte · pointage n° 1"))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, files.values.sumOf { it.commits })
    }

    @Test fun diagnosticIdentityConflictDoesNotPublishIdentifiersValuesOrUnknownFields() {
        file("pointage").edit().putString("data", """[{"id":"old","arrivalTime":1000,"exitTime":2100,"pauses":[],"placeLabel":"sensitive@example.test 48.123,2.456"}]""").commit()
        val report = V2LegacyJournalRecovery.diagnose(context, V2LegacyJournalRecovery.beginDiagnostic(context)!!, true)
        assertTrue(report, report.contains("IDENTITY_CONFLICT"))
        assertTrue(report.contains("Ancien journal V1 · pointage n° 1"))
        assertTrue(report.contains("Ancien journal V2 · pointage n° 1"))
        assertFalse(report.contains("old")); assertFalse(report.contains("sensitive@")); assertFalse(report.contains("48.123"))
        file("pointage").edit().putString("data", """[{"arrivalTime":1000,"secret@example.test":"48.123,2.456"}]""").commit()
        val unknown = V2LegacyJournalRecovery.diagnose(context, V2LegacyJournalRecovery.beginDiagnostic(context)!!, true)
        assertTrue(unknown.contains("UNSUPPORTED_FIELD"))
        assertFalse(unknown.contains("secret@")); assertFalse(unknown.contains("48.123"))
    }

    @Test fun diagnosticDoesNotRecoverOrDeletePreparedCommittedOrAtomicBackupJournals() {
        val physical = V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!
        val after = mapOf("history" to "[]")
        val beforeRestore = mapOf("history" to """[{"id":"before","realEntry":4000,"realExit":5000,"pauses":[]}]""")
        file(physical).edit().putString("history", "[]").commit()
        for (state in listOf("PREPARED", "COMMITTED")) for (suffix in listOf("", ".bak", ".new")) {
            val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
            val journal = File(context.noBackupFilesDir, "agkgmg_restore_transaction_v1.json$suffix")
            val record = JSONObject().put("version", 1).put("scope", "uid:a").put("state", state)
                .put("entries", JSONArray().put(JSONObject().put("logical", "horatrack_v2_test_runtime")
                    .put("physical", physical).put("before", V2PreferenceValues.encode(beforeRestore))
                    .put("after", V2PreferenceValues.encode(after))))
            val bytes = record.toString().toByteArray(Charsets.UTF_8)
            journal.writeBytes(bytes)
            try {
                val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
                val commits = files.values.sumOf { it.commits }
                assertNull("$state$suffix", V2LegacyJournalRecovery.beginDiagnostic(context))
                assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, session))
                assertTrue(V2LegacyJournalRecovery.diagnose(context, session, true).contains("indisponible"))
                assertArrayEquals(bytes, journal.readBytes())
                assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
                assertEquals(commits, files.values.sumOf { it.commits })
            } finally { journal.delete() }
        }
    }

    @Test fun eligibleDiagnosticDoesNotAssignOrReserveTheJournal() {
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = files.values.sumOf { it.commits }
        val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        assertTrue(V2LegacyJournalRecovery.diagnose(context, session, true).contains("Aucun conflit détecté"))
        assertTrue(V2RuntimeAccountScope.hasUnassignedHistory(context))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, files.values.sumOf { it.commits })
    }
    private class FaultPreferences : SharedPreferences {
        private var memory = mutableMapOf<String, Any>()
        private var disk = mapOf<String, Any>()
        var failOnCommit = -1
        var commits = 0
        fun restart() { memory = disk.toMutableMap() }
        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
        override fun contains(key: String): Boolean = memory.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = memory[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            return (memory[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String, defValue: Int) = memory[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = memory[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = memory[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = memory[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            var clear = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) memory.clear()
                changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                commits++
                if (commits == failOnCommit) return false
                disk = memory.toMap()
                return true
            }
            override fun apply() { commit() }
        }
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class RecoveryAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
