package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialLegalCodeVerifierV2Test {
    private val topic = OfficialLegalCodeSourceV2.Topic.OVERTIME
    private val candidate = OfficialLegalCodeSourceV2.Candidate(
        articleId = "LEGIARTI000033020341",
        articleNumber = "L3121-36",
        title = "Article L3121-36",
        snippet = null
    )

    @Test
    fun `accepte un article en vigueur couvrant la date de paie`() {
        val article = OfficialLegalCodeSourceV2.Article(
            articleId = candidate.articleId,
            articleNumber = "L3121-36",
            status = "VIGUEUR",
            content = "A défaut d'accord, les heures supplémentaires sont majorées.",
            effectiveFrom = "1470787200000",
            effectiveTo = "32472144000000"
        )

        val verified = OfficialLegalCodeVerifierV2.validate(
            topic,
            candidate,
            article,
            atMs = 1788566400000,
            checkedAtMs = 1788600000000
        )

        assertNotNull(verified)
        assertEquals(candidate.articleId, verified!!.articleId)
        assertEquals("L3121-36", verified.articleNumber)
        assertEquals(topic, verified.topic)
        assertEquals(1788566400000, verified.referenceAtMs)
    }

    @Test
    fun `rejette un identifiant article different`() {
        val article = OfficialLegalCodeSourceV2.Article(
            articleId = "LEGIARTI000038610166",
            articleNumber = "L3121-33",
            status = "VIGUEUR",
            content = "Texte",
            effectiveFrom = "1470787200000",
            effectiveTo = null
        )

        assertNull(OfficialLegalCodeVerifierV2.validate(topic, candidate, article, 1788566400000, 1L))
    }

    @Test
    fun `rejette un article abroge ou hors periode`() {
        val abrogated = OfficialLegalCodeSourceV2.Article(
            candidate.articleId,
            "L3121-36",
            "ABROGE",
            "Texte",
            "1470787200000",
            null
        )
        val future = OfficialLegalCodeSourceV2.Article(
            candidate.articleId,
            "L3121-36",
            "VIGUEUR",
            "Texte",
            "1893456000000",
            null
        )

        assertNull(OfficialLegalCodeVerifierV2.validate(topic, candidate, abrogated, 1788566400000, 1L))
        assertNull(OfficialLegalCodeVerifierV2.validate(topic, candidate, future, 1788566400000, 1L))
    }

    @Test
    fun `accepte aussi les dates ISO officielles`() {
        val article = OfficialLegalCodeSourceV2.Article(
            candidate.articleId,
            "L3121-36",
            "VIGUEUR",
            "Texte juridique vérifié",
            "2016-08-10",
            "2999-01-01"
        )

        assertNotNull(OfficialLegalCodeVerifierV2.validate(topic, candidate, article, 1788566400000, 2L))
    }
}
