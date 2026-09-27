package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsPendingCompanyBindingContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `manual gps fallback preserves each selected company until its canonical zone exists`() {
        val addAddress = source("app/src/main/java/com/amaury/pointage/AddressUiButtons.kt")
        val picker = source("app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt")

        assertTrue(addAddress.contains("pending_point_company_bindings"))
        assertTrue(addAddress.contains("pendingCompanyBindings.put(formatted, binding)"))
        assertTrue(addAddress.contains("pendingCompanyBindings.remove(formatted)"))

        assertTrue(picker.contains("private fun pendingCompanyBindings(): JSONObject"))
        assertTrue(picker.contains("firstOrNull { it.equals(address.trim(), ignoreCase = true) }"))
        assertTrue(picker.contains("pendingCompanyId?.let { put(\"companyId\", it) }"))
        assertTrue(picker.contains("pendingCompanySlot?.let { put(\"companySlot\", it) }"))

        assertTrue(picker.contains("val stagedCompanyId = zone.optString(\"companyId\")"))
        assertTrue(picker.contains("stagedCompanyId?.let { put(\"companyId\", it) }"))
        assertTrue(picker.contains("stagedCompanySlot?.let { put(\"companySlot\", it) }"))

        assertTrue(
            picker.contains(
                "firstOrNull { it.equals(address, ignoreCase = true) }"
            )
        )
        assertTrue(picker.contains("editor.putString(\"pending_point_company_bindings\""))
        assertTrue(picker.contains("editor.remove(\"pending_point_company_bindings\")"))
    }
}
