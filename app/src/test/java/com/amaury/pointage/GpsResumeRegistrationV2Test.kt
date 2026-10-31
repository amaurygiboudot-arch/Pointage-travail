package com.amaury.pointage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * HT-010: opening the UI is not a reason to remove/reinstall a valid geofence.
 * The persisted exit/return evidence must remain untouched until an actual
 * geofence transition or a proven configuration/permission change occurs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GpsResumeRegistrationV2Test {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
    }

    private fun configuredRegistration() {
        val zones = """[{"id":"work","latitude":46.7,"longitude":-1.4,
            "radius":150,"address":"Atelier","companyId":"company-a","pointType":"POSTE"}]"""
        assertTrue(prefs.edit().putBoolean("enabled", true)
            .putString("zones", zones).commit())
        val stored = readPersistedGpsZones(prefs)
        assertTrue(stored is GpsZonesReadResult.Valid)
        val fingerprint = storedGpsAutomaticFingerprintV2(true, stored)
        assertTrue(prefs.edit()
            .putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, true)
            .putString(GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT, fingerprint)
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("work"))
            .putStringSet(GpsPresenceStateKeysV2.PENDING_EXIT_ZONES, setOf("work"))
            .putString(GpsExitDeliveryV2.KEY, "durable-exit-placeholder")
            .putStringSet(GpsReturnObservationV2.KEY, setOf("return-placeholder"))
            .commit())
    }

    @Test fun reopeningWithCurrentRegistrationKeepsPendingGpsEvidence() {
        configuredRegistration()
        val before = prefs.all.toMap()
        assertTrue(GeofenceManager.isStoredRegistrationCurrent(context))
        assertTrue(shouldKeepGeofenceRegistrationOnResumeV2(
            enabled = true, hasPermissions = true, locationEnabled = true,
            registrationCurrent = GeofenceManager.isStoredRegistrationCurrent(context)
        ))
        assertEquals(before, prefs.all)
        assertEquals(setOf("work"), prefs.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, null))
        assertEquals("durable-exit-placeholder", prefs.getString(GpsExitDeliveryV2.KEY, null))
    }

    @Test fun changedWorksiteFingerprintForcesSafeReregistration() {
        configuredRegistration()
        assertTrue(prefs.edit().putString("zones", """
            [{"id":"work","latitude":46.8,"longitude":-1.4,
            "radius":150,"address":"Atelier","companyId":"company-a","pointType":"POSTE"}]
        """).commit())
        assertFalse(GeofenceManager.isStoredRegistrationCurrent(context))
        assertFalse(shouldKeepGeofenceRegistrationOnResumeV2(
            true, true, true, GeofenceManager.isStoredRegistrationCurrent(context)
        ))
    }

    @Test fun missingOrUntrustedRegistrationCannotBeReused() {
        configuredRegistration()
        assertFalse(shouldKeepGeofenceRegistrationOnResumeV2(true, false, true, true))
        assertFalse(shouldKeepGeofenceRegistrationOnResumeV2(true, true, false, true))
        assertFalse(shouldKeepGeofenceRegistrationOnResumeV2(false, true, true, true))
        assertFalse(shouldKeepGeofenceRegistrationOnResumeV2(true, true, true, false))
        assertTrue(prefs.edit().putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, false).commit())
        assertFalse(GeofenceManager.isStoredRegistrationCurrent(context))
    }

    @Test fun serviceNotAvailableInvalidatesOnlyRegistrationReceipt() {
        configuredRegistration()
        val beforePresence = prefs.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, null)?.toSet()
        val beforePending = prefs.getStringSet(GpsPresenceStateKeysV2.PENDING_EXIT_ZONES, null)?.toSet()
        val beforeOutbox = prefs.getString(GpsExitDeliveryV2.KEY, null)
        val beforeReturn = prefs.getStringSet(GpsReturnObservationV2.KEY, null)?.toSet()

        assertTrue(GeofenceManager.markRegistrationUnavailable(context))
        assertFalse(prefs.getBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, true))
        assertNull(prefs.getString(GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT, null))
        assertFalse(GeofenceManager.isStoredRegistrationCurrent(context))
        assertEquals(beforePresence, prefs.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, null))
        assertEquals(beforePending, prefs.getStringSet(GpsPresenceStateKeysV2.PENDING_EXIT_ZONES, null))
        assertEquals(beforeOutbox, prefs.getString(GpsExitDeliveryV2.KEY, null))
        assertEquals(beforeReturn, prefs.getStringSet(GpsReturnObservationV2.KEY, null))
    }

    @Test fun uiResumeMustCheckFingerprintBeforeAnyDestructiveResync() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/java/com/amaury/pointage/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/com/amaury/pointage/MainActivity.kt").readText()
        val method = source.substringAfter("private fun tryRestoreGeofence()")
            .substringBefore("private fun updateDepartureObservationStatus()")
        val guard = method.indexOf("shouldKeepGeofenceRegistrationOnResumeV2(")
        val resync = method.indexOf("GeofenceManager.resyncStoredZones(this)")
        assertTrue("Resume must check the proven registration before resync", guard >= 0 && resync > guard)
        assertTrue("The valid-registration path must report success", method.contains(
            "showGpsReconciliationResult(true, \"Zones GPS déjà synchronisées\")"
        ))
    }

    @Test fun unknownGeofenceMustPreserveCurrentPresenceDuringPlatformResync() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/java/com/amaury/pointage/GeofenceBroadcastReceiver.kt").isFile }
        val receiver = File(root, "app/src/main/java/com/amaury/pointage/GeofenceBroadcastReceiver.kt").readText()
        val unknown = receiver.substringAfter("if (regularIds.any { it !in zonesById })")
            .substringBefore("val transition = when")
        assertFalse("Unknown zone must not erase existing active/pending presence",
            unknown.contains("clearZonePresenceState("))
        assertTrue("Unknown zone must select non-destructive platform resync",
            unknown.contains("preservePresence = true"))

        val manager = File(root, "app/src/main/java/com/amaury/pointage/GeofenceManager.kt").readText()
        val preserving = manager.substringAfter("fun resyncStoredZonesPreservingPresence(")
            .substringBefore("private fun enqueueStoredZonesReconciliation(")
        assertTrue(preserving.contains("clearBusinessState = false"))
        assertTrue(preserving.contains("clearPresenceState = false"))
    }

    @Test fun unavailableGmsErrorMustInvalidateRegistrationWithoutClosingSession() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/java/com/amaury/pointage/GeofenceBroadcastReceiver.kt").isFile }
        val source = File(root, "app/src/main/java/com/amaury/pointage/GeofenceBroadcastReceiver.kt")
            .readText()
        val errorBlock = source.substringAfter("if (event.hasError())")
            .substringBefore("val triggeredIds")
        assertTrue(errorBlock.contains("GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE"))
        assertTrue(errorBlock.contains("GeofenceManager.markRegistrationUnavailable(context)"))
        assertTrue(errorBlock.contains("finishAfterGpsReconciliation(context, configurationChanged = false)"))
        assertFalse(errorBlock.contains("V2RuntimeStore.exit("))
    }
}
