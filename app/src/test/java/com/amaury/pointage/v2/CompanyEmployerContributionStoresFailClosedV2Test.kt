package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyEmployerContributionStoresFailClosedV2Test {
    private val september = YearMonth.of(2026, 9)

    @Test
    fun `liste json vide reste un stockage valide`() {
        assertTrue(CompanyMobilityContributionStoreV2.decodeRecords("[]").reliable)
        assertTrue(CompanyHealthFamilyStoreV2.decodeRecords("[]").reliable)
        assertTrue(CompanyUnemploymentAgsStoreV2.decodeRecords("[]").reliable)
        assertTrue(CompanyWorkforceContributionStoreV2.decodeRecords("[]").reliable)
        assertTrue(CompanyApprenticeshipTaxStoreV2.decodeRecords("[]").reliable)
    }

    @Test
    fun `json illisible nest jamais transforme en absence de regle`() {
        assertFalse(CompanyMobilityContributionStoreV2.decodeRecords("{").reliable)
        assertFalse(CompanyHealthFamilyStoreV2.decodeRecords("not-json").reliable)
        assertFalse(CompanyUnemploymentAgsStoreV2.decodeRecords("").reliable)
        assertFalse(CompanyWorkforceContributionStoreV2.decodeRecords("null").reliable)
        assertFalse(CompanyApprenticeshipTaxStoreV2.decodeRecords(" ").reliable)
    }

    @Test
    fun `enregistrement partiellement invalide rend tout le stockage non fiable`() {
        val mobility = CompanyMobilityContributionStoreV2.decodeRecords(
            """[
                {"id":"vm-1","status":"APPLICABLE","rate":0.02,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"},
                {"id":"vm-broken","status":"APPLICABLE","rate":0.03,"effectiveFrom":"not-a-month","effectiveTo":null,"source":"Bulletin"}
            ]""".trimIndent()
        )
        val health = CompanyHealthFamilyStoreV2.decodeRecords(
            """[
                {"id":"hf-1","healthRate":0.13,"familyRate":0.0525,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"},
                {"id":"hf-broken","healthRate":0.13,"familyRate":0.0525,"effectiveFrom":"2026-01","effectiveTo":"bad-month","source":"Bulletin"}
            ]""".trimIndent()
        )

        assertFalse(mobility.reliable)
        assertTrue(mobility.records.size == 1)
        assertFalse(health.reliable)
        assertTrue(health.records.size == 1)
    }

    @Test
    fun `doublons didentifiant sont refuses`() {
        val workforce = CompanyWorkforceContributionStoreV2.decodeRecords(
            """[
                {"id":"size","band":"UNDER_11","effectiveFrom":"2026-01","effectiveTo":null,"source":"Source A"},
                {"id":"size","band":"FROM_11_TO_49","effectiveFrom":"2026-06","effectiveTo":null,"source":"Source B"}
            ]""".trimIndent()
        )

        assertFalse(workforce.reliable)
        assertTrue(workforce.records.size == 2)
    }

    @Test
    fun `stockage corrompu bloque chaque resolveur patronal`() {
        val mobility = CompanyMobilityContributionStoreV2.resolve(
            CompanyMobilityContributionStoreV2.ReadResult(emptyList(), false, listOf("corrompu")),
            september
        )
        val health = CompanyHealthFamilyStoreV2.resolve(
            CompanyHealthFamilyStoreV2.ReadResult(emptyList(), false, listOf("corrompu")),
            september
        )
        val unemployment = CompanyUnemploymentAgsStoreV2.resolve(
            CompanyUnemploymentAgsStoreV2.ReadResult(emptyList(), false, listOf("corrompu")),
            september
        )
        val workforce = CompanyWorkforceContributionStoreV2.resolve(
            CompanyWorkforceContributionStoreV2.ReadResult(emptyList(), false, listOf("corrompu")),
            september
        )
        val apprenticeship = CompanyApprenticeshipTaxStoreV2.resolve(
            CompanyApprenticeshipTaxStoreV2.ReadResult(emptyList(), false, listOf("corrompu")),
            september
        )

        assertFalse(mobility.reliable)
        assertNull(mobility.rate)
        assertFalse(health.reliable)
        assertNull(health.healthRate)
        assertFalse(unemployment.reliable)
        assertNull(unemployment.unemploymentRate)
        assertFalse(workforce.reliable)
        assertNull(workforce.band)
        assertFalse(apprenticeship.reliable)
        assertNull(apprenticeship.principalRate)
    }
}
