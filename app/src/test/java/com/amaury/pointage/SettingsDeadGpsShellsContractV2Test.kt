package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDeadGpsShellsContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/MainActivity.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `dead hidden gps shell views stay removed while save engine remains active`() {
        val layout = source("app/src/main/res/layout/activity_main.xml")
        val main = source("app/src/main/java/com/amaury/pointage/MainActivity.kt")
        val address = source("app/src/main/java/com/amaury/pointage/AddressUiButtons.kt")
        val organizer = source("app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt")

        assertFalse(layout.contains("settingsGeofenceRadiusLabel"))
        assertFalse(layout.contains("saveGpsSettingsButton"))
        assertFalse(main.contains("R.id.saveGpsSettingsButton"))
        assertFalse(address.contains("R.id.saveGpsSettingsButton"))
        assertFalse(organizer.contains("\"settingsGeofenceRadiusLabel\""))
        assertFalse(organizer.contains("\"saveGpsSettingsButton\""))

        assertTrue(main.contains("private fun saveGpsSettings("))
        assertTrue(main.contains("else {\n                saveGpsSettings()"))
        assertTrue(layout.contains("android:id=\"@+id/geofenceRadius\""))
        assertTrue(layout.contains("android:id=\"@+id/autoGpsSwitch\""))
    }
}
