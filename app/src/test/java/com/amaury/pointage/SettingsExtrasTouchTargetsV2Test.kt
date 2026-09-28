package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsExtrasTouchTargetsV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/FirstStepsInitProvider.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `public extras actions keep at least 48dp touch targets`() {
        val firstSteps = source("app/src/main/java/com/amaury/pointage/FirstStepsInitProvider.kt")
        val snake = source("app/src/main/java/com/amaury/pointage/SnakeGameButtonView.kt")

        assertTrue(firstSteps.contains("minHeight = dp(activity, 48)"))
        assertTrue(firstSteps.contains("minimumHeight = dp(activity, 48)"))
        assertTrue(snake.contains("minHeight = minimumTouchTargetPx()"))
        assertTrue(snake.contains("minimumHeight = minimumTouchTargetPx()"))
        assertTrue(snake.contains("kotlin.math.ceil(48 * resources.displayMetrics.density.toDouble()).toInt()"))
    }
}
