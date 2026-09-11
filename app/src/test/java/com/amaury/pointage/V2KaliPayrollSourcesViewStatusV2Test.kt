package com.amaury.pointage

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2KaliPayrollSourcesViewStatusV2Test {
    @Test
    fun `stockage fiable ne produit aucune alerte de corruption`() {
        assertNull(
            kaliStorageReliabilityText(
                family = "heures supplémentaires",
                reliable = true,
                warnings = emptyList()
            )
        )
    }

    @Test
    fun `stockage KALI corrompu est affiche comme corruption et pas comme absence`() {
        val text = kaliStorageReliabilityText(
            family = "heures supplémentaires",
            reliable = false,
            warnings = listOf(
                "KALI heures supplémentaires : historique local incohérent ; aucune règle ni absence ne peut être déduite."
            )
        ).orEmpty()

        assertTrue(text.contains("Stockage KALI heures supplémentaires incohérent", ignoreCase = true))
        assertTrue(text.contains("aucune règle enregistrée n'est considérée fiable", ignoreCase = true))
        assertTrue(text.contains("aucune règle ni absence", ignoreCase = true))
        assertTrue(!text.contains("Aucun barème d’heures supplémentaires KALI confirmé", ignoreCase = true))
    }

    @Test
    fun `alerte samedi dimanche reste commune et conserve le diagnostic source`() {
        val text = kaliStorageReliabilityText(
            family = "samedi/dimanche",
            reliable = false,
            warnings = listOf("KALI samedi/dimanche : historique local incohérent.")
        ).orEmpty()

        assertTrue(text.contains("samedi/dimanche", ignoreCase = true))
        assertTrue(text.contains("historique local incohérent", ignoreCase = true))
    }
}
