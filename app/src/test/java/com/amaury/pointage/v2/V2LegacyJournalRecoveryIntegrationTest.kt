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
