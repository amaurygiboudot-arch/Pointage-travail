package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPlayUpdateActionV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    @Test
    fun `public update action routes to Google Play and internal builds keep DEV checker`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").readText()
        assertTrue(source.contains("if (UpdateChecker.INTERNAL_APK_UPDATES_ENABLED) \"VÉRIFIER LES MISES À JOUR\" else \"OUVRIR GOOGLE PLAY\""))
        assertTrue(source.contains("market://details?id=$packageId"))
        assertTrue(source.contains("https://play.google.com/store/apps/details?id=$packageId"))
        assertTrue(source.contains("UpdateChecker.check("))
    }
}
