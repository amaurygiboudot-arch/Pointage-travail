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
        val gpsType = source("app/src/main/java/com/amaury/pointage/GpsZoneTypeView.kt")
        val backup = source("app/src/main/java/com/amaury/pointage/V2BackupRestoreView.kt")

        val installer = settings.substringAfter("object SettingsUiInstaller")
        assertTrue(
            "SettingsUiInstaller must refuse a second root installation",
            installer.contains("if (panel.findViewWithTag<View>(TAG) != null) return") ||
                installer.contains("if (SettingsV2Host.contains(activity, TAG)) return")
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

        assertTrue(
            "GpsZoneTypeView must both own and expose its duplicate tag",
            gpsType.contains("tag = TAG") &&
                firstSteps.contains("findViewWithTag<View>(GpsZoneTypeView.TAG) == null")
        )
        assertTrue(
            "V2BackupRestoreView must both own and expose its duplicate tag",
            backup.contains("tag = TAG") &&
                firstSteps.contains("findViewWithTag<View>(V2BackupRestoreView.TAG) == null")
        )

        val securityInstaller = firstSteps
            .substringAfter("private fun installSecuritySettings")
            .substringBefore("private fun installV2PdfExport")
        assertTrue(securityInstaller.contains("val existing = section.findViewWithTag<V2SecuritySettingsView>(V2SecuritySettingsView.TAG)"))
        assertTrue(securityInstaller.contains("if (existing == null)"))
        assertTrue(securityInstaller.contains("section.addView("))
        assertTrue(securityInstaller.contains("else existing.refresh()"))

        val replayInstaller = firstSteps
            .substringAfter("private fun installReplayButton")
            .substringBefore("private fun removeLegacyGpsTestButton")
        assertTrue(
            "Tutorial replay must early-return when already installed",
            replayInstaller.contains("findViewWithTag<View>(\"first_steps_replay\") != null") &&
                replayInstaller.contains("return")
        )
    }

    @Test
    fun `transitional gps host routes every legacy settings child to its V2 section`() {
        val host = source("app/src/main/java/com/amaury/pointage/SettingsV2Host.kt")
        val organizer = source("app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt")
        val layout = source("app/src/main/res/layout/activity_main.xml")
        val salaryTab = source("app/src/main/java/com/amaury/pointage/SalaryTabTextView.kt")

        assertTrue(
            "Until a dedicated replacement root exists, SettingsV2Host must expose gpsSettingsPanel",
            host.contains("activity.findViewById(R.id.gpsSettingsPanel)")
        )
        assertTrue(layout.contains("android:id=\"@+id/gpsSettingsPanel\""))
        assertTrue(
            "Salary tab must not install GPS settings controls",
            !salaryTab.contains("installAddressUi()") &&
                !salaryTab.contains("AddAddressButton(context)")
        )

        val pointageClassifier = organizer
            .substringAfter("private fun isPointageView")
            .substringBefore("private fun isCelestialView")
        listOf(
            "settingsPointageTitle",
            "settingsGeofenceRadiusLabel",
            "workplaceAddress",
            "geofenceRadius",
            "autoGpsSwitch",
            "gpsStatusText",
            "gpsPointPickerView",
            "locationPermissionButton",
            "locationManagementView"
        ).forEach { id ->
            assertTrue(
                "Legacy XML child $id must remain in the Pointage classifier",
                pointageClassifier.contains("\"$id\"")
            )
        }

        val celestialClassifier = organizer.substringAfter("private fun isCelestialView")
        listOf(
            "settingsCelestialTitle",
            "settingsCelestialModeLabel",
            "celestialGlobeModeGroup",
            "settingsCelestialDescription",
            "celestialWeatherAttribution"
        ).forEach { id ->
            assertTrue(
                "Legacy XML child $id must remain in the Celestial classifier",
                celestialClassifier.contains("\"$id\"")
            )
        }
    }
}
