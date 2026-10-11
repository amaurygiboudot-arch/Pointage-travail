package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
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
    private fun account(id: String?) {
        val user = id?.let { Mockito.mock(FirebaseUser::class.java).also { u -> Mockito.`when`(u.uid).thenReturn(it) } }
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
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
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class RecoveryAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
