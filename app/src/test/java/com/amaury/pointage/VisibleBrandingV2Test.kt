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
    @Test
    fun `legacy product name is absent from shipped application sources`() {
        val roots = listOf(
            File(root, "app/src/main"),
            File(root, "ios/HPTravail/HPTravail"),
            File(root, "functions")
        )
        val legacy = Regex("""\b(?:HoraTrack|HORATRACK)\b""")
        val violations = roots
            .filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .filter { file ->
                file.extension.lowercase() in setOf(
                    "kt", "java", "xml", "txt", "tsv", "swift", "js", "json", "md"
                )
            }
            .mapNotNull { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                // Contrat technique historique du canal APK de développement :
                // le nom de l'artefact distant ne peut changer qu'avec une migration coordonnée.
                val searchable = text.replace("HoraTrack-dev.apk", "")
                if (legacy.containsMatchIn(searchable)) {
                    file.relativeTo(root).path
                } else {
                    null
                }
            }
            .distinct()
            .sorted()

        assertTrue(
            "Ancien nom produit encore présent dans les sources livrées : $violations",
            violations.isEmpty()
        )
    }

}
