package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiInstallerIdempotenceContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    @Test
    fun `settings installer repairs missing sections without duplicating installed content`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").readText()
            .substringAfter("object SettingsUiInstaller")

        assertFalse(source.contains("panel.findViewWithTag<View>(TAG) != null) return"))
        listOf(
            "CONTENT_UPDATES",
            "CONTENT_APPEARANCE",
            "CONTENT_WIDGET",
            "CONTENT_DRIVE",
            "CONTENT_HELP"
        ).forEach { marker ->
            assertTrue(marker, source.contains("findViewWithTag<View>($marker) == null"))
        }
        assertTrue(source.contains("SettingsV2Host.section(activity, sectionTag) ?: LinearLayout(activity)"))
        assertTrue(source.contains("if (section.parent == null) panel.addView(section)"))
    }
}
