package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryTabSettingsIsolationContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/SalaryTabTextView.kt").isFile }

    @Test
    fun `salary tab no longer owns pointage address UI`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/SalaryTabTextView.kt").readText()

        assertFalse(source.contains("installAddressUi"))
        assertFalse(source.contains("add_address_button"))
        assertFalse(source.contains("AddAddressButton"))
        assertTrue(source.contains("showIntegratedSalaryTab"))
    }
}
