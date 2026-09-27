package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUserGuideCurrentV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/UserGuideDialog.kt").isFile }

    @Test
    fun `user guide describes current V2 behavior without legacy promises`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/UserGuideDialog.kt").readText()

        assertTrue(source.contains("NOTICE D'UTILISATION — AGKGMG"))
        assertTrue(source.contains("SAUVEGARDE & DONNÉES"))
        assertTrue(source.contains("POINTAGE & LIEUX / GPS"))
        assertTrue(source.contains("La présence dans une zone GPS est un indice de contexte"))
        assertTrue(source.contains("Une donnée absente ou non fiable reste à confirmer"))
        assertTrue(source.contains("les états GPS purement temporaires restent locaux au téléphone"))

        assertFalse(source.contains("Le temps de pause est retiré du temps réellement travaillé"))
        assertFalse(source.contains("crée un PDF de chaque journée"))
        assertFalse(source.contains("Synchroniser tout l'historique"))
        assertFalse(source.contains("HP Travail"))
        assertFalse(source.contains("HoraTrack"))
    }
}
