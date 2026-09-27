package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsPendingCompanyBindingContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `manual gps fallback preserves selected company until canonical zone exists`() {
        val addAddress = source("app/src/main/java/com/amaury/pointage/AddressUiButtons.kt")
        val picker = source("app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt")

        assertTrue(addAddress.contains("pending_point_company_bindings"))
        assertTrue(addAddress.contains("pendingCompanyBindings.put(formatted, binding)"))
        assertTrue(picker.contains("private fun pendingCompanyBindings(): JSONObject"))
        assertTrue(picker.contains("pendingCompanyId?.let { put(\"companyId\", it) }"))
        assertTrue(picker.contains("stagedCompanyId?.let { put(\"companyId\", it) }"))
        assertTrue(picker.contains("editor.remove(\"pending_point_company_bindings\")"))
    }
}
