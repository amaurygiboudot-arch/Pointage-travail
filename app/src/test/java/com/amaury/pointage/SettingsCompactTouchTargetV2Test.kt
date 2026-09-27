package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCompactTouchTargetV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/SettingsCompactMenuV2.kt").isFile }

    @Test
    fun `compact settings back button keeps a real minimum of 48dp`() {
        val source = File(
            root,
            "app/src/main/java/com/amaury/pointage/SettingsCompactMenuV2.kt"
        ).readText()

        assertTrue(source.contains("dp(activity, 48)"))
        assertTrue(
            source.contains(
                "kotlin.math.ceil(value * activity.resources.displayMetrics.density.toDouble()).toInt()"
            )
        )
    }
}
