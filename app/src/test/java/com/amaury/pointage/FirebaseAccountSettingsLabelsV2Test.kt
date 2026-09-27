package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAccountSettingsLabelsV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/FirebaseAccountActivity.kt").isFile }

    @Test
    fun `account backup actions explicitly identify cloud transport`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/FirebaseAccountActivity.kt").readText()
        assertTrue(source.contains("COMPTE GOOGLE & SAUVEGARDE CLOUD"))
        assertTrue(source.contains("SAUVEGARDER DANS LE CLOUD"))
        assertTrue(source.contains("RESTAURER DEPUIS LE CLOUD"))
        assertFalse(source.contains("SAUVEGARDER TOUT MAINTENANT"))
        assertFalse(source.contains("RESTAURER TOUT MAINTENANT"))
    }
}
