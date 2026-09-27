package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsLegacyEnterpriseLookupRemovalV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/ThemedBackgroundScrollView.kt").isFile }

    @Test
    fun `legacy enterprise settings view stays removed`() {
        assertFalse(File(root, "app/src/main/java/com/amaury/pointage/EnterpriseLookupView.kt").exists())
        val themed = File(root, "app/src/main/java/com/amaury/pointage/ThemedBackgroundScrollView.kt").readText()
        assertFalse(themed.contains("EnterpriseLookupView"))
    }
}
