package com.amaury.pointage

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
import java.util.UUID

/** Real Android SharedPreferences exercised with Robolectric; no device GPS is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [TravelJournalAuthShadowV2::class],
    instrumentedPackages = ["com.google.firebase.auth"])
class TravelJourneyStoreV2IntegrationTest {
    private lateinit var context: Context
    private lateinit var user: FirebaseUser
    private val startMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val auth = Mockito.mock(FirebaseAuth::class.java)
        user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("travel-" + UUID.randomUUID())
        Mockito.`when`(auth.currentUser).thenReturn(user)
        TravelJournalAuthShadowV2.auth = auth
    }

    @Test
    fun startAndFinishPersistAndRejectDuplicateActions() {
        val id = requireNotNull(TravelJourneyStoreV2.begin(context, startMs, "employer-one"))
        assertEquals(1, TravelJourneyStoreV2.read(context)?.size)
        assertEquals(id, TravelJourneyStoreV2.read(context)?.single()?.id)
        assertNull(TravelJourneyStoreV2.begin(context, startMs + 1000, "employer-one"))
        assertEquals(id, TravelJourneyStoreV2.finish(context, startMs + 30_000))
        val closed = requireNotNull(TravelJourneyStoreV2.read(context)).single()
        assertEquals(startMs + 30_000, closed.endedAtMs)
        assertEquals("employer-one", closed.employerId)
        assertNull(TravelJourneyStoreV2.finish(context, startMs + 50_000))
    }

    @Test
    fun gpsIsOptionalAndLateOrInaccurateSamplesAreRejected() {
        val id = requireNotNull(TravelJourneyStoreV2.begin(context, startMs, null))
        val good = TravelFixV2(46.78, -1.75, 12.0, startMs + 3_000)
        assertTrue(TravelJourneyStoreV2.attachFix(context, id, true, good))
        assertFalse(TravelJourneyStoreV2.attachFix(context, id, true, good))
        assertFalse(TravelJourneyStoreV2.attachFix(context, id, false, good))
        assertEquals(id, TravelJourneyStoreV2.finish(context, startMs + 60_000))
        assertFalse(TravelJourneyStoreV2.attachFix(context, id, false,
            TravelFixV2(46.78, -1.75, 200.0, startMs + 60_000)))
        assertFalse(TravelJourneyStoreV2.attachFix(context, id, false,
            TravelFixV2(46.78, -1.75, 12.0, startMs + 250_000)))
        assertTrue(TravelJourneyStoreV2.attachFix(context, id, false,
            TravelFixV2(46.79, -1.74, 12.0, startMs + 60_500)))
        assertEquals(46.79, requireNotNull(TravelJourneyStoreV2.read(context)).single().arrival!!.latitude, 0.00001)
    }

    @Test
    fun switchingAccountsCannotReadOrFinishAnotherOwnersTrip() {
        val originalUid = requireNotNull(user.uid)
        val id = requireNotNull(TravelJourneyStoreV2.begin(context, startMs, "employer-one"))
        Mockito.`when`(user.uid).thenReturn("separate-" + UUID.randomUUID())
        assertTrue(requireNotNull(TravelJourneyStoreV2.read(context)).isEmpty())
        assertNull(TravelJourneyStoreV2.finish(context, startMs + 50_000))
        Mockito.`when`(user.uid).thenReturn(originalUid)
        assertEquals(id, TravelJourneyStoreV2.read(context)?.single()?.id)
        assertNull(TravelJourneyStoreV2.read(context)?.single()?.endedAtMs)
    }

    @Test
    fun corruptJournalIsNeverOverwrittenByBegin() {
        val owner = com.amaury.pointage.v2.V2RuntimeAccountScope.preferences(context, "travel_journeys_v2")
        assertTrue(owner.edit().putString("journal", "{malformed").commit())
        assertNull(TravelJourneyStoreV2.read(context))
        assertNull(TravelJourneyStoreV2.begin(context, startMs, null))
        assertEquals("{malformed", owner.getString("journal", null))
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class TravelJournalAuthShadowV2 {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
