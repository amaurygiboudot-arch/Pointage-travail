package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReleaseFinalizeV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/build.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `public builds cannot enable developer enrollment`() {
        assertFalse(AdminDiagnosticsGate.developerModeAllowed(internalDeveloperBuild = false))
        assertTrue(AdminDiagnosticsGate.developerModeAllowed(internalDeveloperBuild = true))

        val gradle = source("app/build.gradle.kts")
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

    @Test
    fun `owner feedback controls keep release accessibility contract`() {
        val feedback = source("app/src/main/java/com/amaury/pointage/OwnerFeedbackActivity.kt")
        val suggestions = source("app/src/main/java/com/amaury/pointage/SuggestionBoxView.kt")

        assertFalse(feedback.contains("dp(46)"))
        assertTrue(feedback.contains("LinearLayout.LayoutParams(0, dp(48), 1f)"))
        assertTrue(feedback.contains("ViewGroup.LayoutParams.MATCH_PARENT, dp(48)"))
        assertTrue(feedback.contains("kotlin.math.ceil(value * resources.displayMetrics.density.toDouble()).toInt()"))
        assertTrue(suggestions.contains("adaptiveButton(\"IDÉES REÇUES\")"))
        assertFalse(suggestions.contains("📥  IDÉES REÇUES"))
    }
}
