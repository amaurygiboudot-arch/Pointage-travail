package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVisibleProductNameV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/UserGuideDialog.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `settings help and security use AGKGMG visible name`() {
        val guide = source("app/src/main/java/com/amaury/pointage/UserGuideDialog.kt")
        val security = source("app/src/main/java/com/amaury/pointage/SecurityInfoActivity.kt")
        val main = source("app/src/main/java/com/amaury/pointage/MainActivity.kt")
        assertTrue(guide.contains("AGKGMG"))
        assertFalse(guide.contains("HP Travail"))
        assertFalse(guide.contains("HP TRAVAIL"))
        assertFalse(security.contains("HP Travail"))
        assertFalse(main.contains("quand HoraTrack est fermé"))
        assertTrue(main.contains("quand AGKGMG est fermé"))
    }
}
