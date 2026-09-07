package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FrenchPublicHolidayCalendarV2Test {
    @Test
    fun `calendrier commun 2026 calcule correctement les jours mobiles et exclut le premier mai generique`() {
        val scope = FrenchPublicHolidayCalendarV2.scopeForAddress("10 rue Exemple, 85000 La Roche-sur-Yon")
        val holidays = FrenchPublicHolidayCalendarV2.genericHolidays(2026, scope)

        assertTrue(scope.complete)
        assertEquals(FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE, scope.jurisdiction)
        assertTrue(LocalDate.of(2026, 4, 6) in holidays) // lundi de Pâques
        assertTrue(LocalDate.of(2026, 5, 14) in holidays) // Ascension
        assertTrue(LocalDate.of(2026, 5, 25) in holidays) // lundi de Pentecôte
        assertFalse(LocalDate.of(2026, 5, 1) in holidays)
        assertEquals(LocalDate.of(2026, 5, 1), FrenchPublicHolidayCalendarV2.mayFirst(2026))
    }

    @Test
    fun `alsace moselle ajoute le 26 decembre mais reste incomplete pour le vendredi saint`() {
        val scope = FrenchPublicHolidayCalendarV2.scopeForAddress("1 rue Exemple, 67000 Strasbourg")
        val holidays = FrenchPublicHolidayCalendarV2.genericHolidays(2026, scope)

        assertEquals(FrenchPublicHolidayCalendarV2.Jurisdiction.ALSACE_MOSELLE, scope.jurisdiction)
        assertFalse(scope.complete)
        assertTrue(LocalDate.of(2026, 12, 26) in holidays)
        assertTrue(scope.warning.orEmpty().contains("Vendredi saint"))
    }

    @Test
    fun `territoires ultramarins ajoutent leur commemoration legale`() {
        val martinique = FrenchPublicHolidayCalendarV2.scopeForAddress("97200 Fort-de-France")
        val mayotte = FrenchPublicHolidayCalendarV2.scopeForAddress("97600 Mamoudzou")
        val reunion = FrenchPublicHolidayCalendarV2.scopeForAddress("97400 Saint-Denis")
        val saintMartin = FrenchPublicHolidayCalendarV2.scopeForAddress("97150 Saint-Martin")

        assertTrue(LocalDate.of(2026, 5, 22) in FrenchPublicHolidayCalendarV2.genericHolidays(2026, martinique))
        assertTrue(LocalDate.of(2026, 4, 27) in FrenchPublicHolidayCalendarV2.genericHolidays(2026, mayotte))
        assertTrue(LocalDate.of(2026, 12, 20) in FrenchPublicHolidayCalendarV2.genericHolidays(2026, reunion))
        assertTrue(LocalDate.of(2026, 5, 27) in FrenchPublicHolidayCalendarV2.genericHolidays(2026, saintMartin))
    }

    @Test
    fun `adresse absente bloque la pretention de calendrier exhaustif`() {
        val scope = FrenchPublicHolidayCalendarV2.scopeForAddress("")
        assertFalse(scope.complete)
        assertEquals(FrenchPublicHolidayCalendarV2.Jurisdiction.ADDRESS_UNKNOWN, scope.jurisdiction)
    }
}
