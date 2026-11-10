package com.amaury.pointage

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Looper
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class GpsEntryRecoveryCorruptionV2Test {
    private lateinit var context: Context
    private lateinit var gps: SharedPreferences
    private val corrupt = "[{\"version\":1,\"zone\":\"work\",BROKEN"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        val auth = Mockito.mock(FirebaseAuth::class.java)
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("entry-corruption-account")
        Mockito.`when`(auth.currentUser).thenReturn(user)
        GpsDeliveryAuthShadow.auth = auth
        context.getSharedPreferences("salary_companies_v2", Context.MODE_PRIVATE).edit()
            .putString("companies", """[{"id":"company-a","name":"A","siret":""}]""").commit()
        assertTrue(V2ProfileStore.setActiveCompanyId(context, "company-a"))
        V2RuntimeStore.reset(context)
        assertTrue(V2RuntimeReader.current(context).reliable)
        gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        gps.edit().clear().putBoolean("enabled", true).putString("zones", """
            [{"id":"work","latitude":46.7,"longitude":-1.4,"radius":150,
              "address":"Site A","companyId":"company-a","pointType":"POSTE"}]
        """).commit()
        gps.edit().putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, true)
            .putString(GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT,
                storedGpsAutomaticFingerprintV2(true, readPersistedGpsZones(gps))).commit()
    }

    private fun callback(transition: Int, block: (Intent) -> Unit = {
        GeofenceBroadcastReceiver().onReceive(context, it)
    }) {
        val event = Mockito.mock(GeofencingEvent::class.java)
        val fence = Mockito.mock(Geofence::class.java)
        Mockito.`when`(fence.requestId).thenReturn("work")
        Mockito.`when`(event.geofenceTransition).thenReturn(transition)
        Mockito.`when`(event.triggeringGeofences).thenReturn(listOf(fence))
        Mockito.mockStatic(GeofencingEvent::class.java).use { events ->
            events.`when`<GeofencingEvent> { GeofencingEvent.fromIntent(Mockito.any(Intent::class.java)) }
                .thenReturn(event)
            block(Intent("com.amaury.pointage.TEST_CORRUPT_ENTRY"))
        }
    }

    @Test fun absenceAndKnownEmptyRemainDistinctFromCorruption() {
        assertEquals(emptyMap<String, GpsEntryRecoveryV2.Observation>(), GpsEntryRecoveryV2.read(gps))
        assertEquals(emptyMap<String, GpsEntryRecoveryV2.Observation>(), GpsEntryRecoveryV2.decode("[]"))
        assertNull(GpsEntryRecoveryV2.decode(corrupt))
        gps.edit().putInt(GpsEntryRecoveryV2.KEY, 7).commit()
        assertNull(GpsEntryRecoveryV2.read(gps))
        assertNull(GpsEntryRecoveryV2.observe(context, gps, listOf("work"), emptySet(), System.currentTimeMillis()))
        assertEquals(7, gps.getInt(GpsEntryRecoveryV2.KEY, 0))
    }

    @Test fun realEnterPreservesCorruptSourceInsteadOfReplacingItWithNewArrival() {
        gps.edit().putString(GpsEntryRecoveryV2.KEY, corrupt).commit()
        callback(Geofence.GEOFENCE_TRANSITION_ENTER)
        assertEquals(corrupt, gps.getString(GpsEntryRecoveryV2.KEY, null))
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertFalse(gps.getBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, false))
        assertFalse(gps.contains(GpsPresenceStateKeysV2.ACTIVE_ZONES))
        assertTrue(GeofenceManager.isStoredRegistrationCurrent(context))
    }

    @Test fun replayPreservesCorruptSourceAndPendingReceipt() {
        gps.edit().putString(GpsEntryRecoveryV2.KEY, corrupt)
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("work"))
            .putBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, true)
            .putString(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_TOKEN, "unacknowledged").commit()
        GeofenceBroadcastReceiver.replayPendingEntry(context)
        assertEquals(corrupt, gps.getString(GpsEntryRecoveryV2.KEY, null))
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertTrue(gps.getBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, false))
        assertEquals("unacknowledged", gps.getString(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_TOKEN, null))
    }

    @Test fun realExitCannotClearCorruptionOrCloseAnUnacknowledgedEntry() {
        assertTrue(V2RuntimeStore.entry(context, System.currentTimeMillis() - 60_000L))
        gps.edit().putString(GpsEntryRecoveryV2.KEY, corrupt)
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("work"))
            .putBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, true).commit()
        callback(Geofence.GEOFENCE_TRANSITION_EXIT)
        assertEquals(corrupt, gps.getString(GpsEntryRecoveryV2.KEY, null))
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
        assertTrue(gps.getBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, false))
        assertEquals(setOf("work"), gps.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, null))
    }

    @Test fun corruptionAfterEnterBeforeDeferredResolutionCannotBePurgedOrAcknowledged() {
        val receiver = GeofenceBroadcastReceiver()
        context.registerReceiver(receiver, IntentFilter("com.amaury.pointage.TEST_CORRUPT_ENTRY"),
            Context.RECEIVER_NOT_EXPORTED)
        try {
            callback(Geofence.GEOFENCE_TRANSITION_ENTER) { intent ->
                context.sendBroadcast(intent)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertNotNull(GpsEntryRecoveryV2.read(gps)?.get("work"))
            gps.edit().putString(GpsEntryRecoveryV2.KEY, corrupt).commit()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(corrupt, gps.getString(GpsEntryRecoveryV2.KEY, null))
            assertTrue(gps.getBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, false))
            assertNull(V2RuntimeReader.current(context).snapshot.session)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
