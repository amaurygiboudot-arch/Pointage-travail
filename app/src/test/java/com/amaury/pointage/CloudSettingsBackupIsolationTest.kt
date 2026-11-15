package com.amaury.pointage

import android.app.Application
import android.content.Context
import com.amaury.pointage.v2.RestoreGuardedPreferences
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.SalaryLegacyRecovery
import com.amaury.pointage.v2.SalaryPreferenceRegistry
import com.amaury.pointage.v2.V2BackupManager
import com.amaury.pointage.v2.V2PreferenceValues
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Real preference export/import; only the account provider is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [GpsDeliveryAuthShadow::class],
    instrumentedPackages = ["com.google.firebase.auth"])
class CloudSettingsBackupIsolationTest {
    private lateinit var app: Context
    private lateinit var auth: FirebaseAuth
    private val ownerA = "settings-owner-A"
    private val ownerB = "settings-owner-B"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account(ownerA)
        assertTrue(app.getSharedPreferences("gps_settings", 0).edit()
            .putBoolean("enabled", false).putString("zones", "[]").putInt("radius", 125)
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("synthetic-zone"))
            .putBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, true).commit())
        assertTrue(app.getSharedPreferences("appearance_settings", 0).edit()
            .putString("visual_theme", "old-theme").putString("local_setting", "keep").commit())
        assertTrue(app.getSharedPreferences("shift_profiles", 0).edit()
            .putString("selected_shift", "legacy-global").commit())
        assertTrue(SalaryAccountScope.preferences(app, "shift_profiles").edit()
            .putString("selected_shift", "owner-A-mode").commit())
        account(ownerB)
        assertTrue(SalaryAccountScope.preferences(app, "shift_profiles").edit()
            .putString("selected_shift", "owner-B-mode").commit())
        account(ownerA)
    }

    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        SalaryAccountScope.capture()
    }

    private fun protectedValues(): Map<String, Map<String, *>> = listOf(
        "gps_settings", "appearance_settings", "shift_profiles",
        requireNotNull(SalaryPreferenceRegistry.fileName("shift_profiles", "uid:$ownerA")),
        requireNotNull(SalaryPreferenceRegistry.fileName("shift_profiles", "uid:$ownerB"))
    ).associateWith { app.getSharedPreferences(it, 0).all.toMap() }

    @Test fun exportSkipsExistingLegacyAndAccountProfilesAndKeepsSharedSettings() {
        assertTrue(File(app.applicationInfo.dataDir, "shared_prefs/shift_profiles.xml").isFile)
        val before = protectedValues()
        val exported = CloudSettingsBackup.exportPreferences(app)
        assertEquals(setOf("appearance_settings", "gps_settings"), exported.keys().asSequence().toSet())
        assertEquals("old-theme", exported.getJSONObject("appearance_settings").getString("visual_theme"))
        assertEquals(125, exported.getJSONObject("gps_settings").getInt("radius"))
        assertFalse(exported.getJSONObject("gps_settings").has(GpsPresenceStateKeysV2.ACTIVE_ZONES))
        assertFalse(exported.toString().contains("legacy-global"))
        assertFalse(exported.toString().contains("owner-A-mode"))
        assertFalse(exported.toString().contains("owner-B-mode"))
        assertEquals(before, protectedValues())
    }

    @Test fun personalOrNonCanonicalOnlyImportFailsBeforeAnyPreferenceMutation() {
        val names = listOf("shift_profiles", "salary_companies_v2", "GPS_SETTINGS", " gps_settings ",
            "Appearance_settings", requireNotNull(SalaryPreferenceRegistry.fileName("shift_profiles", "uid:$ownerA")))
        val before = protectedValues()
        names.forEach { name ->
            val payload = JSONObject().put(name, JSONObject().put("selected_shift", "untrusted"))
            assertThrows(name, IllegalArgumentException::class.java) {
                CloudSettingsBackup.importPreferences(app, payload)
            }
            assertEquals(name, before, protectedValues())
        }
    }

    @Test fun mixedHistoricalImportMergesSafeSettingsWithoutReadingOrChangingProfiles() {
        val before = protectedValues()
        val payload = JSONObject()
            .put("shift_profiles", "malformed-personal-data-stays-outside-settings")
            .put(" GPS_SETTINGS ", JSONObject().put("radius", 999))
            .put("appearance_settings", JSONObject().put("visual_theme", "restored-theme"))
            .put("gps_settings", JSONObject().put("radius", 200))
        val originalPayload = payload.toString()
        CloudSettingsBackup.importPreferences(app, payload)
        assertEquals(originalPayload, payload.toString())
        val after = protectedValues()
        assertEquals("restored-theme", after.getValue("appearance_settings")["visual_theme"])
        assertEquals("keep", after.getValue("appearance_settings")["local_setting"])
        assertEquals(200, after.getValue("gps_settings")["radius"])
        assertFalse(after.getValue("gps_settings").containsKey(GpsPresenceStateKeysV2.ACTIVE_ZONES))
        before.keys.filterNot { it in setOf("gps_settings", "appearance_settings") }.forEach { name ->
            assertEquals(name, before.getValue(name), after.getValue(name))
        }
    }

    @Test fun invalidCanonicalFileCannotPartiallyApplyEarlierSettings() {
        val before = protectedValues()
        val payload = JSONObject()
            .put("appearance_settings", JSONObject().put("visual_theme", "must-not-apply"))
            .put("gps_settings", "invalid-file")
        assertThrows(IllegalStateException::class.java) { CloudSettingsBackup.importPreferences(app, payload) }
        assertEquals(before, protectedValues())
    }

    @Test fun settingsScopeIsDisjointFromPersonalDomainsAndV2KeepsOwnerProfiles() {
        val settings = listOf("appearance_settings", "gps_settings", "navigation_state", "place_names",
            "smart_setup", "widget_style")
        settings.forEach { name ->
            assertTrue(name, CloudSettingsBackupPolicy.canTransferPreferenceFile(name))
            assertTrue(name, RestoreGuardedPreferences.manages(name))
            assertFalse(name, SalaryPreferenceRegistry.isPersonal(name))
            assertFalse(name, CloudSettingsBackupPolicy.canTransferPreferenceFile(name.uppercase()))
            assertFalse(name, CloudSettingsBackupPolicy.canTransferPreferenceFile(" $name "))
        }
        SalaryPreferenceRegistry.personalFiles.forEach { name ->
            assertFalse(name, CloudSettingsBackupPolicy.canTransferPreferenceFile(name))
        }
        listOf(ownerA to "owner-A-mode", ownerB to "owner-B-mode").forEach { (owner, expected) ->
            account(owner)
            assertTrue(SalaryLegacyRecovery.hasUnresolved(app))
            assertThrows(IllegalArgumentException::class.java) { V2BackupManager.snapshot(app) }
            val legacy = requireNotNull(SalaryLegacyRecovery.preview(app))
            // The ordinary explicit decision keeps the original separate; no attribution is implied.
            assertTrue(SalaryLegacyRecovery.decline(app, legacy.digest, true))
            val snapshot = V2BackupManager.snapshot(app)
            assertEquals("uid:$owner", snapshot.getString("accountScope"))
            val profiles = V2PreferenceValues.decode(snapshot.getJSONObject("preferences").getJSONObject("shift_profiles"))
            assertEquals(expected, profiles["selected_shift"])
            assertFalse(profiles.values.contains("legacy-global"))
            assertEquals(setOf(expected), profiles.values.toSet())
            assertEquals("legacy-global", app.getSharedPreferences("shift_profiles", 0).getString("selected_shift", null))
        }
    }
}
