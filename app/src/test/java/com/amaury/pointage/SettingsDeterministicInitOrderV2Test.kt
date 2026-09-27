package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDeterministicInitOrderV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/FirstStepsInitProvider.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `resume installers ensure settings root before section lookups`() {
        val firstSteps = source("app/src/main/java/com/amaury/pointage/FirstStepsInitProvider.kt")
        val firstPost = firstSteps.substringAfter("activity.window.decorView.post {")
        assertTrue(firstPost.indexOf("SettingsUiInstaller.install(activity)") >= 0)
        assertTrue(
            firstPost.indexOf("SettingsUiInstaller.install(activity)") <
                firstPost.indexOf("installGpsZoneTypeSelector(activity)")
        )

        val security = source("app/src/main/java/com/amaury/pointage/SecurityUiInitProvider.kt")
        val securityPost = security.substringAfter("activity.window.decorView.post {")
        assertTrue(
            securityPost.indexOf("SettingsUiInstaller.install(activity)") <
                securityPost.indexOf("SettingsV2Host.section(activity, SettingsV2Host.TAG_ACCOUNT_SECURITY)")
        )
    }
}
