package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.amaury.pointage.v2.SegmentedPayslipComparisonValuesV2

class SegmentedSalaryCanonicalOutputV2Test {
    @Test
    fun `sortie riche conserve temps brut cash et net sans recalcul`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val net = net(cash, complete = true)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, net)

        assertTrue(result.paidTimeReliable)
        assertTrue(result.premiumTimeReliable)
        assertTrue(result.workedGrossReliable)
        assertTrue(result.cashGrossReliable)
        assertTrue(result.netBeforeIncomeTaxComplete)
        assertEquals(2_400, result.paidMinutes)
        assertEquals(300, result.variableOvertimeMinutes)
        assertEquals(0, result.complementaryMinutes)
        assertEquals(120, result.nightMinutes)
        assertEquals(1_000.0, result.workedGross!!, 0.0001)
        assertEquals(1_100.0, result.cashGross!!, 0.0001)
        assertEquals(900.0, result.netBeforeIncomeTax!!, 0.0001)

        val comparison = SegmentedPayslipComparisonValuesV2.expected(result)
        assertTrue(comparison != null)
        assertEquals(50.0, comparison!![PayslipDocumentParserV2.KEY_OVERTIME_GROSS]!!, 0.0001)
        assertEquals(25.0, comparison[PayslipDocumentParserV2.KEY_PREMIUMS_GROSS]!!, 0.0001)
        assertEquals(1_100.0, comparison[PayslipDocumentParserV2.KEY_GROSS]!!, 0.0001)
        assertEquals(900.0, comparison[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX]!!, 0.0001)
        assertEquals(950.0, comparison[PayslipDocumentParserV2.KEY_NET_TAXABLE]!!, 0.0001)

        val pdfMoney = SalaryExamplePdfV2.estimatedGrossLines(result).toMap()
        assertEquals("1100,00 €", pdfMoney["Brut social estimé AGKGMG hors paniers"])
        assertEquals("50,00 €", pdfMoney["Majoration heures supplémentaires"])
        assertEquals("900,00 €", pdfMoney["Net estimé avant impôt"])
        assertEquals("950,00 €", pdfMoney["Net imposable estimé"])

        val pdfTime = SalaryExamplePdfV2.timeSectionValues(result)
        assertEquals("1", pdfTime.completedSessions)
        assertEquals("40h00", pdfTime.paidTime)
        assertEquals("À confirmer", pdfTime.regularHours)
        assertEquals("À confirmer", pdfTime.overtimeHours)
        assertEquals("À confirmer", pdfTime.unpaidPauses)
    }

    @Test
    fun `net incomplet ne masque pas un brut ni un temps prouves`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val net = net(cash, complete = false)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, net)

        assertTrue(result.paidTimeReliable)
        assertTrue(result.workedGrossReliable)
        assertTrue(result.cashGrossReliable)
        assertFalse(result.netBeforeIncomeTaxComplete)
        assertEquals(1_100.0, result.cashGross!!, 0.0001)
        assertNull(result.netBeforeIncomeTax)

        val comparison = SegmentedPayslipComparisonValuesV2.expected(result)
        assertTrue(comparison != null)
        assertEquals(1_100.0, comparison!![PayslipDocumentParserV2.KEY_GROSS]!!, 0.0001)
        assertFalse(comparison.containsKey(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX))
        assertFalse(comparison.containsKey(PayslipDocumentParserV2.KEY_NET_TAXABLE))

        val pdfMoney = SalaryExamplePdfV2.estimatedGrossLines(result).toMap()
        assertEquals("1100,00 €", pdfMoney["Brut social estimé AGKGMG hors paniers"])
        assertEquals("À confirmer", pdfMoney["Net estimé avant impôt"])
        assertEquals("À confirmer", pdfMoney["Net imposable estimé"])
        assertEquals("À confirmer", pdfMoney["Paniers hors brut"])
    }

    @Test
    fun `chaine cash differente est bloquee sans faux montant final`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val otherCash = cash(worked, 1_200.0)
        val net = net(otherCash, complete = true)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, net)

        assertTrue(result.workedGrossReliable)
        assertEquals(1_000.0, result.workedGross!!, 0.0001)
        assertFalse(result.cashGrossReliable)
        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.cashGross)
        assertTrue(result.warnings.contains(SegmentedSalaryCanonicalOutputAssemblerV2.CHAIN_WARNING))
    }

    @Test
    fun `projection nette d un autre brut reste bloquee`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val otherCash = cash(worked, 1_200.0)
        val projectionFromOtherCash = net(otherCash, complete = true).projection
        val inconsistent = net(cash, complete = true).copy(projection = projectionFromOtherCash)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, inconsistent)

        assertTrue(result.cashGrossReliable)
        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.netBeforeIncomeTax)
        assertNull(result.netTaxable)
        assertTrue(result.warnings.contains(SegmentedSalaryCanonicalOutputAssemblerV2.NET_PROOF_WARNING))
    }

    @Test
    fun `drapeau brut non fiable bloque le net meme si la projection est complete`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val inconsistent = net(cash, complete = true).copy(cashGrossReliable = false)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, inconsistent)

        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.netBeforeIncomeTax)
        assertTrue(result.warnings.contains(SegmentedSalaryCanonicalOutputAssemblerV2.NET_PROOF_WARNING))
    }

    @Test
    fun `brut social avec avantages reste rattache au cash de la chaine`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val original = net(cash, complete = true)
        val projection = original.projection!!
        val withBenefits = original.copy(projection = projection.copy(payroll =
            projection.payroll.copy(gross = 1_200.0, benefitsInKindDeduction = 100.0)))

        val actual = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, withBenefits)

        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertEquals(900.0, actual.netBeforeIncomeTax!!, 0.0)
    }

    @Test
    fun `demi centime de cash utilise la meme normalisation que la projection`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 3_000.005)
        val original = net(cash, complete = true)
        val projection = original.projection!!
        val rounded = original.copy(projection = projection.copy(payroll =
            projection.payroll.copy(gross = 3_000.01)))

        val actual = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, rounded)

        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertEquals(900.0, actual.netBeforeIncomeTax!!, 0.0)
    }

    @Test
    fun `avantages ne permettent pas de masquer une divergence du brut cash`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val original = net(cash, complete = true)
        val projection = original.projection!!
        val otherCash = original.copy(projection = projection.copy(payroll =
            projection.payroll.copy(gross = 1_210.0, benefitsInKindDeduction = 100.0)))

        val actual = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, otherCash)

        assertFalse(actual.netBeforeIncomeTaxComplete)
        assertNull(actual.netBeforeIncomeTax)
        assertTrue(actual.warnings.contains(SegmentedSalaryCanonicalOutputAssemblerV2.NET_PROOF_WARNING))
    }

    @Test fun `employer projection follows exact company month and cash chain through pdf`() {
        val first = java.time.LocalDate.of(2026, 1, 1).toEpochDay()
        val originalWorked = fixtureWorked()
        val worked = originalWorked.copy(
            evidence = originalWorked.evidence.copy(slices = originalWorked.evidence.slices.map {
                it.copy(startEpochDay = first, endEpochDay = first + 6)
            }),
            variables = originalWorked.variables.copy(pieces = listOf(
                SegmentedWorkedVariableGrossPieceV2("company", "c1", first, first + 6, 75.0, true))))
        val cash = cash(worked, 1100.0)
        val originalNet = net(cash, complete = true)
        fun result(context: EmployerContributionProjectionV2.Context): SegmentedSalaryCanonicalOutputV2 {
            val employer = EmployerContributionProjectionV2.assemble(context,
                listOf(EmployerContributionProjectionV2.Line(EmployerContributionProjectionV2.Family.STATUTORY,
                    EmployerContributionProjectionV2.State.KNOWN, 10000, "Fixture")),
                EmployerContributionProjectionV2.Reduction(EmployerContributionProjectionV2.State.KNOWN, 0, "Fixture"))
            val employee = originalNet.projection!!
            return SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash,
                originalNet.copy(projection = employee.copy(payroll = employee.payroll.copy(employerProjection = employer))))
        }
        val context = EmployerContributionProjectionV2.Context("company", 2026, 1, 110000, true)
        val valid = result(context)
        assertEquals(10000L, valid.employerProjection!!.knownSubtotalBeforeReductionsCents)
        assertEquals("100,00 €", SalaryExamplePdfV2.estimatedGrossLines(valid).toMap()["Sous-total patronal connu après réductions"])
        val selected = SalaryExamplePdfV2.canonicalForSelection(valid, "company", 2026, 1)!!
        assertEquals(valid, selected)
        assertEquals("100,00 €", SalaryExamplePdfV2.estimatedGrossLines(selected).toMap()["Sous-total patronal connu après réductions"])
        for (selection in listOf(Triple("other-company", 2026, 1), Triple("company", 2026, 2), Triple("company", 2027, 1))) {
            val filtered = SalaryExamplePdfV2.canonicalForSelection(valid, selection.first, selection.second, selection.third)!!
            assertNull(filtered.employerProjection)
            assertEquals(valid.netBeforeIncomeTax, filtered.netBeforeIncomeTax)
            assertEquals(valid.netAfterIncomeTax, filtered.netAfterIncomeTax)
            assertEquals(valid.warnings, filtered.warnings)
            val summary = SalaryExamplePdfV2.estimatedGrossLines(filtered).toMap()
            assertEquals("À confirmer", summary["Sous-total patronal connu après réductions"])
            assertTrue(summary["Informations patronales"]!!.contains("non affichable"))
            val details = SalaryExamplePdfV2.contributionLines(filtered.net.projection?.payroll,
                filtered.employerProjection, filtered.employerWarnings).toMap()
            assertEquals("À confirmer", details["Sous-total patronal connu avant réductions"])
            assertTrue(details["Informations patronales"]!!.contains("non affichable"))
        }
        for (invalid in listOf(context.copy(companyId = "other-company"), context.copy(month = 2),
            context.copy(year = Int.MAX_VALUE), context.copy(contributionGrossCents = 110001))) {
            val rejected = result(invalid)
            assertNull(rejected.employerProjection)
            assertEquals(valid.netBeforeIncomeTax, rejected.netBeforeIncomeTax)
            assertEquals(valid.netAfterIncomeTax, rejected.netAfterIncomeTax)
            assertEquals(valid.warnings, rejected.warnings)
            assertEquals(valid.netBeforeIncomeTaxComplete, rejected.netBeforeIncomeTaxComplete)
            assertTrue(rejected.employerWarnings.contains(SegmentedSalaryCanonicalOutputAssemblerV2.EMPLOYER_CONTEXT_WARNING))
            assertTrue(SalaryExamplePdfV2.estimatedGrossLines(rejected).toMap()["Informations patronales"]!!.contains("non affichable"))
            assertEquals("À confirmer", SalaryExamplePdfV2.estimatedGrossLines(rejected).toMap()["Sous-total patronal connu après réductions"])
            val details = SalaryExamplePdfV2.contributionLines(rejected.net.projection?.payroll, rejected.employerProjection).toMap()
            assertEquals("À confirmer", details["Sous-total patronal connu avant réductions"])
        }
        val mismatchedCash = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash(worked, 1200.0), valid.net)
        assertNull(mismatchedCash.employerProjection)

        val whole = worked.evidence.slices.single()
        val transition = worked.copy(evidence = worked.evidence.copy(slices = listOf(
            whole.copy(endEpochDay = first + 2),
            whole.copy(startEpochDay = first + 3, ruleVersionId = "r2", weeks = emptyList()))))
        val transitionResult = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(transition, cash, valid.net)
        assertEquals(10000L, transitionResult.employerProjection!!.knownSubtotalBeforeReductionsCents)
        for (slices in listOf(
            listOf(whole.copy(contractVersionId = "other-contract")),
            listOf(whole.copy(endEpochDay = first + 2), whole.copy(startEpochDay = first + 4, weeks = emptyList())),
            listOf(whole, whole.copy(weeks = emptyList())),
            listOf(whole.copy(startEpochDay = first - 1))
        )) {
            val inconsistentWorked = worked.copy(evidence = worked.evidence.copy(slices = slices))
            val rejected = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(inconsistentWorked, cash, valid.net)
            assertNull(rejected.employerProjection)
            assertTrue(rejected.employerWarnings.contains(SegmentedSalaryCanonicalOutputAssemblerV2.EMPLOYER_CONTEXT_WARNING))
        }
        val invalidPaid = worked.copy(evidence = worked.evidence.copy(slices = listOf(whole.copy(weeks = whole.weeks + whole.weeks))))
        assertNull(SegmentedSalaryCanonicalOutputAssemblerV2.assemble(invalidPaid, cash, valid.net).employerProjection)
        val unreliableGrossNet = valid.net.copy(projection = valid.net.projection!!.copy(payroll = valid.net.projection!!.payroll.copy(grossReliable = false)))
        assertNull(SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, unreliableGrossNet).employerProjection)
    }

    private fun fixtureWorked(): SegmentedWorkedGrossProductionResultV2 {
        val week = SegmentedPayrollWeekEvidenceV2(
            weekYear = 2026,
            weekOfYear = 40,
            week = PayrollWeekV2(
                paidMinutes = 2_400,
                nightMinutes = 120,
                saturdayMinutes = 60,
                sundayMinutes = 0,
                publicHolidayMinutes = 0
            ),
            fullWeekContextReliable = true
        )
        val slice = SegmentedPayrollSliceEvidenceV2(
            startEpochDay = 1,
            endEpochDay = 7,
            contractVersionId = "c1",
            ruleVersionId = "r1",
            weeks = listOf(week),
            paidTimeReliable = true,
            premiumTimeBreakdownReliable = true,
            payrollRulesReliable = true
        )
        val evidence = SegmentedPayrollSessionEvidenceResultV2(
            slices = listOf(slice),
            reliable = true,
            warnings = emptyList(),
            sourceId = "source",
            contributingSessionIds = listOf("s1")
        )
        val variables = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = emptyList(),
            reliable = true,
            warnings = emptyList(),
            breakdowns = listOf(
                SegmentedWorkedVariableGrossBreakdownV2(
                    employerId = "company",
                    versionId = "c1",
                    startEpochDay = 1,
                    endEpochDay = 7,
                    overtimeGross = 50.0,
                    complementaryGross = 0.0,
                    premiumGross = 25.0,
                    variableOvertimeMinutes = 300,
                    complementaryMinutes = 0
                )
            )
        )
        val base = SegmentedMonthlyBaseResultV2(
            pieces = emptyList(),
            baseGross = 925.0,
            reliable = true,
            warnings = emptyList()
        )
        val assembly = SegmentedWorkedGrossAssemblyResultV2(
            baseGross = 925.0,
            variableGross = 75.0,
            workedGross = 1_000.0,
            reliable = true,
            warnings = emptyList()
        )
        return SegmentedWorkedGrossProductionResultV2(evidence, variables, base, assembly)
    }

    private fun cash(
        worked: SegmentedWorkedGrossProductionResultV2,
        amount: Double
    ) = SegmentedCashGrossAssemblyResultV2(
        workedGross = worked.workedGross,
        additionalCashGross = amount - (worked.workedGross ?: 0.0),
        cashGross = amount,
        reliable = true,
        warnings = emptyList()
    )

    private fun net(
        cash: SegmentedCashGrossAssemblyResultV2,
        complete: Boolean
    ): SegmentedCashGrossNetProjectionResultV2 {
        val projection = EmployeeNetProjectionV2.Result(
            payroll = NetSalaryEngineV2.Result(
                gross = cash.cashGross ?: 0.0,
                socialSecurityCeiling = 4_005.0,
                socialSecurityCeilingComplete = true,
                statutory = 100.0,
                complementaryRetirement = 50.0,
                conventionProvidentEmployee = 0.0,
                conventionProvidentEmployer = 0.0,
                companyEmployeeDeductions = 50.0,
                employerStatusContributions = 0.0,
                employerAtMpContribution = null,
                netBeforeIncomeTax = 900.0,
                netTaxable = if (complete) 950.0 else null,
                incomeTax = if (complete) 45.0 else null,
                netAfterIncomeTax = if (complete) 855.0 else null,
                complete = complete,
                warnings = emptyList(),
                grossReliable = true
            ),
            netBeforeIncomeTax = if (complete) 900.0 else null,
            netTaxable = if (complete) 950.0 else null,
            incomeTax = if (complete) 45.0 else null,
            netAfterIncomeTax = if (complete) 855.0 else null,
            netBeforeIncomeTaxComplete = complete,
            warnings = emptyList()
        )
        return SegmentedCashGrossNetProjectionResultV2(
            cash = cash,
            projection = projection,
            cashGrossReliable = true,
            netBeforeIncomeTaxComplete = complete,
            warnings = emptyList()
        )
    }
}
