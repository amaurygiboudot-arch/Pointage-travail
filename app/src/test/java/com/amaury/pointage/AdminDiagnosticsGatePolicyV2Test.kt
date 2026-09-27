package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDiagnosticsGatePolicyV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/build.gradle.kts").isFile }

    @Test
    fun `developer mode is unavailable on public builds`() {
        assertFalse(AdminDiagnosticsGate.developerModeAllowed(internalDeveloperBuild = false))
    }

    @Test
    fun `developer mode remains available on internal debug builds`() {
        assertTrue(AdminDiagnosticsGate.developerModeAllowed(internalDeveloperBuild = true))
    }

    @Test
    fun `gradle keeps developer mode closed by default and opens debug only`() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("INTERNAL_DEVELOPER_MODE_ENABLED\", \"false"))
        assertTrue(gradle.contains("getByName(\"debug\")"))
        assertTrue(gradle.contains("INTERNAL_DEVELOPER_MODE_ENABLED\", \"true"))
        assertFalse(
            gradle.substringAfter("getByName(\"release\")").substringBefore("create(\"play\")")
                .contains("INTERNAL_DEVELOPER_MODE_ENABLED\", \"true")
        )
        assertFalse(
            gradle.substringAfter("create(\"play\")")
                .contains("INTERNAL_DEVELOPER_MODE_ENABLED\", \"true")
        )
    }
}
