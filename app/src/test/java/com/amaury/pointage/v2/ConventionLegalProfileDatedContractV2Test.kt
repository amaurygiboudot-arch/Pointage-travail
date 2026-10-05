package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ConventionLegalProfileDatedContractV2Test {
    private val referenceDate = LocalDate.of(2026, 1, 31)
    private val historicalHireDate = LocalDate.of(2020, 1, 1)
    private val currentProfile = ConventionLegalProfileV2(
        companyId = "company-a", idcc = "292", siret = "", professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = 700),
        contractType = "FULL_TIME", entryDate = LocalDate.of(2026, 1, 1),
        conventionSeniorityDate = null, weeklyHours = 35.0,
        forfaitAnnualHours = 1607.0, forfaitAnnualDays = 218.0
    )

    @Test
    fun `ancien mois conserve son embauche et sa duree malgre profil courant contradictoire`() {
        val past = version("past", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 31))
        val current = version("current", LocalDate.of(2026, 2, 1), null).copy(
            contract = past.contract.copy(hireDateEpochDay = LocalDate.of(2026, 2, 1).toEpochDay(),
                type = ContractTypeV2.FULL_TIME, contractualWeeklyMinutes = 2100)
        )
        val profile = dated(listOf(past, current))
        assertEquals(historicalHireDate, profile.entryDate)
        assertEquals("PART_TIME", profile.contractType)
        assertEquals(20.0, profile.weeklyHours!!, 0.0)
        assertNull(profile.forfaitAnnualHours)
        assertNull(profile.forfaitAnnualDays)
        assertEquals(72, V2ConventionProvidentContributionBridge.seniorityMonths(profile, referenceDate))
        assertEquals(0, V2ConventionProvidentContributionBridge.seniorityMonths(currentProfile, referenceDate))
    }

    @Test
    fun `historique absent ou corrompu ne recopie aucun champ courant`() {
        listOf(dated(emptyList()), dated(listOf(version("v1", LocalDate.of(2025, 1, 1), null)), false))
            .forEach { profile ->
                assertNull(profile.entryDate)
                assertNull(profile.contractType)
                assertNull(profile.weeklyHours)
                assertNull(profile.forfaitAnnualHours)
                assertNull(profile.forfaitAnnualDays)
                assertNull(V2ConventionProvidentContributionBridge.seniorityMonths(profile, referenceDate))
            }
    }

    @Test
    fun `resolution non fiable avec contrat present ne fournit aucune anciennete`() {
        val stored = V2EmploymentContractHistoryStore.ReadResult(
            listOf(version("v1", historicalHireDate, null)), true, false, emptyList()
        )
        val resolution = V2EmploymentContractPayrollBridge.resolveStored(stored, "company-a", 2026, 0)
            .resolution.copy(sourceReliable = false)
        val profile = ConventionLegalProfileV2.withDatedResolution(currentProfile, resolution)
        assertNull(profile.entryDate)
        assertNull(profile.contractType)
        assertNull(profile.weeklyHours)
        assertNull(V2ConventionProvidentContributionBridge.seniorityMonths(profile, referenceDate))
    }

    @Test
    fun `contrat confirme sans embauche ne reprend pas la date courante`() {
        val contract = version("v1", historicalHireDate, null).contract.copy(hireDateEpochDay = null)
        val profile = ConventionLegalProfileV2.withDatedContract(currentProfile, contract)
        assertNull(profile.entryDate)
        assertNull(V2ConventionProvidentContributionBridge.seniorityMonths(profile, referenceDate))
    }

    @Test
    fun `changement contractuel dans le mois ne choisit pas une embauche arbitraire`() {
        val before = version("before", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 15))
        val after = version("after", LocalDate.of(2026, 1, 16), null).copy(
            contract = before.contract.copy(grossHourlyRate = 15.0)
        )
        assertNull(dated(listOf(before, after)).entryDate)
    }

    @Test
    fun `anciennete conventionnelle explicite est preservee sans etre remplacee par embauche`() {
        val explicit = LocalDate.of(2018, 1, 1)
        val profile = currentProfile.copy(conventionSeniorityDate = explicit)
        val dated = ConventionLegalProfileV2.withDatedContract(profile, version("v1", historicalHireDate, null).contract)
        assertEquals(explicit, dated.conventionSeniorityDate)
        assertEquals(historicalHireDate, dated.entryDate)
        assertEquals(96, V2ConventionProvidentContributionBridge.seniorityMonths(dated, referenceDate))
        assertEquals(explicit, ConventionLegalProfileV2.withDatedContract(profile, null).conventionSeniorityDate)
        assertNull(ConventionLegalProfileV2.withDatedContract(currentProfile, null).conventionSeniorityDate)
    }

    @Test
    fun `forfait mensuel ne devient pas un forfait annuel et classification reste intacte`() {
        val base = version("v1", historicalHireDate, null).contract.copy(
            type = ContractTypeV2.FORFAIT_HOURS, contractualWeeklyMinutes = null,
            forfaitHoursPeriod = ForfaitHoursPeriodV2.MONTH, forfaitHours = 160.0
        )
        val monthly = ConventionLegalProfileV2.withDatedContract(currentProfile, base)
        assertNull(monthly.forfaitAnnualHours)
        assertEquals(currentProfile.classification, monthly.classification)
        assertEquals(currentProfile.professionalStatus, monthly.professionalStatus)
        assertEquals(160.0, ConventionLegalProfileV2.withDatedContract(currentProfile,
            base.copy(forfaitHoursPeriod = ForfaitHoursPeriodV2.YEAR)).forfaitAnnualHours!!, 0.0)
    }

    private fun dated(versions: List<EmploymentContractSnapshotV2>, reliable: Boolean = true): ConventionLegalProfileV2 {
        val stored = V2EmploymentContractHistoryStore.ReadResult(versions, reliable, false,
            if (reliable) emptyList() else listOf(V2EmploymentContractHistoryStore.STORAGE_WARNING))
        val resolution = V2EmploymentContractPayrollBridge.resolveStored(stored, "company-a", 2026, 0).resolution
        return ConventionLegalProfileV2.withDatedResolution(currentProfile, resolution)
    }

    private fun version(id: String, from: LocalDate, to: LocalDate?) = EmploymentContractSnapshotV2(
        versionId = id, sourceId = "contrat confirme", effectiveFromEpochDay = from.toEpochDay(),
        effectiveToEpochDay = to?.toEpochDay(), checkedAtMs = 1L,
        contract = ContractV2(id = "contract", employerId = "company-a", type = ContractTypeV2.PART_TIME,
            contractualWeeklyMinutes = 1200, grossHourlyRate = 14.0, hireDateEpochDay = historicalHireDate.toEpochDay())
    )
}
