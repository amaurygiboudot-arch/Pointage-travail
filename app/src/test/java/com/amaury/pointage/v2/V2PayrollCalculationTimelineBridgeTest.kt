package com.amaury.pointage.v2

import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionRuleSnapshotV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2PayrollCalculationTimelineBridgeTest {
    @Test
    fun `le bridge produit la timeline croisee du mois sans fallback`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val contractChange = LocalDate.of(2026, 1, 16).toEpochDay()
        val ruleChange = LocalDate.of(2026, 1, 21).toEpochDay()

        val result = V2PayrollCalculationTimelineBridge.resolveStored(
            companyStored = companyStore("0292"),
            contractStored = V2EmploymentContractHistoryStore.ReadResult(
                snapshots = listOf(
                    contract("c1", start - 20, contractChange - 1, 12.0),
                    contract("c2", contractChange, null, 13.0)
                ),
                reliable = true,
                repairedFromBackup = false,
                warnings = emptyList()
            ),
            conventionStored = V2ConventionRuleStore.ReadResult(
                snapshots = listOf(
                    rule("r1", start - 20, ruleChange - 1),
                    rule("r2", ruleChange, null)
                ),
                reliable = true,
                warnings = emptyList()
            ),
            companyId = "company",
            idcc = "0292",
            year = 2026,
            monthZeroBased = 0
        )

        assertTrue(result.timeline.reliable)
        assertEquals(3, result.timeline.slices.size)
        assertEquals(listOf("c1/r1", "c2/r1", "c2/r2"), result.timeline.slices.map {
            "${it.contractVersionId}/${it.ruleVersionId}"
        })
    }

    @Test
    fun `un historique conventionnel non fiable bloque le bridge entier`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val result = V2PayrollCalculationTimelineBridge.resolveStored(
            companyStored = companyStore("0292"),
            contractStored = V2EmploymentContractHistoryStore.ReadResult(
                snapshots = listOf(contract("c1", start - 20, null, 12.0)),
                reliable = true,
                repairedFromBackup = false,
                warnings = emptyList()
            ),
            conventionStored = V2ConventionRuleStore.ReadResult(
                snapshots = listOf(rule("r1", start - 20, null)),
                reliable = false,
                warnings = listOf("règles test non fiables")
            ),
            companyId = "company",
            idcc = "0292",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.timeline.reliable)
        assertTrue(result.timeline.slices.isEmpty())
        assertTrue(result.warnings.contains("règles test non fiables"))
    }

    @Test
    fun `un idcc appartenant a une autre entreprise ne peut jamais alimenter la timeline`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val result = V2PayrollCalculationTimelineBridge.resolveStored(
            companyStored = companyStore("1486"),
            contractStored = V2EmploymentContractHistoryStore.ReadResult(
                snapshots = listOf(contract("c1", start - 20, null, 12.0)),
                reliable = true,
                repairedFromBackup = false,
                warnings = emptyList()
            ),
            conventionStored = V2ConventionRuleStore.ReadResult(
                snapshots = listOf(rule("r1", start - 20, null)),
                reliable = true,
                warnings = emptyList()
            ),
            companyId = "company",
            idcc = "0292",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.timeline.reliable)
        assertTrue(result.timeline.slices.isEmpty())
        assertTrue(result.warnings.contains(V2PayrollCalculationTimelineBridge.COMPANY_IDCC_MISMATCH_WARNING))
    }

    @Test
    fun `un store entreprise non fiable bloque les regles meme si contrat et convention sont presents`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val result = V2PayrollCalculationTimelineBridge.resolveStored(
            companyStored = companyStore("0292", reliable = false),
            contractStored = V2EmploymentContractHistoryStore.ReadResult(
                snapshots = listOf(contract("c1", start - 20, null, 12.0)),
                reliable = true,
                repairedFromBackup = false,
                warnings = emptyList()
            ),
            conventionStored = V2ConventionRuleStore.ReadResult(
                snapshots = listOf(rule("r1", start - 20, null)),
                reliable = true,
                warnings = emptyList()
            ),
            companyId = "company",
            idcc = "0292",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.timeline.reliable)
        assertTrue(result.timeline.slices.isEmpty())
        assertTrue(result.warnings.contains(V2PayrollCalculationTimelineBridge.COMPANY_STORE_WARNING))
    }

    private fun companyStore(idcc: String, reliable: Boolean = true) = SalaryCompanyStore.ReadResult(
        companies = listOf(
            SalaryCompanyStore.Company(
                id = "company",
                name = "Entreprise test",
                siret = "",
                idcc = idcc
            )
        ),
        reliable = reliable,
        repairedFromBackup = false,
        warnings = if (reliable) emptyList() else listOf("store entreprise test non fiable")
    )

    private fun contract(
        version: String,
        from: Long,
        to: Long?,
        rate: Double
    ) = EmploymentContractSnapshotV2(
        versionId = version,
        sourceId = "contract-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        contract = ContractV2(
            id = version,
            employerId = "company",
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 35 * 60,
            grossHourlyRate = rate,
            hireDateEpochDay = 0L
        ),
        checkedAtMs = 1L,
        note = null
    )

    private fun rule(version: String, from: Long, to: Long?) = ConventionRuleSnapshotV2(
        idcc = "0292",
        versionId = version,
        sourceId = "rule-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        rules = PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
        checkedAtMs = 1L
    )
}
