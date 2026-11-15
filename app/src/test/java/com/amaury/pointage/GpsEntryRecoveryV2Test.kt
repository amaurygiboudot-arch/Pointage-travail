package com.amaury.pointage

import com.amaury.pointage.v2.SalaryAccountScope
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Looper
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.GpsTransitionV2
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real receiver replay and canonical runtime; a disk/cache fault models Android commit(false). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class GpsEntryRecoveryV2Test {
    private lateinit var context: Context
    private lateinit var gps: SharedPreferences
    private lateinit var runtime: FaultPreferences
    private lateinit var auth: FirebaseAuth
    private var observedAt = 0L
    private val zones = """[{"id":"work","latitude":46.7,"longitude":-1.4,"radius":150,"address":"Site A","companyId":"company-a","pointType":"POSTE"}]"""

    @Before fun setup() {
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account("account-a")
        runtime = FaultPreferences()
        val runtimeName = V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:account-a")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == runtimeName) runtime else super.getSharedPreferences(name, mode)
        }
        SalaryAccountScope.preferences(context, "salary_companies_v2").edit()
            .putString("companies", """[{"id":"company-a","name":"A","siret":""},{"id":"company-b","name":"B","siret":""}]""")
            .commit()
        assertTrue(V2ProfileStore.setActiveCompanyId(context, "company-a"))
        V2RuntimeStore.reset(context)
        assertTrue(V2RuntimeReader.current(context).reliable)
        gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        gps.edit().clear().putBoolean("enabled", true).putString("zones", zones).commit()
        register()
        observedAt = System.currentTimeMillis() - 10_000L
    }

    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        if (::context.isInitialized) V2RuntimeAccountScope.monitoringEpoch(context)
    }

    private fun register() {
        gps.edit().putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, true)
            .putString(GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT,
                storedGpsAutomaticFingerprintV2(true, readPersistedGpsZones(gps))).commit()
    }

    private fun observe() {
        val observations = checkNotNull(GpsEntryRecoveryV2.observe(context, gps, listOf("work"), emptySet(), observedAt))
        assertEquals(observedAt, observations["work"]?.atMs)
        assertTrue(gps.edit().putString(GpsEntryRecoveryV2.KEY, GpsEntryRecoveryV2.encode(observations))
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("work"))
            .putBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, true).commit())
    }

    private fun replay() = GeofenceBroadcastReceiver.replayPendingEntry(context)
    private fun pending() = gps.getBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, false)

    @Test fun interruptedTimerReplaysObservedArrivalOnceAtOriginalCallbackTime() {
        observe()
        replay()
        val session = V2RuntimeReader.current(context).snapshot.session
        assertNotNull(session)
        assertEquals(observedAt, session?.realArrivalMs)
        assertEquals("company-a", session?.employerId)
        assertFalse(pending())
        replay()
        assertEquals(session?.id, V2RuntimeReader.current(context).snapshot.session?.id)
        assertTrue(GeofenceManager.isStoredRegistrationCurrent(context))
    }

    @Test fun appResumeWithoutAnObservationNeverSynthesizesArrival() {
        gps.edit().putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("work"))
            .putBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, true).commit()
        replay()
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertTrue(pending())
    }

    @Test fun failedRuntimeWriteRemainsPendingAndCanRetryAfterProcessRestart() {
        observe()
        runtime.rejectCommits = true
        replay()
        assertTrue(pending())
        assertFalse(V2RuntimeReader.current(context).reliable)
        runtime.restart()
        runtime.rejectCommits = false
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        replay()
        val session = checkNotNull(V2RuntimeReader.current(context).snapshot.session)
        assertEquals(observedAt, session.realArrivalMs)
        assertFalse(pending())
        replay()
        assertEquals(session.id, V2RuntimeReader.current(context).snapshot.session?.id)
        assertEquals(1, V2RuntimeStore.allSessions(context).size)
    }

    @Test fun arrivalDuringUnknownRuntimeKeepsFactWithoutInventingItsSessionLater() {
        runtime.edit().putLong("real_entry", -1L).commit()
        assertFalse(V2RuntimeReader.current(context).reliable)
        observe()
        assertEquals(observedAt, checkNotNull(GpsEntryRecoveryV2.read(gps))["work"]?.atMs)
        runtime.edit().remove("real_entry").commit()
        assertTrue(V2RuntimeReader.current(context).reliable)
        replay()
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertTrue(pending())
    }

    @Test fun failedWriteInMemoryIsReconfirmedOnDiskBeforeAcknowledgement() {
        observe()
        runtime.failEntryWrites = 1
        replay()
        assertTrue(pending())
        // The readback may successfully reflush the failed entry while the failed
        // operation's GPS receipt remains pending. Verify disk before acknowledging.
        runtime.restart()
        val persisted = checkNotNull(V2RuntimeReader.current(context).snapshot.session)
        assertEquals(observedAt, persisted.realArrivalMs)
        replay()
        assertFalse(pending())
        runtime.restart()
        assertEquals(persisted, V2RuntimeReader.current(context).snapshot.session)
        assertEquals(listOf(persisted), V2RuntimeStore.allSessions(context))
    }

    @Test fun nextRealExitRecoversArrivalBeforeResolvingDeparture() {
        observe()
        runtime.failEntryWrites = 1
        replay()
        runtime.restart()
        val event = Mockito.mock(GeofencingEvent::class.java)
        val fence = Mockito.mock(Geofence::class.java)
        Mockito.`when`(fence.requestId).thenReturn("work")
        Mockito.`when`(event.geofenceTransition).thenReturn(Geofence.GEOFENCE_TRANSITION_EXIT)
        Mockito.`when`(event.triggeringGeofences).thenReturn(listOf(fence))
        val beforeExit = System.currentTimeMillis()
        Mockito.mockStatic(GeofencingEvent::class.java).use { events ->
            events.`when`<GeofencingEvent> { GeofencingEvent.fromIntent(Mockito.any(Intent::class.java)) }
                .thenReturn(event)
            GeofenceBroadcastReceiver().onReceive(context, Intent("test.exit"))
        }
        val session = V2RuntimeReader.current(context).snapshot.session
        assertEquals(observedAt, session?.realArrivalMs)
        assertNotNull(session?.realExitMs)
        assertTrue(checkNotNull(session?.realExitMs) >= beforeExit)
        assertFalse(pending())
        assertTrue(gps.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, emptySet()).orEmpty().isEmpty())
    }

    @Test fun changedAccountCannotConsumeOldArrivalEvenWhenReturningToOriginalAccount() {
        observe()
        account("account-b")
        replay()
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        account("account-a")
        replay()
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertTrue(pending())
    }

    @Test fun changedConfigurationCannotAdoptAnOlderObservedArrival() {
        observe()
        gps.edit().putString("zones", zones.replace("150", "180")).commit()
        register()
        replay()
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertTrue(pending())
    }

    @Test fun newSessionAfterObservationIsNeverReplacedOrAcknowledged() {
        observe()
        assertTrue(V2RuntimeStore.entry(context, observedAt + 1_000L))
        val session = V2RuntimeReader.current(context).snapshot.session
        replay()
        assertEquals(session?.id, V2RuntimeReader.current(context).snapshot.session?.id)
        assertTrue(pending())
    }

    @Test fun duplicateEnterPreservesOriginalObservationAndBatchDoesNotResolveEarly() {
        observedAt = System.currentTimeMillis()
        observe()
        val duplicate = checkNotNull(GpsEntryRecoveryV2.observe(context, gps, listOf("work"), setOf("work"), observedAt + 1_000L))
        assertEquals(observedAt, duplicate["work"]?.atMs)
        replay()
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertTrue(pending())
    }

    @Test fun legacySlotResolvesExistingEmployerAndAllowsTheNextExit() {
        gps.edit().putString("zones", zones.replace("\"companyId\":\"company-a\"", "\"companySlot\":1")).commit()
        register()
        observe()
        replay()
        assertFalse(pending())
        assertEquals("company-a", V2RuntimeReader.current(context).snapshot.session?.employerId)
        val beforeExit = System.currentTimeMillis()
        deliverExit()
        val closed = checkNotNull(V2RuntimeReader.current(context).snapshot.session)
        assertEquals(observedAt, closed.realArrivalMs)
        assertTrue(checkNotNull(closed.realExitMs) >= beforeExit)
        assertTrue(V2RuntimeStore.hasOnlyUnqualifiedPresenceSegment(closed))
        assertFalse(pending())
    }

    @Test fun legacyAddressBindingUsesSameConfirmedEmployerForArrivalAndDeparture() {
        gps.edit().putString("zones", zones.replace(",\"companyId\":\"company-a\"", ""))
            .putString("address_company_slots", """{"SITE A":1}""").commit()
        register()
        observe()
        replay()
        assertFalse(pending())
        deliverExit()
        assertNotNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun legacySlotWithMissingOrChangedCompanyDoesNotCloseTheOriginalSession() {
        for (companies in listOf("[]",
            """[{"id":"company-b","name":"B","siret":""},{"id":"company-a","name":"A","siret":""}]""")) {
            setup()
            gps.edit().putString("zones", zones.replace("\"companyId\":\"company-a\"", "\"companySlot\":1")).commit()
            register()
            observe()
            replay()
            val original = checkNotNull(V2RuntimeReader.current(context).snapshot.session)
            SalaryAccountScope.preferences(context, "salary_companies_v2").edit()
                .clear().putString("companies", companies).commit()
            deliverExit()
            assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
            assertEquals(original.id, V2RuntimeReader.current(context).snapshot.session?.id)
            assertNotNull(com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2.pending(context))
        }
    }

    @Test fun conflictingLegacyAddressBindingsNeverFallBackToSelectedEmployer() {
        gps.edit().putString("zones", zones.replace(",\"companyId\":\"company-a\"", ""))
            .putString("address_company_slots", """{"Site A":1,"SITE A":2}""").commit()
        register()
        observe()
        replay()
        assertTrue(pending())
        assertNull(V2RuntimeReader.current(context).snapshot.session)
    }

    @Test fun unboundZoneCanCloseOnlyTheGpsSessionBoundToThatPlaceAndConfirmedEmployer() {
        gps.edit().putString("zones", zones.replace(",\"companyId\":\"company-a\"", "")).commit()
        register()
        observe()
        replay()
        val opened = checkNotNull(V2RuntimeReader.current(context).snapshot.session)
        assertEquals("work", opened.placeId)
        deliverExit()
        assertEquals(opened.id, V2RuntimeReader.current(context).snapshot.session?.id)
        assertNotNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun unboundZoneCannotCloseGpsSessionAfterActiveEmployerChanged() {
        gps.edit().putString("zones", zones.replace(",\"companyId\":\"company-a\"", "")).commit()
        register()
        observe()
        replay()
        val opened = checkNotNull(V2RuntimeReader.current(context).snapshot.session)
        assertTrue(V2ProfileStore.setActiveCompanyId(context, "company-b"))
        deliverExit()
        assertEquals(opened.id, V2RuntimeReader.current(context).snapshot.session?.id)
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
        assertNotNull(com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2.pending(context))
    }

    private fun deliverExit() {
        val event = Mockito.mock(GeofencingEvent::class.java)
        val fence = Mockito.mock(Geofence::class.java)
        Mockito.`when`(fence.requestId).thenReturn("work")
        Mockito.`when`(event.geofenceTransition).thenReturn(Geofence.GEOFENCE_TRANSITION_EXIT)
        Mockito.`when`(event.triggeringGeofences).thenReturn(listOf(fence))
        Mockito.mockStatic(GeofencingEvent::class.java).use { events ->
            events.`when`<GeofencingEvent> { GeofencingEvent.fromIntent(Mockito.any(Intent::class.java)) }.thenReturn(event)
            GeofenceBroadcastReceiver().onReceive(context, Intent("test.exit"))
        }
    }

    @Test fun keepCurrentUsesConfirmedEmployerButDoesNotFollowLaterEmployerSwitch() {
        gps.edit().putString("zones", zones.replace(",\"companyId\":\"company-a\"", "")).commit()
        register()
        assertTrue(V2RuntimeStore.entry(context, observedAt - 60_000L))
        observe()
        assertTrue(V2ProfileStore.setActiveCompanyId(context, "company-b"))
        replay()
        assertTrue(pending())
        assertTrue(V2ProfileStore.setActiveCompanyId(context, "company-a"))
        replay()
        assertFalse(pending())
    }

    @Test fun wrongEmployerAndUnknownEmployerDoNotAcknowledgeExistingSession() {
        assertTrue(V2ProfileStore.setActiveCompanyId(context, "company-b"))
        assertTrue(V2RuntimeStore.entry(context, observedAt - 60_000L))
        observe()
        replay()
        assertTrue(pending())
        assertEquals("company-b", V2RuntimeReader.current(context).snapshot.session?.employerId)
        assertNull(resolvedGpsEntryEmployerIdV2(GpsZoneEmployerResolutionV2.KeepCurrent, null))
        assertNull(resolvedGpsEntryEmployerIdV2(GpsZoneEmployerResolutionV2.Block("unverified"), "company-a"))
    }

    @Test fun recoveryEvidenceIsNotTransferredByBackups() {
        assertFalse(GpsPresenceStateKeysV2.isTransferablePreferenceKey("gps_settings", GpsEntryRecoveryV2.KEY))
        assertNull(GpsEntryRecoveryV2.decode("broken"))
    }

    @Test fun receiverHomeCallbackNeverCreatesOrBlocksWorkPresence() {
        val home = """{"id":"home","latitude":46.7,"longitude":-1.4,"radius":150,"address":"Home","pointType":"HOME"}"""
        gps.edit().putString("zones", zones.dropLast(1) + "," + home + "]").commit()
        register()
        val event = Mockito.mock(GeofencingEvent::class.java)
        val homeFence = Mockito.mock(Geofence::class.java)
        val workFence = Mockito.mock(Geofence::class.java)
        Mockito.`when`(homeFence.requestId).thenReturn("home")
        Mockito.`when`(workFence.requestId).thenReturn("work")
        Mockito.`when`(event.geofenceTransition).thenReturn(Geofence.GEOFENCE_TRANSITION_ENTER)
        Mockito.`when`(event.triggeringGeofences).thenReturn(listOf(homeFence))
        val receiver = GeofenceBroadcastReceiver()
        val intent = Intent("com.amaury.pointage.TEST_GPS_ENTRY")
        context.registerReceiver(receiver, IntentFilter(intent.action), Context.RECEIVER_NOT_EXPORTED)
        try {
            Mockito.mockStatic(GeofencingEvent::class.java).use { events ->
                events.`when`<GeofencingEvent> { GeofencingEvent.fromIntent(Mockito.any(Intent::class.java)) }
                    .thenReturn(event)
                context.sendBroadcast(intent)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(gps.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, emptySet()).orEmpty().isEmpty())
                assertFalse(pending())
                assertNull(V2RuntimeReader.current(context).snapshot.session)

                Mockito.`when`(event.triggeringGeofences).thenReturn(listOf(homeFence, workFence))
                context.sendBroadcast(intent)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(setOf("work"), gps.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, null))
                assertTrue(pending())
                assertEquals(setOf("work"), checkNotNull(GpsEntryRecoveryV2.read(gps)).keys)
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private class FaultPreferences : SharedPreferences {
        private var memory = mutableMapOf<String, Any>()
        private var disk = mapOf<String, Any>()
        var failEntryWrites = 0
        var rejectCommits = false
        fun restart() { memory = disk.toMutableMap() }
        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
        override fun contains(key: String) = memory.containsKey(key)
        override fun getString(key: String, defValue: String?) = memory[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = (memory[key] as? Set<String>)?.toMutableSet() ?: defValues
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
                if (rejectCommits) return false
                if (changes.containsKey("real_entry") && changes["real_entry"] != null && failEntryWrites > 0) {
                    failEntryWrites--
                    return false
                }
                disk = memory.toMap()
                return true
            }
            override fun apply() { commit() }
        }
    }
}
