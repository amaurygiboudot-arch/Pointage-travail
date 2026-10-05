package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class EmployerAtMpRateHistoryV2Test {
    private val siret = "12345678901234"
    private val january = YearMonth.of(2026, 1)
    private fun record(id: String = "old", rate: Double = 0.0208,
        from: LocalDate = LocalDate.of(2025, 1, 1), to: LocalDate? = LocalDate.of(2026, 1, 31)) =
        EmployerAtMpRateHistoryV2.Record(id, siret, rate, from, to, "Notification établissement datée", 1L)

    @Test
    fun `ancien mois conserve montant notifie apres changement du taux courant`() {
        val versions = listOf(record(), record("new", 0.04, LocalDate.of(2026, 2, 1), null))
        val past = EmployerAtMpRateHistoryV2.resolve(versions, siret, january)
        val current = EmployerAtMpRateHistoryV2.resolve(versions, siret, january.plusMonths(1))
        assertTrue(past.reliable)
        assertEquals(52.0, EmployerAtMpContributionV2.calculate(2500.0, past.rate).employerAmount!!, 0.000001)
        assertEquals(100.0, EmployerAtMpContributionV2.calculate(2500.0, current.rate).employerAmount!!, 0.000001)
        assertEquals(siret, past.establishmentSiret)
        assertEquals("Notification établissement datée", past.source)
    }

    @Test
    fun `etablissement different ne fournit jamais le taux meme si date et montant valides`() {
        val result = EmployerAtMpRateHistoryV2.resolve(listOf(record()), "99999999999999", january)
        assertBlocked(result)
    }

    @Test
    fun `absence de notification et siret absent restent inconnus`() {
        assertBlocked(EmployerAtMpRateHistoryV2.resolve(emptyList(), siret, january))
        assertBlocked(EmployerAtMpRateHistoryV2.resolve(listOf(record()), "", january))
    }

    @Test
    fun `zero pourcent confirme et source est un vrai montant nul`() {
        val result = EmployerAtMpRateHistoryV2.resolve(listOf(record(rate = 0.0)), siret, january)
        assertTrue(result.reliable)
        val calculated = EmployerAtMpContributionV2.calculate(2500.0, result.rate)
        assertTrue(calculated.complete)
        assertEquals(0.0, calculated.employerAmount!!, 0.0)
    }

    @Test
    fun `confirmation absente source vide et taux invalide bloquent`() {
        listOf(record().copy(confirmedAtMs = 0L), record().copy(source = ""), record(rate = Double.NaN),
            record(rate = -0.01), record(rate = 1.01), record().copy(effectiveTo = LocalDate.of(2024, 1, 1)))
            .forEach { assertBlocked(EmployerAtMpRateHistoryV2.resolve(listOf(it), siret, january)) }
    }

    @Test
    fun `jour manquant et debut en milieu de mois ne sont pas etendus automatiquement`() {
        assertBlocked(EmployerAtMpRateHistoryV2.resolve(listOf(record(from = LocalDate.of(2026, 1, 2))), siret, january))
        assertBlocked(EmployerAtMpRateHistoryV2.resolve(listOf(record(to = LocalDate.of(2026, 1, 30))), siret, january))
    }

    @Test
    fun `changement au milieu du mois bloque le montant mensuel meme avec couverture complete`() {
        val versions = listOf(record(to = LocalDate.of(2026, 1, 15)), record("new", 0.03, LocalDate.of(2026, 1, 16), null))
        val result = EmployerAtMpRateHistoryV2.resolve(versions, siret, january)
        assertBlocked(result)
        assertTrue(result.warnings.single().contains("varie"))
        assertFalse(EmployerAtMpContributionV2.calculate(2500.0, result.rate).complete)
    }

    @Test
    fun `chevauchement meme taux et identifiants dupliques bloquent`() {
        assertBlocked(EmployerAtMpRateHistoryV2.resolve(listOf(record(), record("duplicate", from = LocalDate.of(2026, 1, 15))), siret, january))
        assertBlocked(EmployerAtMpRateHistoryV2.resolve(listOf(record(), record()), siret, january))
    }

    @Test
    fun `versions contigues au meme taux peuvent couvrir mois sans inventer source`() {
        val versions = listOf(record(to = LocalDate.of(2026, 1, 15)),
            record("next", from = LocalDate.of(2026, 1, 16), to = LocalDate.of(2026, 1, 31)).copy(source = "Seconde notification"))
        val result = EmployerAtMpRateHistoryV2.resolve(versions, siret, january)
        assertTrue(result.reliable)
        assertEquals(0.0208, result.rate!!, 0.0)
        assertEquals("Notification établissement datée | Seconde notification", result.source)
    }

    @Test
    fun `fin inclusive couvre le dernier jour du mois bissextile`() {
        val period = YearMonth.of(2028, 2)
        val valid = record(from = period.atDay(1), to = period.atEndOfMonth())
        assertTrue(EmployerAtMpRateHistoryV2.resolve(listOf(valid), siret, period).reliable)
        assertBlocked(EmployerAtMpRateHistoryV2.resolve(listOf(valid.copy(effectiveTo = LocalDate.of(2028, 2, 28))), siret, period))
    }

    private fun assertBlocked(result: EmployerAtMpRateHistoryV2.Snapshot) {
        assertFalse(result.reliable)
        assertNull(result.rate)
        assertNull(result.source)
        assertTrue(result.warnings.isNotEmpty())
    }
}
