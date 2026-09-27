package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDiagnosticsManifestPolicyTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }

    @Test
    fun `developer and recovery activities stay non exported`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        listOf(
            ".RecoveryActivityV2",
            ".AdminDiagnosticsActivity",
            ".OwnerEnrollmentActivity"
        ).forEach { activity ->
            val declaration = manifest.substringAfter("android:name=\"$activity\"").substringBefore("/>")
            assertTrue("$activity must stay non exported", declaration.contains("android:exported=\"false\""))
        }
    }
}
