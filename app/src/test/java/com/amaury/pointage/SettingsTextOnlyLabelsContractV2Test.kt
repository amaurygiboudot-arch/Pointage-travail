package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTextOnlyLabelsContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/SettingsCompactMenuV2.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `settings navigation and public labels stay text only`() {
        val menu = source("app/src/main/java/com/amaury/pointage/SettingsCompactMenuV2.kt")
        val security = source("app/src/main/java/com/amaury/pointage/SecurityUiInitProvider.kt")
        val relief = source("app/src/main/java/com/amaury/pointage/ButtonReliefInstaller.kt")
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")

        listOf(
            "COMPTE & SÉCURITÉ",
            "POINTAGE & LIEUX",
            "PERSONNALISATION",
            "SAUVEGARDE & DONNÉES",
            "AIDE"
        ).forEach { label -> assertTrue(menu.contains("\"$label\"")) }

        assertTrue(menu.contains("AGKGMG — version"))
        assertFalse(security.contains("🛡 VÉRIFIER LA SÉCURITÉ"))
        assertFalse(relief.contains("💎 LABORATOIRE DIAMANT"))
        assertTrue(settings.contains("styledButton(activity, \"NOTICE D'UTILISATION\")"))
        assertFalse(settings.contains("📖 NOTICE D'UTILISATION"))
    }
}
