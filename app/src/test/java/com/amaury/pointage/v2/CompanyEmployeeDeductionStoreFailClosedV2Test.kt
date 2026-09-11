package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.CompanyEmployeeDeductionResolverV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyEmployeeDeductionStoreFailClosedV2Test {
    private val period = YearMonth.of(2026, 9)

    @Test
    fun `entreprise absente rend les retenues non fiables`() {
        val unavailable = CompanyEmployeeDeductionStoreV2.companyUnavailableResult()

        assertFalse(unavailable.reliable)
        assertTrue(unavailable.records.isEmpty())
        assertTrue(unavailable.warnings.isNotEmpty())
    }

    @Test
    fun `entreprise absente interdit aussi le fallback legacy des retenues`() {
        val blocked = CompanyEmployeeDeductionStoreV2.resolve(
            CompanyEmployeeDeductionStoreV2.companyUnavailableResult(),
            period
        )
        val legacy = CompanyEmployeeDeductionResolverV2.withLegacyFallback(
            blocked,
            mapOf(CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE to 42.0)
        )

        CompanyEmployeeDeductionResolverV2.Kind.entries.forEach { kind ->
            val value = legacy[kind]
            assertNull(value.amount)
            assertTrue(value.hasDatedRecords)
            assertFalse(value.reliable)
            assertFalse(value.legacyUsed)
        }
    }

    @Test
    fun `json partiellement invalide bloque le store au lieu de supprimer silencieusement la ligne`() {
        val decoded = CompanyEmployeeDeductionStoreV2.decode(
            """[
                {"id":"mutual","kind":"MUTUAL_EMPLOYEE","amount":42.0,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"},
                {"id":"broken","kind":"PROVIDENT_EMPLOYEE","amount":12.0,"effectiveFrom":"not-a-month","effectiveTo":null,"source":"Bulletin"}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(1, decoded.records.size)
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `stockage incoherent bloque tous les types et interdit le fallback legacy`() {
        val blocked = CompanyEmployeeDeductionStoreV2.resolve(
            CompanyEmployeeDeductionStoreV2.ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("stockage incohérent")
            ),
            period
        )
        val legacy = CompanyEmployeeDeductionResolverV2.withLegacyFallback(
            blocked,
            mapOf(CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE to 42.0)
        )

        val mutual = legacy[CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE]
        assertNull(mutual.amount)
        assertTrue(mutual.hasDatedRecords)
        assertFalse(mutual.reliable)
        assertFalse(mutual.legacyUsed)
    }

    @Test
    fun `store vide sain conserve le comportement de migration legacy`() {
        val resolved = CompanyEmployeeDeductionStoreV2.resolve(
            CompanyEmployeeDeductionStoreV2.ReadResult(emptyList(), true, emptyList()),
            period
        )
        val legacy = CompanyEmployeeDeductionResolverV2.withLegacyFallback(
            resolved,
            mapOf(CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE to 42.0)
        )

        val mutual = legacy[CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE]
        assertEquals(42.0, mutual.amount!!, 0.001)
        assertTrue(mutual.legacyUsed)
        assertFalse(mutual.reliable)
    }

    @Test
    fun `doublon identifiant rend le store incoherent`() {
        val decoded = CompanyEmployeeDeductionStoreV2.decode(
            """[
                {"id":"same","kind":"MUTUAL_EMPLOYEE","amount":42.0,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"},
                {"id":"same","kind":"TRANSPORT_EMPLOYEE","amount":8.0,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(2, decoded.records.size)
    }

    @Test
    fun `source vide ou montant negatif sont refuses au decodage`() {
        val blankSource = CompanyEmployeeDeductionStoreV2.decode(
            """[{"id":"a","kind":"MUTUAL_EMPLOYEE","amount":42.0,"effectiveFrom":"2026-01","effectiveTo":null,"source":"   "}]"""
        )
        val negative = CompanyEmployeeDeductionStoreV2.decode(
            """[{"id":"b","kind":"MUTUAL_EMPLOYEE","amount":-1.0,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"}]"""
        )

        assertFalse(blankSource.reliable)
        assertTrue(blankSource.records.isEmpty())
        assertFalse(negative.reliable)
        assertTrue(negative.records.isEmpty())
    }
}
