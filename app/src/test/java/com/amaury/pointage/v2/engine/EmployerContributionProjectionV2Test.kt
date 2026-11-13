package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.engine.EmployerContributionProjectionV2.Context
import com.amaury.pointage.v2.engine.EmployerContributionProjectionV2.Family
import com.amaury.pointage.v2.engine.EmployerContributionProjectionV2.State
import com.amaury.pointage.v2.engine.EmployerContributionProjectionV2.Line
import com.amaury.pointage.v2.engine.EmployerContributionProjectionV2.Reduction
import org.junit.Assert.*
import org.junit.Test

class EmployerContributionProjectionV2Test {
    // Shared contract fixtures V2, SHA256 8a93d44e10cb67d0e2be1f48d717e0b64a5d8af160310719f82cdff21e8a86f1
    @Test fun sharedCrossPlatformFixtures() {
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("known_two", 30003L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("known_two", 30003L, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("known_two", true, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.MISSING, null, null)), Reduction(State.MISSING, null, null))
            assertEquals("partial_known", 10001L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("partial_known", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("partial_known", true, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.MISSING, null, null), Line(Family.COMPLEMENTARY_RETIREMENT, State.MISSING, null, null)), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("none_known", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("none_known", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("none_known", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 0L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.MISSING, null, null)), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("confirmed_zero", 0L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("confirmed_zero", 0L, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("confirmed_zero", true, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.INVALID, null, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("invalid_supplied", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("invalid_supplied", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("invalid_supplied", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 30003L, "Synthetic confirmed reductions"))
            assertEquals("reduction_exact", 30003L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("reduction_exact", 0L, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("reduction_exact", true, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 30004L, "Synthetic confirmed reductions"))
            assertEquals("reduction_one_cent_over", 30003L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("reduction_one_cent_over", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("reduction_one_cent_over", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.MISSING, null, null))
            assertEquals("reduction_unknown", 30003L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("reduction_unknown", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("reduction_unknown", true, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.INVALID, -1L, "Synthetic confirmed reductions"))
            assertEquals("reduction_negative", 30003L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("reduction_negative", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("reduction_negative", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.NOT_APPLICABLE, null, "Synthetic dated non-applicability")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("nonapplicable_proven", 10001L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("nonapplicable_proven", 10001L, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("nonapplicable_proven", true, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.NOT_APPLICABLE, null, "")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("nonapplicable_unproven", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("nonapplicable_unproven", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("nonapplicable_unproven", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context(null, 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("company_absent", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("company_absent", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("company_absent", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("  ", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("company_blank", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("company_blank", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("company_blank", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 13, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("invalid_month", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("invalid_month", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("invalid_month", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, false),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("unreliable_gross", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("unreliable_gross", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("unreliable_gross", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, null, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("gross_absent", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("gross_absent", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("gross_absent", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 8000000000000000L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 2000000000000000L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("sum_overflow", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("sum_overflow", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("sum_overflow", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, -1L, "Synthetic confirmed fixture"), Line(Family.COMPLEMENTARY_RETIREMENT, State.KNOWN, 20002L, "Synthetic confirmed fixture")), Reduction(State.KNOWN, 0L, "Synthetic confirmed reductions"))
            assertEquals("negative_line", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("negative_line", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("negative_line", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.NOT_APPLICABLE, null, "Synthetic confirmed absence of reductions")), Reduction(State.MISSING, null, null))
            assertEquals("nonapplicable_only", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("nonapplicable_only", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("nonapplicable_only", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture")), Reduction(State.NOT_APPLICABLE, null, "Synthetic confirmed absence of reductions"))
            assertEquals("reduction_not_applicable_proven", 10001L, result.knownSubtotalBeforeReductionsCents)
            assertEquals("reduction_not_applicable_proven", 10001L, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("reduction_not_applicable_proven", true, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.MISSING, 10001L, null)), Reduction(State.MISSING, null, null))
            assertEquals("missing_with_amount", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("missing_with_amount", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("missing_with_amount", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "")), Reduction(State.MISSING, null, null))
            assertEquals("known_without_source", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("known_without_source", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("known_without_source", false, result.monetaryReliable)
        }
        run {
            val result = EmployerContributionProjectionV2.assemble(
                Context("fixture-company-A", 2026, 1, 250000L, true),
                listOf(Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture"), Line(Family.STATUTORY, State.KNOWN, 10001L, "Synthetic confirmed fixture")), Reduction(State.MISSING, null, null))
            assertEquals("duplicate_family", null, result.knownSubtotalBeforeReductionsCents)
            assertEquals("duplicate_family", null, result.knownSubtotalAfterReductionsCents)
            assertFalse(result.coverageComplete)
            assertEquals("duplicate_family", false, result.monetaryReliable)
        }
    }

    @Test fun invalidConversionsNeverBecomeZeroOrMissing() {
        for (amount in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            assertEquals(State.INVALID, EmployerContributionProjectionV2.reduction(amount, "fixture").state)
        }
        assertEquals(State.MISSING, EmployerContributionProjectionV2.reduction(null, "fixture").state)
        assertEquals(0L, EmployerContributionProjectionV2.reduction(0.0, "fixture").amountCents)
    }
}
