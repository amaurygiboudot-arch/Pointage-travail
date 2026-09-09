package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.CompanyBenefitInKindResolverV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyBenefitInKindStoreFailClosedV2Test {
    private val september = YearMonth.of(2026, 9)

    @Test
    fun `liste vide sans confirmation ne devient jamais zero fiable`() {
        val result = CompanyBenefitInKindStoreV2.resolve(
            records = CompanyBenefitInKindStoreV2.ReadResult(emptyList(), true, emptyList()),
            confirmations = CompanyBenefitInKindStoreV2.ConfirmationReadResult(emptyList(), true, emptyList()),
            period = september
        )

        assertEquals(0.0, result.totalGross, 0.001)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("non confirmée exhaustive") })
    }

    @Test
    fun `liste vide explicitement confirmee autorise zero`() {
        val result = CompanyBenefitInKindStoreV2.resolve(
            records = CompanyBenefitInKindStoreV2.ReadResult(emptyList(), true, emptyList()),
            confirmations = CompanyBenefitInKindStoreV2.ConfirmationReadResult(
                confirmations = listOf(
                    CompanyBenefitInKindStoreV2.MonthConfirmation(september, "Bulletin 09/2026")
                ),
                reliable = true,
                warnings = emptyList()
            ),
            period = september
        )

        assertEquals(0.0, result.totalGross, 0.001)
        assertTrue(result.reliable)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `montant present reste provisoire tant que la liste du mois nest pas confirmee`() {
        val record = CompanyBenefitInKindResolverV2.Record(
            id = "vehicle",
            label = "Véhicule",
            grossValue = 180.0,
            kind = CompanyBenefitInKindResolverV2.Kind.MONTHLY,
            effectiveFrom = YearMonth.of(2026, 1)
        )
        val result = CompanyBenefitInKindStoreV2.resolve(
            records = CompanyBenefitInKindStoreV2.ReadResult(listOf(record), true, emptyList()),
            confirmations = CompanyBenefitInKindStoreV2.ConfirmationReadResult(emptyList(), true, emptyList()),
            period = september
        )

        assertEquals(180.0, result.totalGross, 0.001)
        assertFalse(result.reliable)
    }

    @Test
    fun `montant et liste exhaustive confirmes deviennent fiables`() {
        val record = CompanyBenefitInKindResolverV2.Record(
            id = "vehicle",
            label = "Véhicule",
            grossValue = 180.0,
            kind = CompanyBenefitInKindResolverV2.Kind.MONTHLY,
            effectiveFrom = YearMonth.of(2026, 1)
        )
        val result = CompanyBenefitInKindStoreV2.resolve(
            records = CompanyBenefitInKindStoreV2.ReadResult(listOf(record), true, emptyList()),
            confirmations = CompanyBenefitInKindStoreV2.ConfirmationReadResult(
                listOf(CompanyBenefitInKindStoreV2.MonthConfirmation(september, "Bulletin 09/2026")),
                true,
                emptyList()
            ),
            period = september
        )

        assertEquals(180.0, result.totalGross, 0.001)
        assertTrue(result.reliable)
    }

    @Test
    fun `json avantages partiellement invalide bloque le stockage au lieu de disparaitre`() {
        val decoded = CompanyBenefitInKindStoreV2.decodeRecords(
            """[
                {"id":"vehicle","label":"Véhicule","grossValue":180.0,"kind":"MONTHLY","effectiveFrom":"2026-01","effectiveTo":null,"paymentMonth":null},
                {"id":"broken","label":"","grossValue":120.0,"kind":"MONTHLY","effectiveFrom":"not-a-month","effectiveTo":null,"paymentMonth":null}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(1, decoded.records.size)
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `doublon de confirmation mensuelle est incoherent`() {
        val decoded = CompanyBenefitInKindStoreV2.decodeConfirmations(
            """[
                {"period":"2026-09","source":"Bulletin"},
                {"period":"2026-09","source":"DSN"}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(2, decoded.confirmations.size)
    }

    @Test
    fun `confirmation avec source vide est refusee au decodage`() {
        val decoded = CompanyBenefitInKindStoreV2.decodeConfirmations(
            """[{"period":"2026-09","source":"   "}]"""
        )

        assertFalse(decoded.reliable)
        assertTrue(decoded.confirmations.isEmpty())
    }
}
