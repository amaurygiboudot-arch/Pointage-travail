package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGpsOwnershipV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `salary tab does not install GPS settings controls`() {
        val salaryTab = source("app/src/main/java/com/amaury/pointage/SalaryTabTextView.kt")
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")

        assertFalse(salaryTab.contains("installAddressUi()"))
        assertFalse(salaryTab.contains("AddAddressButton(context)"))
        assertTrue(
            settings.substringAfter("object SettingsUiInstaller")
                .contains("installPointageAddressButton(activity)")
        )
    }
}
