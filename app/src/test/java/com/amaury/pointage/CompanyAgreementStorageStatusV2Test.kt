package com.amaury.pointage

import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import com.amaury.pointage.v2.CompanyAgreementStoreV2
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementStorageStatusV2Test {
    @Test
    fun `metadonnees fiables sans reparation ne produisent aucun avertissement`() {
        val stored = CompanyAgreementStoreV2.ReadResult(emptyList(), true, emptyList())

        assertNull(companyAgreementMetadataStorageStatusText(stored))
    }

    @Test
    fun `metadonnees corrompues sont affichees comme corruption et non absence`() {
        val text = requireNotNull(
            companyAgreementMetadataStorageStatusText(
                CompanyAgreementStoreV2.ReadResult(
                    agreements = emptyList(),
                    reliable = false,
                    warnings = listOf("diagnostic ACCO")
                )
            )
        )

        assertTrue(text.contains("incohérent"))
        assertTrue(text.contains("aucun accord partiellement récupéré"))
        assertTrue(text.contains("diagnostic ACCO"))
    }

    @Test
    fun `restauration des metadonnees ACCO est visible`() {
        val text = requireNotNull(
            companyAgreementMetadataStorageStatusText(
                CompanyAgreementStoreV2.ReadResult(
                    agreements = emptyList(),
                    reliable = true,
                    warnings = listOf("restauré"),
                    repairedFromBackup = true
                )
            )
        )

        assertTrue(text.contains("restaurés automatiquement"))
    }

    @Test
    fun `regles corrompues ne sont jamais presentees comme une liste normale`() {
        val text = requireNotNull(
            companyAgreementRuleStorageStatusText(
                CompanyAgreementRuleStoreV2.ReadResult(
                    records = emptyList(),
                    reliable = false,
                    warnings = listOf("règles ACCO cassées")
                )
            )
        )

        assertTrue(text.contains("incohérent"))
        assertTrue(text.contains("ni n'utilise"))
        assertTrue(text.contains("règles ACCO cassées"))
    }

    @Test
    fun `recherche avec accords trouves mais non sauvegardes nest jamais annoncee comme reussie`() {
        val text = companyAgreementSearchOutcomeText(foundCount = 2, rejectedCount = 1, persisted = false)

        assertTrue(text.contains("n'a pas pu les enregistrer"))
        assertTrue(text.contains("À confirmer"))
    }
}
