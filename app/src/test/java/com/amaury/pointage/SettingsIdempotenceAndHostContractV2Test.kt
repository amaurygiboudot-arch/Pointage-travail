package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIdempotenceAndHostContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/SettingsV2Host.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `settings installers remain idempotent across resume passes`() {
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")
        val organizer = source("app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt")
        val firstSteps = source("app/src/main/java/com/amaury/pointage/FirstStepsInitProvider.kt")

        val installer = settings.substringAfter("object SettingsUiInstaller")
        assertTrue(
            "SettingsUiInstaller must refuse a second root installation",
            installer.contains("if (panel.findViewWithTag<View>(TAG) != null) return")
        )
        assertTrue(
            "The pointage add-address action must have its own duplicate guard",
            installer.contains("if (section.findViewWithTag<View>(\"add_address_button\") != null) return")
        )

        listOf(
            "TAG_ACCOUNT_SECURITY",
            "TAG_POINTAGE",
            "TAG_CELESTIAL",
            "TAG_EXTRAS"
        ).forEach { tag ->
            assertTrue(
                "Core section $tag must be created only when absent",
                organizer.contains("SettingsV2Host.section(activity, SettingsV2Host.$tag) == null")
            )
        }

        listOf(
            "GpsZoneTypeView.TAG",
            "V2BackupRestoreView.TAG",
            "V2SecuritySettingsView.TAG",
            "\"first_steps_replay\""
        ).forEach { guard ->
            assertTrue(
                "Resume-installed control $guard must be protected from duplication",
                firstSteps.contains("findViewWithTag<View>($guard) == null") ||
                    firstSteps.contains("findViewWithTag<V2SecuritySettingsView>($guard)")
            )
        }
    }

    @Test
    fun `legacy gps panel id is only the transitional V2 host not a second settings owner`() {
        val host = source("app/src/main/java/com/amaury/pointage/SettingsV2Host.kt")
        val organizer = source("app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt")
        val layout = source("app/src/main/res/layout/activity_main.xml")

        assertTrue(
            "Until a dedicated replacement root exists, SettingsV2Host must remain the sole accessor for gpsSettingsPanel",
            host.contains("activity.findViewById(R.id.gpsSettingsPanel)")
        )
        assertTrue(layout.contains("android:id=\"@+id/gpsSettingsPanel\""))

        listOf(
            "settingsPointageTitle",
            "locationManagementView",
            "autoGpsSwitch",
            "gpsStatusText",
            "gpsPointPickerView",
            "locationPermissionButton"
        ).forEach { id ->
            assertTrue(
                "Legacy XML child $id must be classified into the canonical pointage V2 section",
                organizer.contains("\"$id\"")
            )
        }

        listOf(
            "settingsCelestialTitle",
            "settingsCelestialModeLabel",
            "celestialGlobeModeGroup",
            "settingsCelestialDescription",
            "celestialWeatherAttribution"
        ).forEach { id ->
            assertTrue(
                "Legacy XML child $id must be classified into the canonical celestial V2 section",
                organizer.contains("\"$id\"")
            )
        }
    }
}
