package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialApecDecisionIndexV2Test {
    @Test
    fun `IDCC exact retourne tous les PDF officiels de sa ligne sans choisir`() {
        val html = """
            <table><tbody>
              <tr>
                <td>Plasturgie</td><td>0292</td><td>Cadres 900 à 940</td>
                <td><a href="/assets/files/CP-Apec-Agrement-plasturgie-09102024.pdf">Agrément</a></td>
                <td><a href='/assets/files/ancien-plasturgie.pdf'>Historique 1</a></td>
              </tr>
              <tr>
                <td>Autre branche</td><td>2920</td>
                <td><a href="/assets/files/autre.pdf">Agrément</a></td>
              </tr>
            </tbody></table>
        """.trimIndent()

        val diagnostic = OfficialApecDecisionIndexV2.parse(html, "292")

        assertEquals(2, diagnostic.candidates.size)
        assertTrue(diagnostic.candidates.all { it.idcc == "292" })
        assertTrue(diagnostic.candidates.all { it.sourceUrl.startsWith("https://commission-paritaire.apec.fr/assets/files/") })
        assertTrue(diagnostic.candidates.any { it.linkLabel == "Historique 1" })
        assertTrue(diagnostic.reasons.any { it.contains("aucune priorité", ignoreCase = true) })
    }

    @Test
    fun `IDCC voisin ne fuit jamais dans le résultat`() {
        val html = """
            <table><tbody>
              <tr><td>Branche 2920</td><td>2920</td><td><a href="/assets/files/2920.pdf">Agrément</a></td></tr>
              <tr><td>Branche 29</td><td>29</td><td><a href="/assets/files/29.pdf">Agrément</a></td></tr>
            </tbody></table>
        """.trimIndent()

        val diagnostic = OfficialApecDecisionIndexV2.parse(html, "292")

        assertTrue(diagnostic.candidates.isEmpty())
    }

    @Test
    fun `plusieurs lignes du même IDCC sont toutes conservées`() {
        val html = """
            <table><tbody>
              <tr><td>Champ national</td><td>493</td><td><a href="/assets/files/national.pdf">Agrément</a></td></tr>
              <tr><td>Vins de Champagne</td><td>0493</td><td><a href="/assets/files/champagne.pdf">Agrément</a></td></tr>
            </tbody></table>
        """.trimIndent()

        val diagnostic = OfficialApecDecisionIndexV2.parse(html, "0493")

        assertEquals(2, diagnostic.candidates.size)
        assertTrue(diagnostic.reasons.any { it.contains("2 lignes") })
    }

    @Test
    fun `liens externes non PDF et chemins hors assets sont rejetés`() {
        val html = """
            <table><tbody><tr>
              <td>Plasturgie</td><td>292</td>
              <td><a href="https://evil.example/decision.pdf">Externe</a></td>
              <td><a href="https://commission-paritaire.apec.fr/autre/decision.pdf">Hors assets</a></td>
              <td><a href="/assets/files/page.html">HTML</a></td>
              <td><a href="/assets/files/bonne.pdf?download=1">Bonne</a></td>
            </tr></tbody></table>
        """.trimIndent()

        val diagnostic = OfficialApecDecisionIndexV2.parse(html, "292")

        assertEquals(1, diagnostic.candidates.size)
        assertTrue(diagnostic.candidates.single().sourceUrl.contains("bonne.pdf"))
    }

    @Test
    fun `texte seul de la ligne ne vaut jamais agrément`() {
        val html = """
            <table><tbody><tr><td>Plasturgie</td><td>292</td><td>Cadres 900 à 940</td></tr></tbody></table>
        """.trimIndent()

        val diagnostic = OfficialApecDecisionIndexV2.parse(html, "292")

        assertTrue(diagnostic.candidates.isEmpty())
        assertTrue(diagnostic.reasons.any { it.contains("aucun PDF officiel", ignoreCase = true) })
    }
}
