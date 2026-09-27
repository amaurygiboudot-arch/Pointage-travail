package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsManualUpdateAccessV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `Help exposes the existing safe update checker`() {
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")
        val menu = source("app/src/main/java/com/amaury/pointage/SettingsCompactMenuV2.kt")

        assertTrue(settings.contains("VÉRIFIER LES MISES À JOUR"))
        assertTrue(settings.contains("OUVRIR GOOGLE PLAY"))
        assertTrue(settings.contains("UpdateChecker.check("))
        assertTrue(settings.contains("askBeforeDownload = true"))
        assertTrue(settings.contains("market://details?id="))
        assertTrue(settings.contains("play.google.com/store/apps/details?id="))
        assertTrue(menu.substringAfter("Page.HELP -> setOf(").substringBefore(")").contains("SettingsV2Host.TAG_UPDATES"))
    }
}
