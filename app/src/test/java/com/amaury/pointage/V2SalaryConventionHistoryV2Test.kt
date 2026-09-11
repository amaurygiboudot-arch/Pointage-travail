package com.amaury.pointage

import com.amaury.pointage.v2.V2ConventionRuleStore
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.engine.ConventionRuleSnapshotV2
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SalaryConventionHistoryV2Test {
    private fun snapshot(): ConventionRuleSnapshotV2 = ConventionRuleSnapshotV2(
        idcc = "0292",
        versionId = "v1",
        sourceId = "legifrance:KALI:TEST",
        effectiveFromEpochDay = 1_000L,
        rules = PayrollRulesV2(
            weeklyRegularMinutes = 35 * 60,
            overtimeTiers = listOf(
                OvertimeTierV2(35 * 60, 43 * 60, 1.25),
                OvertimeTierV2(43 * 60, null, 1.50)
            )
        ),
        checkedAtMs = 1L
    )

    private fun convention() = ConventionCatalog.Convention(
        idcc = "0292",
        shortName = "Plasturgie",
        fullName = "Plasturgie",
        rulesIntegrated = true,
        overtimeTiers = listOf(ConventionCatalog.OvertimeTier(35.0, 43.0, 1.25))
    )

    @Test
    fun `historique KALI corrompu bloque le fallback statique du temps plein`() {
        val state = salaryConventionHistoryStateV2(
            provided = null,
            stored = V2ConventionRuleStore.ReadResult(
                snapshots = listOf(snapshot()),
                reliable = false,
                warnings = listOf("KALI : stockage incohérent")
            )
        )

        assertFalse(state.reliable)
        assertTrue(state.history!!.allVersions("0292").isEmpty())
        assertFalse(
            state.conventionForCalculation(
                convention(),
                ContractTypeV2.FULL_TIME
            ).rulesIntegrated
        )
        assertTrue(state.warnings.any { it.contains("stockage incohérent") })
    }

    @Test
    fun `contrat OTHER conserve une estimation statique pour ne perdre aucune minute`() {
        val state = salaryConventionHistoryStateV2(
            provided = null,
            stored = V2ConventionRuleStore.ReadResult(
                snapshots = emptyList(),
                reliable = false,
                warnings = listOf("KALI : stockage incohérent")
            )
        )

        val calculationConvention = state.conventionForCalculation(
            convention(),
            ContractTypeV2.OTHER
        )

        assertTrue(calculationConvention.rulesIntegrated)
        assertTrue(calculationConvention.overtimeTiers.isNotEmpty())
    }

    @Test
    fun `estimation OTHER issue du catalogue devient non fiable si elle valorise des heures sup`() {
        val state = SalaryConventionHistoryStateV2(
            history = ConventionRuleHistoryV2.empty(),
            reliable = false,
            warnings = listOf("KALI : stockage incohérent")
        )

        assertFalse(
            salaryConventionHistoryGrossReliableV2(
                baseReliable = true,
                state = state,
                contractType = ContractTypeV2.OTHER,
                overtimeGross = 42.0
            )
        )
        assertTrue(
            salaryConventionHistoryGrossReliableV2(
                baseReliable = true,
                state = state,
                contractType = ContractTypeV2.OTHER,
                overtimeGross = 0.0
            )
        )
    }

    @Test
    fun `historique KALI vide mais fiable conserve le fallback catalogue existant`() {
        val state = salaryConventionHistoryStateV2(
            provided = null,
            stored = V2ConventionRuleStore.ReadResult(
                snapshots = emptyList(),
                reliable = true,
                warnings = emptyList()
            )
        )

        assertTrue(state.reliable)
        assertTrue(
            state.conventionForCalculation(
                convention(),
                ContractTypeV2.FULL_TIME
            ).rulesIntegrated
        )
        assertTrue(state.warnings.isEmpty())
    }

    @Test
    fun `historique KALI fiable conserve ses snapshots dates`() {
        val storedSnapshot = snapshot()
        val state = salaryConventionHistoryStateV2(
            provided = null,
            stored = V2ConventionRuleStore.ReadResult(
                snapshots = listOf(storedSnapshot),
                reliable = true,
                warnings = emptyList()
            )
        )

        assertTrue(state.reliable)
        assertEquals("v1", state.history!!.applicable("0292", 1_000L)?.versionId)
    }

    @Test
    fun `historique explicitement fourni reste prioritaire pour les tests et appels controles`() {
        val provided = ConventionRuleHistoryV2(listOf(snapshot()))
        val state = salaryConventionHistoryStateV2(
            provided = provided,
            stored = V2ConventionRuleStore.ReadResult(
                snapshots = emptyList(),
                reliable = false,
                warnings = listOf("ne doit pas contaminer l'historique fourni")
            )
        )

        assertTrue(state.reliable)
        assertEquals("v1", state.history!!.applicable("0292", 1_000L)?.versionId)
        assertTrue(state.warnings.isEmpty())
    }

    @Test
    fun `temps plein remplace le message trompeur de barème non integre`() {
        val state = SalaryConventionHistoryStateV2(
            history = ConventionRuleHistoryV2.empty(),
            reliable = false,
            warnings = listOf("KALI : historique local incohérent")
        )
        val warnings = salaryConventionHistoryWarningsV2(
            existing = listOf(
                "Barème conventionnel d'heures supplémentaires non intégré : ancien message",
                "Autre avertissement à conserver"
            ),
            state = state,
            contractType = ContractTypeV2.FULL_TIME
        )

        assertFalse(warnings.any { it.startsWith("Barème conventionnel d'heures supplémentaires non intégré") })
        assertTrue(warnings.contains("Autre avertissement à conserver"))
        assertTrue(warnings.contains("KALI : historique local incohérent"))
    }

    @Test
    fun `OTHER conserve le diagnostic de barème non integre en plus du stockage incoherent`() {
        val state = SalaryConventionHistoryStateV2(
            history = ConventionRuleHistoryV2.empty(),
            reliable = false,
            warnings = listOf("KALI : historique local incohérent")
        )
        val warnings = salaryConventionHistoryWarningsV2(
            existing = listOf("Barème conventionnel d'heures supplémentaires non intégré : contrôle requis"),
            state = state,
            contractType = ContractTypeV2.OTHER
        )

        assertTrue(warnings.any { it.startsWith("Barème conventionnel d'heures supplémentaires non intégré") })
        assertTrue(warnings.contains("KALI : historique local incohérent"))
    }
}
