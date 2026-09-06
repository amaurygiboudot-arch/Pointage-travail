package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatutoryOvertimeRulesV2Test {
    private val zone = ZoneId.of("UTC")
    private val reference = LocalDate.of(2026, 9, 30)

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun record(
        number: String,
        excerpt: String,
        from: LocalDate = LocalDate.of(2020, 1, 1),
        to: LocalDate? = null
    ) = LegalPayrollSourceStoreV2.Record(
        topic = OfficialLegalCodeSourceV2.Topic.OVERTIME,
        articleId = "LEGIARTI000000000001",
        articleNumber = number,
        status = "VIGUEUR",
        excerpt = excerpt,
        effectiveFromMs = ms(from),
        effectiveToMs = to?.let(::ms),
        referenceAtMs = ms(reference),
        checkedAtMs = ms(reference)
    )

    @Test
    fun `structure 25 puis 50 uniquement depuis L3121-36 verifie`() {
        val rule = StatutoryOvertimeRulesV2.fallbackRule(
            listOf(record(
                "L3121-36",
                "A défaut d'accord, les huit premières heures supplémentaires donnent lieu à une majoration de 25 %. Les heures suivantes donnent lieu à une majoration de 50 %."
            )),
            reference,
            zone
        )

        assertNotNull(rule)
        assertEquals(2, rule!!.tiers.size)
        assertEquals(35 * 60, rule.tiers[0].fromMinutes)
        assertEquals(43 * 60, rule.tiers[0].toMinutes)
        assertEquals(1.25, rule.tiers[0].multiplier, 0.0001)
        assertEquals(1.50, rule.tiers[1].multiplier, 0.0001)
    }

    @Test
    fun `refuse de garder un ancien bareme si le texte LEGI ne confirme plus les taux`() {
        val rule = StatutoryOvertimeRulesV2.fallbackRule(
            listOf(record("L3121-36", "Nouvelle rédaction sans barème chiffré exploitable.")),
            reference,
            zone
        )

        assertNull(rule)
    }

    @Test
    fun `refuse un article hors periode`() {
        val rule = StatutoryOvertimeRulesV2.fallbackRule(
            listOf(record(
                "L3121-36",
                "Les huit premières heures supplémentaires sont majorées de 25 %, les suivantes de 50 %.",
                to = LocalDate.of(2026, 8, 31)
            )),
            reference,
            zone
        )

        assertNull(rule)
    }

    @Test
    fun `lit le plancher de 10 pour un accord seulement si L3121-33 le confirme`() {
        val percent = StatutoryOvertimeRulesV2.agreementMinimumPercent(
            listOf(record(
                "L. 3121-33",
                "Une convention ou un accord collectif prévoit le taux de majoration. Ce taux ne peut être inférieur à 10 %."
            )),
            reference,
            zone
        )

        assertEquals(10.0, percent!!, 0.0001)
    }
}
