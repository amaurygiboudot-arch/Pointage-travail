package com.amaury.pointage

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.GpsDecisionV2
import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
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

/** Real runtime/coordinator/outbox, with disk/cache divergence injected at preference commits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class GpsExitDeliveryIntegrationV2Test {
    private lateinit var context: Context
    private lateinit var gps: FaultPreferences
    private lateinit var business: FaultPreferences
    private lateinit var auth: FirebaseAuth
    private var arrival = 0L
    private var exit = 0L
    private val zones = """[{"id":"work","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A","companyId":"company-a","pointType":"POSTE"}]"""

    @Before fun setup() {
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        changeAccount("account-a")
        gps = FaultPreferences()
        business = FaultPreferences()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
                "gps_settings" -> gps
                "horatrack_v2_gps_state" -> business
                else -> super.getSharedPreferences(name, mode)
            }
        }
        arrival = System.currentTimeMillis() - 3_600_000L
        exit = arrival + 600_000L
        V2RuntimeStore.reset(context)
        assertTrue(V2RuntimeStore.entry(context, arrival))
        assertTrue(V2RuntimeReader.current(context).reliable)
        assertTrue(gps.edit().putBoolean("enabled", true).putString("zones", zones).commit())
        HoraTrackV2.gps.reset()
    }

    private fun changeAccount(uid: String?) {
        val user = uid?.let {
            Mockito.mock(FirebaseUser::class.java).also { user -> Mockito.`when`(user.uid).thenReturn(uid) }
        }
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }

    private fun record(): GpsExitDeliveryRecordV2 = checkNotNull(GpsExitDeliveryV2.prepare(context,
        GpsEventV2("exit-work-$exit", exit, "work", GpsPointTypeV2.POSTE, GpsTransitionV2.EXIT),
        GpsExitDeliveryV2.observationContext(context)))

    private fun stage(record: GpsExitDeliveryRecordV2 = record()) {
        assertTrue(GpsExitDeliveryV2.commitPresence(gps,
            gps.edit().putStringSet("active_zones", emptySet()).remove("pending_exit_zones"), record))
    }

    private fun restartPreferences() { gps.restart(); business.restart() }

    private fun persistReturnBeforeTimer(record: GpsExitDeliveryRecordV2, zoneId: String = "work",
        returnedAtMs: Long = exit + 60_000L, additionalZoneIds: Set<String> = emptySet(),
        availableAtMs: Long = returnedAtMs + 2_000L) {
        val stored = checkNotNull(readPersistedGpsZones(gps) as? GpsZonesReadResult.Valid).zones
        val active = additionalZoneIds + zoneId
        val previousActive = gps.getStringSet("active_zones", emptySet()).orEmpty()
        val previous = GpsReturnObservationV2.decode(gps.getStringSet(GpsReturnObservationV2.KEY, emptySet()).orEmpty())
        val records = GpsReturnObservationV2.observe(previous, stored.map { it.id }.toSet(), active - previousActive,
            returnedAtMs, record.observationContext()).values.map { it.encode() }.toSet()
        val selection = GpsTriggeredZoneSelectionV2.selectAllTriggered(active.sorted(),
            stored.filter { it.id in active }.map { zone -> GpsTriggeredZoneSelectionV2.Candidate(
                zone.id, "company:${zone.companyId}", GpsTriggeredZoneSelectionV2.pointType(zone),
                GpsTriggeredZoneSelectionV2.placeKey(zone)) })
        val editor = gps.edit()
            .putStringSet("active_zones", active)
            .putBoolean("entry_resolution_pending", true)
            .putString("entry_resolution_token", "timer-that-never-ran")
            .putStringSet(GpsReturnObservationV2.KEY, records)
        if (selection is GpsTriggeredZoneSelectionV2.Result.Selected) {
            editor.putString(GpsReturnObservationV2.QUALIFICATION_KEY,
                GpsReturnObservationV2.Qualification(selection.zoneId, availableAtMs).encode())
        } else editor.remove(GpsReturnObservationV2.QUALIFICATION_KEY)
        assertTrue(GpsExitDeliveryV2.commitPresence(gps, editor, null))
    }

    @Test fun crashAfterFinalPresenceCommitRecoversAtPromptWithoutNewGpsCallback() {
        val record = record()
        stage(record)
        restartPreferences()
        val pending = GpsWorkStateCoordinatorV2.pendingForOpenSession(context)
        assertEquals(record.event.id, pending?.id)
        assertEquals(exit, pending?.atMs)
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isEmpty())
        assertTrue(GpsWorkStateCoordinatorV2.confirmExit(context, record.event.id))
        assertEquals(exit, V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun failedPendingCommitDoesNotAcknowledgeDespiteMutatedMemoryCache() {
        stage()
        business.failNext = 1
        GpsExitDeliveryV2.replay(context)
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isNotEmpty())
        restartPreferences()
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
        GpsExitDeliveryV2.replay(context)
        assertEquals(exit, GpsWorkStateCoordinatorV2.pending(context)?.atMs)
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isEmpty())
    }

    @Test fun failedAtomicStagingDoesNotPublishPresenceOrObservationAfterRestart() {
        assertTrue(gps.edit().putStringSet("active_zones", setOf("work")).commit())
        gps.failNext = 1
        assertFalse(GpsExitDeliveryV2.commitPresence(gps,
            gps.edit().putStringSet("active_zones", emptySet()), record()))
        gps.restart()
        assertEquals(setOf("work"), gps.getStringSet("active_zones", emptySet()))
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isEmpty())
    }

    @Test fun cancelledDeliveryNeverResurrectsAfterCleanupFailureAndReceiptRetry() {
        val record = record()
        stage(record)
        gps.failNext = 1
        GpsExitDeliveryV2.replay(context) // Pending/receipt durable, outbox removal failed.
        gps.restart()
        gps.failNext = 1
        assertTrue(GpsWorkStateCoordinatorV2.cancelPending(context, record.event.id))
        restartPreferences()
        business.failNext = 1 // Existing ACK cannot be recommitted this time.
        GpsExitDeliveryV2.replay(context)
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isNotEmpty())
        GpsExitDeliveryV2.replay(context)
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isEmpty())
    }

    @Test fun replayIgnoresRamDebounceAndNeverClosesWorkWithoutConfirmation() {
        val record = record()
        assertTrue(HoraTrackV2.gps.ingest(record.event).accepted)
        assertTrue(HoraTrackV2.gps.ingest(record.event).duplicate)
        stage(record)
        GpsExitDeliveryV2.replay(context)
        assertEquals(record.event.id, GpsWorkStateCoordinatorV2.pending(context)?.id)
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun unreliableRuntimeRetainsOutboxUntilVerifiedAgain() {
        stage()
        val runtime = context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        assertTrue(runtime.edit().putString("history", "not-json").commit())
        GpsExitDeliveryV2.replay(context)
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isNotEmpty())
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
        assertTrue(runtime.edit().putString("history", "[]").commit())
        GpsExitDeliveryV2.replay(context)
        assertEquals(exit, GpsWorkStateCoordinatorV2.pending(context)?.atMs)
    }

    @Test fun newSessionDoesNotReceiveOldSessionDeparture() {
        stage()
        assertTrue(V2RuntimeStore.exit(context, exit + 60_000L))
        assertTrue(V2RuntimeStore.entry(context, exit + 120_000L))
        GpsExitDeliveryV2.replay(context)
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isEmpty())
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun accountSwitchInvalidatesQueuedAndAlreadyDeliveredQuestions() {
        val record = record()
        stage(record)
        GpsExitDeliveryV2.replay(context)
        changeAccount("account-b")
        assertNull(GpsWorkStateCoordinatorV2.pendingForOpenSession(context))
        assertFalse(GpsWorkStateCoordinatorV2.confirmExit(context, record.event.id))
        stage(record)
        GpsExitDeliveryV2.replay(context)
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun guestIsExplicitAndCannotBePromotedToAnotherAccount() {
        changeAccount(null)
        val guest = record()
        assertEquals("guest", guest.accountScope)
        stage(guest)
        changeAccount("account-b")
        GpsExitDeliveryV2.replay(context)
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
    }

    @Test fun changedGpsConfigurationInvalidatesQueuedDeparture() {
        stage()
        assertTrue(gps.edit().putString("zones", zones.replace("120", "250")).commit())
        GpsExitDeliveryV2.replay(context)
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isEmpty())
    }

    @Test fun partialObservationCannotBindToNewAccountOrSessionAtFinalDeparture() {
        val originalContext = GpsExitDeliveryV2.observationContext(context)
        val event = record().event
        changeAccount("account-b")
        assertNull(GpsExitDeliveryV2.prepare(context, event, originalContext))
        changeAccount("account-a")
        assertTrue(V2RuntimeStore.exit(context, exit + 60_000L))
        assertTrue(V2RuntimeStore.entry(context, exit + 120_000L))
        assertNull(GpsExitDeliveryV2.prepare(context, event.copy(atMs = exit + 180_000L), originalContext))
    }

    @Test fun recoveredExitCanBeCancelledByReturnBeforeDelayedCleanup() {
        val record = record()
        stage(record)
        gps.failNext = 1
        GpsExitDeliveryV2.replay(context)
        val returned = GpsWorkStateCoordinatorV2.route(context,
            record.event.copy(id = "return", atMs = exit + 60_000L, transition = GpsTransitionV2.ENTER),
            GpsDecisionV2(true, false, false, "test"))
        assertEquals(GpsWorkStateCoordinatorV2.Action.RETURNED_TO_POSTE, returned.action)
        restartPreferences()
        GpsExitDeliveryV2.replay(context)
        assertNull(GpsWorkStateCoordinatorV2.pending(context))
    }

    @Test fun unfinishedPromptFromDeadProcessCanBeShownAgain() {
        stage()
        val pending = checkNotNull(GpsWorkStateCoordinatorV2.pendingForOpenSession(context))
        GpsWorkStateCoordinatorV2.markPromptShown(context, pending)
        assertFalse(GpsWorkStateCoordinatorV2.shouldPrompt(context, pending))
        assertTrue(business.edit().putString("prompted_process", "previous-process").commit())
        assertTrue(GpsWorkStateCoordinatorV2.shouldPrompt(context, pending))
    }

    @Test fun crashAfterReturnEnterBeforeTimerDoesNotRestoreOldExitQuestion() {
        val record = record()
        stage(record)
        GpsExitDeliveryV2.replay(context)
        assertNotNull(GpsWorkStateCoordinatorV2.pending(context))
        persistReturnBeforeTimer(record)
        restartPreferences() // No entry dispatch or timer is executed.
        assertNull(GpsWorkStateCoordinatorV2.pendingForOpenSession(context))
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
        restartPreferences()
        assertNull(GpsWorkStateCoordinatorV2.pendingForOpenSession(context))
    }

    @Test fun retryDeliveryThenReturnEnterSurvivesCrashWithoutRecreatingExit() {
        val record = record()
        stage(record)
        business.failNext = 1
        GpsExitDeliveryV2.replay(context)
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isNotEmpty())
        persistReturnBeforeTimer(record)
        restartPreferences()
        assertNull(GpsWorkStateCoordinatorV2.pendingForOpenSession(context))
        assertTrue(gps.getStringSet(GpsExitDeliveryV2.KEY, emptySet())!!.isEmpty())
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun activeZoneWithoutBoundTimestampDoesNotProveReturn() {
        stage()
        assertTrue(gps.edit().putStringSet("active_zones", setOf("work")).commit())
        assertNotNull(GpsWorkStateCoordinatorV2.pendingForOpenSession(context))
    }

    @Test fun returnCannotCancelBeforeCompleteEntryBatchWindow() {
        val record = record()
        stage(record)
        persistReturnBeforeTimer(record, availableAtMs = System.currentTimeMillis() + 60_000L)
        assertEquals(record.event.id, GpsWorkStateCoordinatorV2.pendingForOpenSession(context)?.id)
    }

    private fun configureMixedWorksites() {
        val second = """{"id":"work-b","latitude":46.70035,"longitude":-1.4,"radius":120,"address":"Site A","companyId":"company-a","pointType":"POSTE"}"""
        val conflicting = """{"id":"work-c","latitude":46.70035,"longitude":-1.4,"radius":120,"address":"Site C","companyId":"company-c","pointType":"POSTE"}"""
        assertTrue(gps.edit().putString("zones", zones.removeSuffix("]") + ",$second,$conflicting]").commit())
    }

    private fun mixedReturnCannotCancel(zoneId: String) {
        configureMixedWorksites()
        val record = record()
        stage(record)
        persistReturnBeforeTimer(record, zoneId, additionalZoneIds = setOf("work-c"))
        restartPreferences()
        assertEquals(record.event.id, GpsWorkStateCoordinatorV2.pendingForOpenSession(context)?.id)
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun equivalentReturnBWithContradictoryCDoesNotCancelDepartureA() = mixedReturnCannotCancel("work-b")

    @Test fun sameZoneReturnAWithContradictoryCDoesNotCancelDepartureA() = mixedReturnCannotCancel("work")

    @Test fun separateEnterBBeforeContradictoryCInvalidatesEarlierQualificationBeforeReplay() {
        configureMixedWorksites()
        val record = record()
        stage(record)
        persistReturnBeforeTimer(record, "work-b")
        // C's receiver must not consume B's previous qualification before incorporating C.
        GpsExitDeliveryV2.replay(context, allowReturnAcknowledgement = false)
        assertEquals(record.event.id, GpsWorkStateCoordinatorV2.pending(context)?.id)
        persistReturnBeforeTimer(record, "work-c", exit + 60_500L, additionalZoneIds = setOf("work-b"))
        restartPreferences()
        assertEquals(record.event.id, GpsWorkStateCoordinatorV2.pendingForOpenSession(context)?.id)
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    private fun distinctZoneReturn(company: String = "company-a", site: String = "Site A",
        delayMs: Long = 60_000L, cancels: Boolean) {
        val second = """{"id":"work-b","latitude":46.70035,"longitude":-1.4,"radius":120,"address":"$site","companyId":"$company","pointType":"POSTE"}"""
        assertTrue(gps.edit().putString("zones", zones.removeSuffix("]") + "," + second + "]").commit())
        val record = record()
        stage(record)
        GpsExitDeliveryV2.replay(context)
        persistReturnBeforeTimer(record, "work-b", exit + delayMs)
        restartPreferences()
        val pending = GpsWorkStateCoordinatorV2.pendingForOpenSession(context)
        if (cancels) assertNull(pending) else assertEquals(record.event.id, pending?.id)
        assertNull(V2RuntimeReader.current(context).snapshot.session?.realExitMs)
    }

    @Test fun crashBeforeTimerAlsoRecognizesEquivalentOverlappingWorksiteReturn() =
        distinctZoneReturn(cancels = true)

    @Test fun distinctEmployerCannotCancelEarlierExitAfterCrash() =
        distinctZoneReturn(company = "company-b", cancels = false)

    @Test fun distinctPlaceCannotCancelEarlierExitAfterCrash() =
        distinctZoneReturn(site = "Site B", cancels = false)

    @Test fun distinctZoneReturnBeyondTwoMinutesCannotCancelEarlierExitAfterCrash() =
        distinctZoneReturn(delayMs = 120_001L, cancels = false)

    /** commit(false) updates cache but not disk, matching the Android failure mode under test. */
    private class FaultPreferences : SharedPreferences {
        private var memory = mutableMapOf<String, Any>()
        private var disk = mapOf<String, Any>()
        var failNext = 0
        fun restart() { memory = disk.toMutableMap() }
        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
        override fun contains(key: String): Boolean = memory.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = memory[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (memory[key] as? Set<String>)?.toMutableSet() ?: defValues
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
                if (failNext > 0) { failNext--; return false }
                disk = memory.toMap()
                return true
            }
            override fun apply() { commit() }
        }
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class GpsDeliveryAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
