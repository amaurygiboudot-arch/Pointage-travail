package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGpsPickerTouchTargetV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt").isFile }

    @Test
    fun `GPS picker quick actions keep at least 48dp targets`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/GpsPointPickerView.kt").readText()
        assertFalse(source.contains("LayoutParams(0, dp(44), 1f)"))
        assertTrue(source.contains("LayoutParams(0, dp(48), 1f)"))
        assertTrue(source.contains("kotlin.math.ceil(v * resources.displayMetrics.density.toDouble()).toInt()"))
    }
}
