package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialKaliProfileMatcherV2Test {
    private val classification = ConventionClassificationV2(coefficient = 910)

    private fun offset(raw: String, needle: String): Int {
        val normalized = OfficialKaliProfileMatcherV2.normalize(raw)
        return normalized.indexOf(needle).also { require(it >= 0) }
    }

    @Test
    fun `cible juste après classification exacte appartient au profil`() {
        val raw = "Cadres coefficient 910. Le capital deces est garanti."

        assertTrue(
            OfficialKaliProfileMatcherV2.nearestScopeMatches(
                rawText = raw,
                classification = classification,
                professionalStatus = "CADRE",
                targetOffset = offset(raw, "capital deces")
            )
        )
    }

    @Test
    fun `nouveau coefficient voisin avant la cible coupe la portée exacte`() {
        val raw = "Cadres coefficient 910. Capital deces garanti. Cadres coefficient 920. Incapacite temporaire garantie."

        assertFalse(
            OfficialKaliProfileMatcherV2.nearestScopeMatches(
                rawText = raw,
                classification = classification,
                professionalStatus = "CADRE",
                targetOffset = offset(raw, "incapacite temporaire")
            )
        )
    }

    @Test
    fun `coefficient voisin seul ne correspond jamais au profil`() {
        val raw = "Cadres coefficient 920. Le capital deces est garanti."

        assertFalse(
            OfficialKaliProfileMatcherV2.nearestScopeMatches(
                rawText = raw,
                classification = classification,
                professionalStatus = "CADRE",
                targetOffset = offset(raw, "capital deces")
            )
        )
    }

    @Test
    fun `coefficient et niveau de deux lignes voisines ne sont jamais combines`() {
        val raw = """
            Non-cadres coefficient 700 niveau III. Cotisation 0,40 %.
            Non-cadres coefficient 800 niveau IV. Cotisation 0,80 %.
        """.trimIndent()
        val impossible = ConventionClassificationV2(coefficient = 700, level = "IV")

        assertFalse(
            OfficialKaliProfileMatcherV2.nearestScopeMatches(
                rawText = raw,
                classification = impossible,
                professionalStatus = "NON_CADRE",
                targetOffset = offset(raw, "cotisation 0,80")
            )
        )
    }

    @Test
    fun `champs de la meme ligne restent combinables`() {
        val raw = "Non-cadres coefficient 700 niveau IV. Cotisation 0,40 %."
        val exact = ConventionClassificationV2(coefficient = 700, level = "IV")

        assertTrue(
            OfficialKaliProfileMatcherV2.nearestScopeMatches(
                rawText = raw,
                classification = exact,
                professionalStatus = "NON_CADRE",
                targetOffset = offset(raw, "cotisation 0,40")
            )
        )
    }

    @Test
    fun `clause statut cadre seule ne correspond jamais au non cadre`() {
        val raw = "Cadres : aucune garantie capital deces n'est prévue."

        assertTrue(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "CADRE"))
        assertFalse(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "NON_CADRE"))
    }

    @Test
    fun `clause statut non cadre seule ne correspond jamais au cadre`() {
        val raw = "Non-cadres : aucune garantie invalidite n'est prévue."

        assertFalse(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "CADRE"))
        assertTrue(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "NON_CADRE"))
    }

    @Test
    fun `clause sans statut reste générale`() {
        val raw = "Aucune garantie capital deces n'est prévue."

        assertTrue(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "CADRE"))
        assertTrue(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "NON_CADRE"))
    }

    @Test
    fun `clause mélangeant cadre et non cadre reste ambiguë`() {
        val raw = "Cadres et non-cadres : aucune garantie capital deces n'est prévue."

        assertFalse(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "CADRE"))
        assertFalse(OfficialKaliProfileMatcherV2.statusScopeMatches(raw, "NON_CADRE"))
    }
}
