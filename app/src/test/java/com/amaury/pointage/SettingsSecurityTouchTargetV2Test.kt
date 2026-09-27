package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSecurityTouchTargetV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/SecurityUiInitProvider.kt").isFile }

    @Test
    fun `public security settings button keeps at least 48dp target`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/SecurityUiInitProvider.kt").readText()
        assertTrue(source.contains("minHeight = dp(activity, 48)"))
        assertTrue(source.contains("minimumHeight = dp(activity, 48)"))
        assertTrue(source.contains("kotlin.math.ceil(value * activity.resources.displayMetrics.density.toDouble()).toInt()"))
    }
}
