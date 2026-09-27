package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPointageSingleOwnerV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `settings installer is the only owner of add address ui`() {
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")
        val salaryTab = source("app/src/main/java/com/amaury/pointage/SalaryTabTextView.kt")

        val installer = settings.substringAfter("object SettingsUiInstaller")
        assertTrue(installer.contains("installPointageAddressButton(activity)"))
        assertTrue(installer.contains("SettingsV2Host.TAG_POINTAGE"))
        assertTrue(installer.contains("tag = \"add_address_button\""))

        assertFalse(salaryTab.contains("installAddressUi()"))
        assertFalse(salaryTab.contains("panel.addView(addButton"))
        assertFalse(salaryTab.contains("findViewWithTag<AddAddressButton>(\"add_address_button\")"))
    }
}
