package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.CompanyProfessionalStatusResolverV2 as Resolver
import com.amaury.pointage.v2.engine.ComplementaryRetirementCatalogV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CompanyProfessionalStatusV2Test {
    private val january = YearMonth.of(2026, 1)
    private fun record(id: String = "v1", status: Resolver.Status = Resolver.Status.NON_CADRE,
        start: String = "2026-01-01", end: String? = "2026-01-31", source: String = "contrat signé") =
        Resolver.Record(id, status, LocalDate.parse(start), end?.let(LocalDate::parse), source, 1L)

    @Test fun unconfirmedVersionCannotSupplyStatus() {
        assertNull(Resolver.resolve(listOf(record().copy(confirmedAtMs = 0L)), january).status)
    }

    private fun currentLegalProfile() = ConventionLegalProfileV2(
        companyId = "company-a", idcc = "", siret = "", professionalStatus = "CADRE",
        classification = com.amaury.pointage.v2.engine.ConventionClassificationV2(),
        contractType = null, entryDate = null, conventionSeniorityDate = null,
        weeklyHours = null, forfaitAnnualHours = null, forfaitAnnualDays = null
    )

    @Test fun datedLegalProfileDoesNotInheritCurrentCadreForJanuaryApec() {
        val profile = ConventionLegalProfileV2.withDatedProfessionalStatus(currentLegalProfile(),
            Resolver.resolve(listOf(record(), record("v2", Resolver.Status.CADRE, "2026-02-01", null)), january))
        assertFalse(ComplementaryRetirementCatalogV2.estimateGeneric(2500.0, 2026, profile.professionalStatus)
            .lines.any { it.id == "apec" })
        assertEquals("NON_CADRE", profile.professionalStatus)
    }

    @Test fun datedLegalProfileDoesNotPromoteStatusFromUnreliableSnapshot() {
        val profile = ConventionLegalProfileV2.withDatedProfessionalStatus(currentLegalProfile(),
            Resolver.Snapshot("CADRE", false, listOf("corrompu")))
        assertNull(profile.professionalStatus)
    }

    @Test fun promotionDoesNotRewriteJanuaryApec() {
        val records = listOf(record(), record("v2", Resolver.Status.CADRE, "2026-02-01", null))
        val past = Resolver.resolve(records, january)
        val current = Resolver.resolve(records, january.plusMonths(1))
        assertEquals("NON_CADRE", past.status)
        assertEquals("CADRE", current.status)
        assertFalse(ComplementaryRetirementCatalogV2.estimateGeneric(2500.0, 2026, past.status).lines.any { it.id == "apec" })
        assertTrue(ComplementaryRetirementCatalogV2.estimateGeneric(2500.0, 2026, current.status).lines.any { it.id == "apec" })
    }
    @Test fun missingHistoryIsUnknown() {
        assertNull(Resolver.resolve(emptyList(), january).status)
        assertFalse(Resolver.resolve(emptyList(), january).reliable)
    }
    @Test fun futureCurrentVersionCannotCoverOldMonth() {
        assertNull(Resolver.resolve(listOf(record(start = "2026-02-01", end = null)), january).status)
    }
    @Test fun materialMidMonthChangeIsBlocked() {
        val records = listOf(record(end = "2026-01-15"), record("v2", Resolver.Status.CADRE, "2026-01-16"))
        assertNull(Resolver.resolve(records, january).status)
    }
    @Test fun equivalentAdjacentVersionsKeepStatus() {
        val records = listOf(record(end = "2026-01-15"), record("v2", start = "2026-01-16"))
        assertEquals("NON_CADRE", Resolver.resolve(records, january).status)
    }
    @Test fun overlapBlocksEvenIdenticalStatus() {
        assertNull(Resolver.resolve(listOf(record(), record("v2")), january).status)
    }
    @Test fun dayGapIsNotMonthlyCoverage() {
        assertNull(Resolver.resolve(listOf(record(end = "2026-01-15"), record("v2", start = "2026-01-17")), january).status)
    }
    @Test fun blankSourceOrReversedPeriodIsInvalid() {
        assertFalse(Resolver.resolve(listOf(record(source = " ")), january).reliable)
        assertFalse(Resolver.resolve(listOf(record(start = "2026-01-31", end = "2026-01-01")), january).reliable)
    }
    @Test fun corruptStoreDoesNotPromoteReadableRows() {
        val stored = CompanyProfessionalStatusStoreV2.ReadResult(listOf(record()), false, listOf("corrompu"))
        assertNull(CompanyProfessionalStatusStoreV2.resolve(stored, january).status)
    }
    @Test fun malformedJsonAndDuplicateIdsBlockResolution() {
        assertFalse(CompanyProfessionalStatusStoreV2.decode("invalid").reliable)
        val row = """{"id":"v1","status":"NON_CADRE","effectiveFrom":"2026-01-01","effectiveTo":"2026-01-31","source":"bulletin","confirmedAtMs":1}"""
        assertFalse(CompanyProfessionalStatusStoreV2.decode("[$row,$row]").reliable)
        assertNull(CompanyProfessionalStatusStoreV2.resolve(CompanyProfessionalStatusStoreV2.decode("[$row,{}]"), january).status)
    }
    @Test fun deletedOrUnconfirmedCompanyCannotExposeItsHistory() {
        val unavailable = CompanyProfessionalStatusStoreV2.companyUnavailableResult()
        assertTrue(unavailable.records.isEmpty())
        assertNull(CompanyProfessionalStatusStoreV2.resolve(unavailable, january).status)
    }
    @Test fun emptyCompanyHistoryDoesNotInheritOtherCompanyStatus() {
        assertEquals("NON_CADRE", CompanyProfessionalStatusStoreV2.resolve(
            CompanyProfessionalStatusStoreV2.ReadResult(listOf(record()), true, emptyList()), january).status)
        assertNull(CompanyProfessionalStatusStoreV2.resolve(
            CompanyProfessionalStatusStoreV2.ReadResult(emptyList(), true, emptyList()), january).status)
    }
}
