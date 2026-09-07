package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialMayFirstLegalRuleV2Test {
    private fun record(
        number: String? = "L3133-6",
        excerpt: String = "Dans les établissements et services qui, en raison de la nature de leur activité, ne peuvent interrompre le travail, les salariés occupés le 1er mai ont droit, en plus du salaire correspondant au travail accompli, à une indemnité égale au montant de ce salaire. Cette indemnité est à la charge de l'employeur."
    ) = LegalPayrollSourceStoreV2.Record(
        topic = OfficialLegalCodeSourceV2.Topic.PUBLIC_HOLIDAYS,
        articleId = "LEGIARTI000033020878",
        articleNumber = number,
        status = "VIGUEUR",
        excerpt = excerpt,
        effectiveFromMs = 1_470_787_200_000L,
        effectiveToMs = null,
        referenceAtMs = 1_788_134_400_000L,
        checkedAtMs = 1_788_134_401_000L
    )

    @Test
    fun `L3133-6 explicite produit uniquement la part additionnelle egale au salaire`() {
        val rule = OfficialMayFirstLegalRuleV2.parse(record())
        assertNotNull(rule)
        assertEquals(1.0, rule!!.extraMultiplier, 0.0001)
        assertEquals(2.0, rule.totalMultiplier, 0.0001)
    }

    @Test
    fun `mauvais numero d article est refuse meme avec un texte ressemblant`() {
        assertNull(OfficialMayFirstLegalRuleV2.parse(record(number = "L3133-5")))
    }

    @Test
    fun `maintien du salaire sans indemnite additionnelle ne devient pas un doublement`() {
        assertNull(
            OfficialMayFirstLegalRuleV2.parse(
                record(excerpt = "Le chômage du 1er mai ne peut être une cause de réduction de salaire. Une indemnité égale au salaire perdu est versée.")
            )
        )
    }

    @Test
    fun `indemnite sans egalite explicite avec le salaire travaille est refusee`() {
        assertNull(
            OfficialMayFirstLegalRuleV2.parse(
                record(excerpt = "Les salariés occupés le 1er mai reçoivent, en plus du salaire correspondant au travail accompli, une indemnité forfaitaire.")
            )
        )
    }
}
