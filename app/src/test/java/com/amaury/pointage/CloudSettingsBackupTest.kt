package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSettingsBackupTest {
    @Test fun rejectsUnusableBackupBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudSettingsBackup.validateImport(JSONObject().put("pointage", JSONObject()))
        }
        assertThrows(IllegalStateException::class.java) {
            CloudSettingsBackup.validateImport(JSONObject().put("gps_settings", "invalid"))
        }
    }

    @Test fun validatesEveryFileBeforeImport() {
        val backup = JSONObject()
            .put("company_settings", JSONObject().put("name", "Atelier"))
            .put("gps_settings", JSONObject().put("zones", JSONArray().put("valid").put(42)))
        assertThrows(IllegalArgumentException::class.java) {
            CloudSettingsBackup.validateImport(backup)
        }
        backup.put("gps_settings", JSONObject().put("zones", JSONArray().put("valid")))
        assertEquals(2, CloudSettingsBackup.validateImport(backup).size)
    }
}
