package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleBrandingV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/LaunchActivity.kt").isFile }

    @Test
    fun `first install welcome uses current visible product name`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/LaunchActivity.kt").readText()

        assertTrue(source.contains("BIENVENUE SUR AGKGMG"))
        assertFalse(source.contains("BIENVENUE SUR HORATRACK"))
    }
}
