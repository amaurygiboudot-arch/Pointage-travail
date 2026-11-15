package com.amaury.pointage

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.location.LocationManager
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.engine.GpsTransitionV2
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.UUID
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsSiteJourneyAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class GpsSiteJourneyStoreV2Test {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private val zones = """[
        {"id":"A","latitude":1,"longitude":2,"radius":100,"address":"Site A","companyId":"company-a","pointType":"POSTE"},
        {"id":"B","latitude":2,"longitude":3,"radius":100,"address":"Site B","companyId":"company-a","pointType":"POSTE"},
        {"id":"home","latitude":3,"longitude":4,"radius":100,"address":"Domicile","pointType":"HOME"}
    ]"""
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsSiteJourneyAuthShadow.auth = auth
        switchAccount("A-" + UUID.randomUUID().toString())
        configure(zones)
    }
    private fun switchAccount(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        V2RuntimeAccountScope.monitoringEpoch(context)
    }
    private fun configure(raw: String) {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        gps.edit().putBoolean("enabled", true).putString("zones", raw)
            .putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, true)
            .putString(GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT,
                storedGpsAutomaticFingerprintV2(true, parsePersistedGpsZones(raw))).commit()
    }
    private fun observe(zone: String, transition: GpsTransitionV2, at: Long) =
        GpsSiteJourneyStoreV2.observe(context, zone, transition, at)

    @Test fun storedFactsSurviveReadAndDuplicatesWithoutTouchingWorkPresence() {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val before = gps.all.toMap()
        assertTrue(observe("A", GpsTransitionV2.ENTER, 1000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, 2000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, 2500))
        assertTrue(observe("B", GpsTransitionV2.ENTER, 5000))
        val events = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(3, events.size)
        assertEquals(3000L, GpsSiteJourneyJournalV2.journeys(events)!!.single().durationMs)
        assertEquals(before, gps.all)
    }

    @Test fun accountChangeNeverPairsDifferentOwnersAndReturningAccountGetsNewEpoch() {
        switchAccount("A")
        assertTrue(observe("A", GpsTransitionV2.ENTER, 1000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, 2000))
        switchAccount("B")
        assertTrue(GpsSiteJourneyStoreV2.read(context)!!.isEmpty())
        assertTrue(observe("B", GpsTransitionV2.ENTER, 3000))
        switchAccount("A")
        assertTrue(observe("B", GpsTransitionV2.ENTER, 4000))
        val events = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(3, events.size)
        assertTrue(GpsSiteJourneyJournalV2.journeys(events)!!.isEmpty())
    }

    @Test fun configurationChangeAndInterruptionBreakPairingWithoutDeletingFacts() {
        assertTrue(observe("A", GpsTransitionV2.ENTER, 1000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, 2000))
        configure(zones.replace("Site B", "Site B modifié"))
        assertTrue(observe("B", GpsTransitionV2.ENTER, 3000))
        assertTrue(observe("B", GpsTransitionV2.EXIT, 4000))
        assertTrue(GpsSiteJourneyStoreV2.interrupt(context, 5000))
        assertTrue(observe("A", GpsTransitionV2.ENTER, 6000))
        val events = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(6, events.size)
        assertTrue(GpsSiteJourneyJournalV2.journeys(events)!!.isEmpty())
    }

    @Test fun homeCommuteIsKeptAsExclusionEvidenceAndNeverBecomesJourney() {
        assertTrue(observe("A", GpsTransitionV2.ENTER, 1000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, 2000))
        assertTrue(observe("home", GpsTransitionV2.ENTER, 3000))
        assertTrue(observe("home", GpsTransitionV2.EXIT, 4000))
        assertTrue(observe("B", GpsTransitionV2.ENTER, 5000))
        val events = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(5, events.size)
        assertTrue(GpsSiteJourneyJournalV2.journeys(events)!!.isEmpty())
    }

    @Test fun corruptJournalIsPreservedAndNotTreatedAsEmpty() {
        val prefs = V2RuntimeAccountScope.preferences(context, GpsSiteJourneyStoreV2.PREFS)
        assertTrue(prefs.edit().putString(GpsSiteJourneyStoreV2.KEY, "broken").commit())
        assertNull(GpsSiteJourneyStoreV2.read(context))
        assertFalse(observe("A", GpsTransitionV2.ENTER, 1000))
        assertEquals("broken", prefs.getString(GpsSiteJourneyStoreV2.KEY, null))
        assertNotNull(GpsSiteJourneyStoreV2.warning(context))
        assertTrue(prefs.getBoolean(GpsSiteJourneyStoreV2.INCOMPLETE, false))
    }

    @Test fun rejectedChronologyLeavesFactsIntactAndMarksJournalIncomplete() {
        assertTrue(observe("A", GpsTransitionV2.ENTER, 2000))
        assertFalse(observe("A", GpsTransitionV2.EXIT, 1000))
        assertEquals(1, GpsSiteJourneyStoreV2.read(context)!!.size)
        assertNotNull(GpsSiteJourneyStoreV2.warning(context))
        assertTrue(V2RuntimeAccountScope.preferences(context, GpsSiteJourneyStoreV2.PREFS)
            .getBoolean(GpsSiteJourneyStoreV2.INCOMPLETE, false))
    }

    @Test fun staleRegistrationUnknownZoneAndFutureTimeDoNotCreateFacts() {
        assertFalse(observe("unknown", GpsTransitionV2.ENTER, 1000))
        assertFalse(observe("A", GpsTransitionV2.ENTER, Long.MAX_VALUE))
        context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE).edit()
            .putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, false).commit()
        assertFalse(observe("A", GpsTransitionV2.ENTER, 1000))
        assertTrue(GpsSiteJourneyStoreV2.read(context)!!.isEmpty())
    }

    @Test fun homeAndWorkBatchUsesOneJournalCommitAndExcludesCommute() {
        assertTrue(observe("A", GpsTransitionV2.ENTER, 1000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, 2000))
        val probe = installCommitProbe()
        assertTrue(GpsSiteJourneyStoreV2.observeBatch(context, listOf("B", "home"), GpsTransitionV2.ENTER, 3000))
        assertEquals(1, probe.journalCommits)
        val events = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(setOf("B", "home"), events.filter { it.atMs == 3000L }.map { it.zoneId }.toSet())
        assertTrue(GpsSiteJourneyJournalV2.journeys(events)!!.isEmpty())
        // Same callback replay with reversed enumeration changes neither facts nor storage.
        assertTrue(GpsSiteJourneyStoreV2.observeBatch(context, listOf("home", "B"), GpsTransitionV2.ENTER, 3000))
        assertEquals(1, probe.journalCommits)
        assertEquals(events, GpsSiteJourneyStoreV2.read(context))
    }

    @Test fun invalidMemberRejectsEntireBatchWithoutPartialSiteArrival() {
        assertTrue(observe("A", GpsTransitionV2.ENTER, 1000))
        val before = GpsSiteJourneyStoreV2.read(context)
        val probe = installCommitProbe()
        assertFalse(GpsSiteJourneyStoreV2.observeBatch(context, listOf("B", "unknown"), GpsTransitionV2.ENTER, 3000))
        assertEquals(0, probe.journalCommits)
        assertEquals(before, GpsSiteJourneyStoreV2.read(context))
    }

    @Test fun failedBatchCommitIsNeverExposedUntilWholeBatchIsConfirmed() {
        assertTrue(observe("A", GpsTransitionV2.ENTER, 1000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, 2000))
        val probe = installCommitProbe()
        probe.failNextJournalCommit = true
        assertFalse(GpsSiteJourneyStoreV2.observeBatch(context, listOf("B", "home"), GpsTransitionV2.ENTER, 3000))
        assertEquals(1, probe.journalCommits)
        assertNull(GpsSiteJourneyStoreV2.read(context))
        assertNotNull(GpsSiteJourneyStoreV2.warning(context))
        assertTrue(GpsSiteJourneyStoreV2.observeBatch(context, listOf("home", "B"), GpsTransitionV2.ENTER, 3000))
        assertEquals(2, probe.journalCommits)
        val events = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(4, events.size)
        assertTrue(GpsSiteJourneyJournalV2.journeys(events)!!.isEmpty())
    }

    private fun healthyMonitoring() {
        shadowOf(RuntimeEnvironment.getApplication() as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setLocationEnabled(true)
    }

    private fun departedRecently() {
        val now = System.currentTimeMillis()
        assertTrue(observe("A", GpsTransitionV2.ENTER, now - 2000))
        assertTrue(observe("A", GpsTransitionV2.EXIT, now - 1000))
    }

    private fun arriveLaterWithoutJourney() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        configure(zones)
        assertTrue(observe("B", GpsTransitionV2.ENTER, System.currentTimeMillis()))
        assertTrue(GpsSiteJourneyJournalV2.journeys(GpsSiteJourneyStoreV2.read(context)!!)!!.isEmpty())
    }

    @Test fun disableAndReenableSameConfigurationCutsJourneyWithoutClearingArrivalEvidence() {
        healthyMonitoring()
        departedRecently()
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        gps.edit().putBoolean("enabled", false)
            .putString(GpsEntryRecoveryV2.KEY, "arrival-proof-preserved")
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("A")).commit()
        GeofenceManager.observeMonitoringAvailability(context)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        GeofenceManager.observeMonitoringAvailability(context)
        val stopped = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(1, stopped.count { it.kind == GpsSiteObservationKindV2.INTERRUPTION })
        assertEquals("arrival-proof-preserved", gps.getString(GpsEntryRecoveryV2.KEY, null))
        assertEquals(setOf("A"), gps.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, null))
        arriveLaterWithoutJourney()
    }

    @Test fun lostPermissionsAndDisabledSystemLocationEachCutJourney() {
        healthyMonitoring()
        departedRecently()
        shadowOf(RuntimeEnvironment.getApplication() as Application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        GeofenceManager.observeMonitoringAvailability(context)
        healthyMonitoring()
        arriveLaterWithoutJourney()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertTrue(observe("B", GpsTransitionV2.EXIT, System.currentTimeMillis()))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        shadowOf(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setLocationEnabled(false)
        GeofenceManager.observeMonitoringAvailability(context)
        healthyMonitoring()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertTrue(observe("A", GpsTransitionV2.ENTER, System.currentTimeMillis()))
        val events = GpsSiteJourneyStoreV2.read(context)!!
        assertEquals(2, events.count { it.kind == GpsSiteObservationKindV2.INTERRUPTION })
        assertTrue(GpsSiteJourneyJournalV2.journeys(events)!!.isEmpty())
    }

    @Test fun gmsRegistrationUnavailableCutsJourneyAndDuplicateSignalsAreIdempotent() {
        healthyMonitoring()
        departedRecently()
        assertTrue(GeofenceManager.markRegistrationUnavailable(context))
        assertTrue(GeofenceManager.markRegistrationUnavailable(context))
        assertEquals(1, GpsSiteJourneyStoreV2.read(context)!!.count { it.kind == GpsSiteObservationKindV2.INTERRUPTION })
        arriveLaterWithoutJourney()
    }

    @Test fun ordinaryHealthyResumeDoesNotInsertInterruption() {
        healthyMonitoring()
        departedRecently()
        val before = GpsSiteJourneyStoreV2.read(context)
        GeofenceManager.observeMonitoringAvailability(context)
        assertEquals(before, GpsSiteJourneyStoreV2.read(context))
        assertTrue(observe("B", GpsTransitionV2.ENTER, System.currentTimeMillis()))
        assertEquals(1, GpsSiteJourneyJournalV2.journeys(GpsSiteJourneyStoreV2.read(context)!!)!!.size)
    }

    private fun installCommitProbe(): JournalCommitProbe {
        val base = context
        val name = checkNotNull(V2RuntimeAccountScope.fileName(GpsSiteJourneyStoreV2.PREFS, V2RuntimeAccountScope.currentScope()))
        val probe = JournalCommitProbe(base.getSharedPreferences(name, Context.MODE_PRIVATE))
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(requested: String, mode: Int): SharedPreferences =
                if (requested == name) probe else base.getSharedPreferences(requested, mode)
        }
        return probe
    }

    /** A reported failure can leave the preferences cache changed; it must remain untrusted. */
    private class JournalCommitProbe(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var journalCommits = 0
        var failNextJournalCommit = false
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                private var journal = false
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                    if (key == GpsSiteJourneyStoreV2.KEY) journal = true
                    editor.putString(key, value)
                }
                override fun commit(): Boolean {
                    if (journal) journalCommits++
                    val stored = editor.commit()
                    if (journal && failNextJournalCommit) {
                        failNextJournalCommit = false
                        return false
                    }
                    return stored
                }
            }
        }
    }
}

@Implements(FirebaseAuth::class)
class GpsSiteJourneyAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
