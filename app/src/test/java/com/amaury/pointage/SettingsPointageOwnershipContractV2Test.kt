package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPointageOwnershipContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `settings owns the add address button inside the pointage section`() {
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")
        val installer = settings.substringAfter("object SettingsUiInstaller")

        assertTrue(installer.contains("SettingsV2Host.TAG_POINTAGE"))
        assertTrue(installer.contains("installPointageAddressButton(activity)"))
        assertTrue(installer.contains("section.addView(addButton"))
        assertFalse(installer.contains("panel.addView(addButton"))
    }
}
