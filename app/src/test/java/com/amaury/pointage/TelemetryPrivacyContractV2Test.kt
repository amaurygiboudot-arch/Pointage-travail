package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPrivacyContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/TelemetryManager.kt").isFile }

    @Test
    fun `telemetry keeps crash consent off by default and has no second feedback channel`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/TelemetryManager.kt").readText()
        assertTrue(source.contains("getBoolean(KEY_CRASH_REPORTS, false)"))
        assertTrue(source.contains("options.isSendDefaultPii = false"))
        assertFalse(source.contains("fun sendIdea("))
        assertFalse(source.contains("Sentry.captureMessage"))
    }
}
