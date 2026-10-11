package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import com.amaury.pointage.GpsDeliveryAuthShadow
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class V2RuntimeAccountIntegrationTest {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account("A")
    }
    private fun account(uid: String?) {
        val user = uid?.let { Mockito.mock(FirebaseUser::class.java).also { user -> Mockito.`when`(user.uid).thenReturn(uid) } }
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }
    @Test fun accountSwitchAndGuestNeverExposeOrCloseOtherSession() {
        val arrival = System.currentTimeMillis() - 60_000
        assertTrue(V2RuntimeStore.entry(context, arrival))
        val a = V2RuntimeReader.current(context).snapshot.session
        assertNotNull(a)
        account("B")
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertFalse(V2RuntimeStore.exit(context))
        assertTrue(V2RuntimeStore.entry(context, arrival))
        val b = V2RuntimeReader.current(context).snapshot.session
        assertNotEquals(a?.id, b?.id)
        account(null)
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        account("A")
        assertEquals(a?.id, V2RuntimeReader.current(context).snapshot.session?.id)
    }
    @Test fun anotherWorkerCannotTurnIncompleteHistoryIntoReliableZero() {
        context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
            .edit().putString("history", "unassigned").commit()
        assertFalse(V2RuntimeReader.allSessions(context).reliable)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> { V2RuntimeReader.current(context).reliable }.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(V2RuntimeHistoryGuardV2.sourceState().reliable)
            assertFalse(V2RuntimeReader.allSessions(context).reliable)
        } finally { executor.shutdownNow() }
    }

    @Test fun legacyJournalIsPreservedAndBlocksTotalsButNotNewScopedFacts() {
        val global = context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        global.edit().putString("history", "unassigned historical bytes").commit()
        assertTrue(V2RuntimeStore.entry(context, System.currentTimeMillis() - 60_000))
        assertTrue(V2RuntimeReader.current(context).reliable)
        assertNotNull(V2RuntimeReader.current(context).snapshot.session)
        assertFalse(V2RuntimeReader.allSessions(context).reliable)
        assertEquals("unassigned historical bytes", global.getString("history", null))
    }
}
