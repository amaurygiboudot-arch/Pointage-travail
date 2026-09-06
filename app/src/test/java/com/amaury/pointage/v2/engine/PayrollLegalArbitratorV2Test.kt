package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PayrollLegalArbitratorV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    private fun candidate(
        id: String,
        source: PayrollLegalArbitratorV2.Source,
        fingerprint: String,
        from: LocalDate? = LocalDate.of(2026, 1, 1),
        to: LocalDate? = null,
        equivalent: Boolean? = null,
        branchLock: Boolean? = null,
        verified: Boolean = true,
        scopeConfirmed: Boolean = true
    ) = PayrollLegalArbitratorV2.Candidate(
        id = id,
        source = source,
        effectiveFrom = from,
        effectiveTo = to,
        verified = verified,
        scopeConfirmed = scopeConfirmed,
        valueFingerprint = fingerprint,
        companyGuaranteesEquivalent = equivalent,
        branchLockConfirmed = branchLock
    )

    @Test
    fun `les sources BOCC et JORF restent des preuves de publication et ne pilotent jamais le calcul`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("bocc", PayrollLegalArbitratorV2.Source.BOCC, "p25"),
                candidate("jorf", PayrollLegalArbitratorV2.Source.JORF, "p25")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.OVERTIME_RATE_L3121_33_36
        )

        assertEquals(PayrollLegalArbitratorV2.State.NO_APPLICABLE_RULE, result.state)
        assertNull(result.selected)
        assertEquals(2, result.ignoredPublicationEvidence.size)
    }

    @Test
    fun `heures supplementaires accord entreprise avant branche puis loi`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("legi", PayrollLegalArbitratorV2.Source.LEGI, "25-50"),
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "20-30"),
                candidate("acco", PayrollLegalArbitratorV2.Source.ACCO, "15-25")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.OVERTIME_RATE_L3121_33_36
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.state)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selected?.source)
    }

    @Test
    fun `heures supplementaires branche retenue en absence d accord entreprise`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("legi", PayrollLegalArbitratorV2.Source.LEGI, "25-50"),
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "20-30")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.OVERTIME_RATE_L3121_33_36
        )

        assertEquals(PayrollLegalArbitratorV2.Source.KALI, result.selected?.source)
    }

    @Test
    fun `heures supplementaires loi retenue uniquement a defaut d accord`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(candidate("legi", PayrollLegalArbitratorV2.Source.LEGI, "25-50")),
            date,
            PayrollLegalArbitratorV2.Policy.OVERTIME_RATE_L3121_33_36
        )

        assertEquals(PayrollLegalArbitratorV2.Source.LEGI, result.selected?.source)
    }

    @Test
    fun `deux valeurs ACCO incompatibles bloquent l automatisme`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("acco-a", PayrollLegalArbitratorV2.Source.ACCO, "15"),
                candidate("acco-b", PayrollLegalArbitratorV2.Source.ACCO, "25"),
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "25")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.OVERTIME_RATE_L3121_33_36
        )

        assertEquals(PayrollLegalArbitratorV2.State.CONFLICT, result.state)
        assertNull(result.selected)
    }

    @Test
    fun `bloc L2253-1 exige controle d equivalence quand entreprise et branche different`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "coef-800"),
                candidate("acco", PayrollLegalArbitratorV2.Source.ACCO, "coef-local")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.BRANCH_BLOCK_L2253_1
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.state)
        assertNull(result.selected)
    }

    @Test
    fun `bloc L2253-1 accepte l accord entreprise si equivalence explicitement confirmee`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "min-1"),
                candidate("acco", PayrollLegalArbitratorV2.Source.ACCO, "min-2", equivalent = true)
            ),
            date,
            PayrollLegalArbitratorV2.Policy.BRANCH_BLOCK_L2253_1
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.state)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selected?.source)
    }

    @Test
    fun `bloc L2253-1 conserve la branche si equivalence entreprise refusee`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "min-1"),
                candidate("acco", PayrollLegalArbitratorV2.Source.ACCO, "min-2", equivalent = false)
            ),
            date,
            PayrollLegalArbitratorV2.Policy.BRANCH_BLOCK_L2253_1
        )

        assertEquals(PayrollLegalArbitratorV2.Source.KALI, result.selected?.source)
    }

    @Test
    fun `L2253-3 donne priorite a l accord entreprise`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "branch"),
                candidate("acco", PayrollLegalArbitratorV2.Source.ACCO, "company")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.ENTERPRISE_PREVAILS_L2253_3
        )

        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selected?.source)
    }

    @Test
    fun `une regle future ou non datee n est jamais appliquee`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("future", PayrollLegalArbitratorV2.Source.ACCO, "x", from = LocalDate.of(2026, 10, 1)),
                candidate("undated", PayrollLegalArbitratorV2.Source.KALI, "y", from = null),
                candidate("legi", PayrollLegalArbitratorV2.Source.LEGI, "z")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.OVERTIME_RATE_L3121_33_36
        )

        assertEquals(PayrollLegalArbitratorV2.Source.LEGI, result.selected?.source)
        assertTrue(result.considered.none { it.id == "future" || it.id == "undated" })
    }

    @Test
    fun `bloc L2253-2 demande confirmation du verrou de branche`() {
        val result = PayrollLegalArbitratorV2.resolve(
            listOf(
                candidate("kali", PayrollLegalArbitratorV2.Source.KALI, "danger", branchLock = null),
                candidate("acco", PayrollLegalArbitratorV2.Source.ACCO, "danger-local")
            ),
            date,
            PayrollLegalArbitratorV2.Policy.BRANCH_LOCK_L2253_2
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.state)
    }
}
