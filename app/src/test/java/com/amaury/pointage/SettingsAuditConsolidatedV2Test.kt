package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAuditConsolidatedV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `settings audit keeps touch targets actions and branding coherent`() {
        val security = source("app/src/main/java/com/amaury/pointage/SecurityUiInitProvider.kt")
        val gpsPicker = source("app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt")
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")
        val menu = source("app/src/main/java/com/amaury/pointage/SettingsCompactMenuV2.kt")
        val guide = source("app/src/main/java/com/amaury/pointage/UserGuideDialog.kt")
        val securityInfo = source("app/src/main/java/com/amaury/pointage/SecurityInfoActivity.kt")
        val owner = source("app/src/main/java/com/amaury/pointage/OwnerFeedbackActivity.kt")

        assertTrue(security.contains("minHeight = dp(activity, 48)"))
        assertTrue(security.contains("minimumHeight = dp(activity, 48)"))
        assertTrue(security.contains("kotlin.math.ceil(value * activity.resources.displayMetrics.density.toDouble()).toInt()"))

        assertFalse(gpsPicker.contains("LayoutParams(0, dp(44), 1f)"))
        assertTrue(gpsPicker.contains("LayoutParams(0, dp(48), 1f)"))
        assertTrue(gpsPicker.contains("kotlin.math.ceil(value * resources.displayMetrics.density.toDouble()).toInt()"))

        assertTrue(settings.contains("VÉRIFIER LES MISES À JOUR"))
        assertTrue(settings.contains("UpdateChecker.check("))
        assertTrue(settings.contains("askBeforeDownload = true"))
        assertTrue(settings.contains("if (configured && !HoraTrackV2.ENABLED) View.VISIBLE else View.GONE"))
        assertTrue(menu.substringAfter("Page.HELP -> setOf(").substringBefore(")").contains("SettingsV2Host.TAG_UPDATES"))

        listOf(guide, securityInfo, owner).forEach { visible ->
            assertFalse(visible.contains("HP Travail"))
            assertFalse(visible.contains("HP TRAVAIL"))
        }
        assertTrue(guide.contains("AGKGMG"))
    }
}
