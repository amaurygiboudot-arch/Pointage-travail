package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OvertimeLegalArbitrationBridgeV2Test {
    private val date = LocalDate.of(2026, 9, 30)
    private val zone = ZoneId.systemDefault()

    private fun branch() = ConventionRuleSnapshotV2(
        idcc = "0292",
        versionId = "2026-01",
        sourceId = "legifrance:KALI:0292:2026-01",
        effectiveFromEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
        effectiveToEpochDay = null,
        rules = PayrollRulesV2(
            weeklyRegularMinutes = 35 * 60,
            overtimeTiers = listOf(
                OvertimeTierV2(35 * 60, 43 * 60, 1.20),
                OvertimeTierV2(43 * 60, null, 1.30)
            )
        ),
        checkedAtMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
    )

    private fun legalFallbackRecord() = LegalPayrollSourceStoreV2.Record(
        topic = OfficialLegalCodeSourceV2.Topic.OVERTIME,
        articleId = "LEGIARTI000033020341",
        articleNumber = "L3121-36",
        status = "VIGUEUR",
        excerpt = "A défaut d'accord, les huit premières heures supplémentaires donnent lieu à une majoration de 25 %. Les heures suivantes donnent lieu à une majoration de 50 %.",
        effectiveFromMs = LocalDate.of(2016, 8, 10).atStartOfDay(zone).toInstant().toEpochMilli(),
        effectiveToMs = null,
        referenceAtMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        checkedAtMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
    )

    private fun absent(vararg sources: PayrollLegalArbitratorV2.Source) =
        sources.associateWith { PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE }

    @Test
    fun `KALI est retenu avant le bareme LEGI si absence ACCO confirmee`() {
        val result = OvertimeLegalArbitrationBridgeV2.assemble(
            referenceDate = date,
            companyAgreement = null,
            branchSnapshot = branch(),
            legalRecords = listOf(legalFallbackRecord()),
            sourceKnowledge = absent(PayrollLegalArbitratorV2.Source.ACCO)
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.resolution.state)
        assertEquals(PayrollLegalArbitratorV2.Source.KALI, result.resolution.selected?.source)
        assertEquals(1.20, result.selectedSchedule!!.tiers.first().multiplier, 0.0001)
    }

    @Test
    fun `LEGI n est retenu qu apres absence ACCO et KALI confirmee`() {
        val result = OvertimeLegalArbitrationBridgeV2.assemble(
            referenceDate = date,
            companyAgreement = null,
            branchSnapshot = null,
            legalRecords = listOf(legalFallbackRecord()),
            sourceKnowledge = absent(
                PayrollLegalArbitratorV2.Source.ACCO,
                PayrollLegalArbitratorV2.Source.KALI
            )
        )

        assertEquals(PayrollLegalArbitratorV2.Source.LEGI, result.resolution.selected?.source)
        assertEquals(listOf(1.25, 1.50), result.selectedSchedule!!.tiers.map { it.multiplier })
    }

    @Test
    fun `LEGI est bloque si le contenu KALI est encore inconnu`() {
        val result = OvertimeLegalArbitrationBridgeV2.assemble(
            referenceDate = date,
            companyAgreement = null,
            branchSnapshot = null,
            legalRecords = listOf(legalFallbackRecord()),
            sourceKnowledge = absent(PayrollLegalArbitratorV2.Source.ACCO)
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.resolution.state)
        assertNull(result.selectedSchedule)
    }
}
