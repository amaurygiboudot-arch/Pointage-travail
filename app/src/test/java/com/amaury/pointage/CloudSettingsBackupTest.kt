package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            .put("appearance_settings", JSONObject().put("mode", "dark"))
            .put("gps_settings", JSONObject().put("zones", JSONArray().put("valid").put(42)))
        assertThrows(IllegalArgumentException::class.java) {
            CloudSettingsBackup.validateImport(backup)
        }
        backup.put("gps_settings", JSONObject().put("zones", JSONArray().put("valid")))
        assertEquals(2, CloudSettingsBackup.validateImport(backup).size)
    }
    @Test fun ignoresMalformedDeviceLocalKeysButValidatesTransferableSettings() {
        val values = JSONObject()
            .put("active_tab", JSONObject().put("obsolete", true))
            .put("report_month_ms", 1790812800000L)
        val backup = JSONObject().put("navigation_state", values)
        assertEquals(1, CloudSettingsBackup.validateImport(backup).size)

        values.put("report_month_ms", JSONObject().put("invalid", true))
        assertThrows(IllegalStateException::class.java) {
            CloudSettingsBackup.validateImport(backup)
        }
    }
    @Test fun rejectsOutOfRangeNumbersBeforeImport() {
        listOf(Long.MAX_VALUE.toDouble(), 1e100, -1e100).forEach { number ->
            val backup = JSONObject()
                .put("appearance_settings", JSONObject().put("mode", "dark"))
                .put("navigation_state", JSONObject().put("report_month_ms", number))
            assertThrows(IllegalArgumentException::class.java) {
                CloudSettingsBackup.validateImport(backup)
            }
        }
    }

    @Test fun numericConversionNeverSaturatesOrProducesNonFiniteValues() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Long.MAX_VALUE.toDouble(), 1e100, -1e100).forEach {
            assertNull(CloudSettingsBackup.transferableDouble(it))
        }
        assertEquals(Long.MIN_VALUE, CloudSettingsBackup.transferableDouble(Long.MIN_VALUE.toDouble()))
        assertEquals(1790812800000L, CloudSettingsBackup.transferableDouble(1790812800000.0))
        assertEquals(1.25f, CloudSettingsBackup.transferableDouble(1.25))
    }

    @Test fun closedSettingsScopeIgnoresMalformedBusinessStores() {
        val backup = JSONObject()
            .put("salary_companies_v2", "not-settings")
            .put("horatrack_v2_payroll_coverage", "not-settings")
            .put("horatrack_v2_segmented_proration", "not-settings")
            .put("future_unreviewed_preferences", "not-settings")
            .put("appearance_settings", JSONObject().put("mode", "dark")
                .put("custom_image_bg", JSONObject().put("local", true)))
        assertEquals(listOf("appearance_settings"), CloudSettingsBackup.validateImport(backup).map { it.first })
    }

    @Test fun exportedFloatSettingsRemainRestorableAfterJsonReparse() {
        val exported = JSONObject().put("appearance_settings", JSONObject()
            .put("brightness", 0.75f).put("scale", 1.25f))
        val reparsed = JSONObject(exported.toString())
        assertEquals(1, CloudSettingsBackup.validateImport(reparsed).size)
        val brightness = reparsed.getJSONObject("appearance_settings").get("brightness") as Number
        assertEquals(0.75f, CloudSettingsBackup.transferableNumber(brightness))
    }

    @Test fun reparsedExponentAndDecimalNumbersHaveCheckedConversions() {
        fun backup(number: String) = JSONObject("""{"navigation_state":{"value":$number}}""")
        val supported = backup("1.79E12")
        assertEquals(1, CloudSettingsBackup.validateImport(supported).size)
        assertEquals(1790000000000L, CloudSettingsBackup.transferableNumber(
            supported.getJSONObject("navigation_state").get("value") as Number))
        assertEquals(Long.MAX_VALUE, CloudSettingsBackup.transferableNumber(
            java.math.BigDecimal("9223372036854775807")))
        listOf("1E100", "-1E100", "9223372036854775808", "-9223372036854775809").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                CloudSettingsBackup.validateImport(backup(it))
            }
        }
        assertNull(CloudSettingsBackup.transferableNumber(java.math.BigDecimal("1E100")))
    }

}
