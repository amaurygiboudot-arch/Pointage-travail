package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSecondaryTouchTargetV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `settings secondary 48dp controls round upward on fractional densities`() {
        val gps = source("app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt")
        val background = source("app/src/main/java/com/amaury/pointage/BackgroundPickerActivity.kt")

        assertTrue(gps.contains("dp(48)"))
        assertTrue(background.contains("dp(48)"))
        assertTrue(gps.contains("kotlin.math.ceil(value * resources.displayMetrics.density.toDouble()).toInt()"))
        assertTrue(background.contains("kotlin.math.ceil(v * resources.displayMetrics.density.toDouble()).toInt()"))
    }
}
