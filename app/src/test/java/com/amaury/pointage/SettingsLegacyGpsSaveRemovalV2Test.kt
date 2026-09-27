package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsLegacyGpsSaveRemovalV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/MainActivity.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `hidden legacy GPS save path stays removed`() {
        val main = source("app/src/main/java/com/amaury/pointage/MainActivity.kt")
        val layout = source("app/src/main/res/layout/activity_main.xml")
        val addressUi = source("app/src/main/java/com/amaury/pointage/AddressUiButtons.kt")
        val organizer = source("app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt")

        assertFalse(main.contains("saveGpsSettingsButton"))
        assertFalse(main.contains("private fun saveGpsSettings("))
        assertFalse(main.contains("private fun loadSavedZoneObjects("))
        assertFalse(main.contains("private fun existingZoneForAddress("))
        assertFalse(layout.contains("saveGpsSettingsButton"))
        assertFalse(addressUi.contains("saveGpsSettingsButton"))
        assertFalse(organizer.contains("saveGpsSettingsButton"))
        assertFalse(main.contains("settingsButton"))
        assertFalse(layout.contains("settingsButton"))
        assertFalse(source("app/src/main/java/com/amaury/pointage/PointageApplication.kt").contains("R.id.settingsButton"))
        assertFalse(source("app/src/main/java/com/amaury/pointage/ThemeFrameStyler.kt").contains("settingsButton"))
        assertFalse(source("app/src/main/java/com/amaury/pointage/ButtonReliefInstaller.kt").contains("settingsButton"))
        assertFalse(source("app/src/main/java/com/amaury/pointage/LuxuryUiInstaller.kt").contains("settingsButton"))
    }
}
