package com.amaury.pointage.billing

import org.junit.Assert.*
import org.junit.Test

class PaidServiceReportBuilderTest {
    private fun month(index: Int = 0) = PaidServiceReportBuilder.Month(2025, index, "record$index", "bulletin.pdf", "a".repeat(64), "01/02/2025",
        mapOf("Brut" to 1900.0, "Net avant PAS" to 1500.0, "Net imposable" to 1550.0, "Heures supplémentaires" to 200.0, "Mutuelle salariale" to 20.0),
        mapOf("Brut" to 2000.0, "Net avant PAS" to 1600.0, "Net imposable" to 1650.0, "Heures supplémentaires" to 200.0),
        setOf("Brut", "Net avant PAS", "Net imposable", "Heures supplémentaires"), listOf("2025-01-02 : 7h00 payées"), reliable = true)
    private fun input(product: String = "horatrack_payslip_comparison", months: List<PaidServiceReportBuilder.Month> = listOf(month())) =
        PaidServiceReportBuilder.Input(product, "company", "Entreprise", "", "0292", 2025, months)
    private fun rejected(input: PaidServiceReportBuilder.Input) { assertTrue(runCatching { PaidServiceReportBuilder.build(input) }.isFailure) }

    @Test fun rejectsThinUnreliableAmbiguousOrUnboundedReportsBeforePayment() {
        rejected(input(months = listOf(month().copy(reliable = false))))
        rejected(input(months = listOf(month(), month())))
        rejected(input("horatrack_analysis", listOf(month().copy(observed = mapOf("Brut" to 1900.0)))))
        rejected(input("horatrack_analysis", listOf(month().copy(observed = month().observed + ("invented" to 1.0)))))
        rejected(input(months = listOf(month().copy(observed = month().observed + ("Brut" to 1e308)))))
        rejected(input("horatrack_annual_review", listOf(month())))
    }

    @Test fun comparisonExplainsEveryFieldAndNeverAddsOverlappingDeltas() {
        val report = PaidServiceReportBuilder.build(input())
        assertTrue(report.lines.any { it.startsWith("Brut : observé") && it.contains("-100,00") })
        assertTrue(report.lines.any { it.startsWith("Primes / majorations : NON LU") })
        assertTrue(report.lines.any { it.contains("pas une créance établie") })
        assertFalse(report.lines.any { it.contains("Total dû") })
    }

    @Test fun annualShowsTwelveMonthsMissingCoverageAndRepeatedAnomalies() {
        val report = PaidServiceReportBuilder.build(input("horatrack_annual_review", (0..5).map(::month)))
        assertEquals(12, report.lines.count { Regex("\\d{2}/2025 : .*" ).matches(it) })
        assertEquals(6, report.lines.count { it.contains("NON CONTRÔLÉ") })
        assertTrue(report.lines.any { it.contains("Brut : écarts répétés, 6 négatifs") })
        assertTrue(report.lines.any { it.contains("Cumuls PARTIELS") })
    }

    @Test fun deterministicDigestChangesWhenLetterSourceOrFactsChange() {
        val base = input("horatrack_claim_dossier").copy(claimLetter = "Lettre corrigée par l'utilisateur")
        val first = PaidServiceReportBuilder.build(base)
        assertEquals(first.inputSha256, PaidServiceReportBuilder.build(base).inputSha256)
        assertNotEquals(first.inputSha256, PaidServiceReportBuilder.build(base.copy(claimLetter = "Autre lettre")).inputSha256)
        assertNotEquals(first.inputSha256, PaidServiceReportBuilder.build(base.copy(months = listOf(month().copy(sourceSha256 = "b".repeat(64))))).inputSha256)
        assertTrue(first.lines.contains(base.claimLetter))
        assertTrue(first.lines.any { it.contains("AUCUN ENVOI AUTOMATIQUE") })
    }
}
