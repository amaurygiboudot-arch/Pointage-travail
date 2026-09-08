package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialKaliAniScopeMatcherV2Test {
    @Test
    fun `clause sans ANI explicite reste generale`() {
        val text = "La cotisation de prévoyance est fixée à 0,80 %."

        assertTrue(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.ARTICLE_2_1))
        assertTrue(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2))
    }

    @Test
    fun `article 2_2 ne correspond jamais au salarie hors 2_1 2_2`() {
        val text = "Salariés relevant de l'article 2.2 : aucune cotisation de prévoyance n'est due."

        assertTrue(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.ARTICLE_2_2))
        assertFalse(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2))
    }

    @Test
    fun `hors 2_1 2_2 est reconnu avant les references internes aux articles`() {
        val text = "Salariés ne relevant pas des articles 2.1 et 2.2 : régime de prévoyance spécifique."

        assertTrue(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2))
        assertFalse(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.ARTICLE_2_1))
        assertFalse(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.ARTICLE_2_2))
    }

    @Test
    fun `extension regime cadres prime sur hors ANI generique`() {
        val text = "Les non-cadres ne relevant pas des articles 2.1 et 2.2 bénéficient de l'extension du régime de prévoyance des cadres."

        assertTrue(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE))
        assertFalse(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2))
    }

    @Test
    fun `deux populations ANI positives dans la meme clause restent ambigues`() {
        val text = "Les salariés relevant de l'article 2.1 et de l'article 2.2 sont visés."

        assertFalse(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.ARTICLE_2_1))
        assertFalse(OfficialKaliAniScopeMatcherV2.matches(text, ProtectionCategoryV2.AniCategory.ARTICLE_2_2))
    }
}
