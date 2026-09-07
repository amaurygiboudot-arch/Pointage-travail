package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Calendrier déterministe des jours fériés légaux français utilisés pour affecter des minutes
 * réellement travaillées. Il ne décide jamais du taux de paie : cette décision reste dans
 * l'arbitrage ACCO/KALI/LEGI.
 *
 * Sources de structure juridique :
 * - Code du travail L3133-1 : onze fêtes légales communes ;
 * - L3134-13 : régime local Moselle/Bas-Rhin/Haut-Rhin ;
 * - L3422-2 : commémoration de l'abolition de l'esclavage dans les territoires concernés.
 *
 * Le 1er mai est identifié séparément car son régime de rémunération relève d'une règle LEGI propre.
 */
object FrenchPublicHolidayCalendarV2 {
    enum class Jurisdiction {
        COMMON_FRANCE,
        ALSACE_MOSELLE,
        GUADELOUPE,
        MARTINIQUE,
        GUYANE,
        MAYOTTE,
        REUNION,
        SAINT_BARTHELEMY,
        SAINT_MARTIN,
        SPECIAL_TERRITORY_UNKNOWN,
        ADDRESS_UNKNOWN
    }

    data class Scope(
        val jurisdiction: Jurisdiction,
        /** False signifie qu'une règle locale peut ajouter un jour impossible à déduire de l'adresse seule. */
        val complete: Boolean,
        val postalCode: String? = null,
        val warning: String? = null
    )

    fun scopeForAddress(address: String): Scope {
        val postal = Regex("(?<!\\d)(\\d{5})(?!\\d)").find(address)?.groupValues?.getOrNull(1)
            ?: return Scope(
                Jurisdiction.ADDRESS_UNKNOWN,
                complete = false,
                warning = "Adresse de l'entreprise insuffisante pour confirmer le calendrier territorial des jours fériés."
            )

        return when {
            postal == "97133" -> Scope(Jurisdiction.SAINT_BARTHELEMY, true, postal)
            postal == "97150" -> Scope(Jurisdiction.SAINT_MARTIN, true, postal)
            postal.startsWith("971") -> Scope(Jurisdiction.GUADELOUPE, true, postal)
            postal.startsWith("972") -> Scope(Jurisdiction.MARTINIQUE, true, postal)
            postal.startsWith("973") -> Scope(Jurisdiction.GUYANE, true, postal)
            postal.startsWith("974") -> Scope(Jurisdiction.REUNION, true, postal)
            postal.startsWith("976") -> Scope(Jurisdiction.MAYOTTE, true, postal)
            postal.startsWith("98") -> Scope(
                Jurisdiction.SPECIAL_TERRITORY_UNKNOWN,
                complete = false,
                postalCode = postal,
                warning = "Territoire à calendrier local : validation juridique des jours fériés requise avant calcul automatique."
            )
            postal.startsWith("57") || postal.startsWith("67") || postal.startsWith("68") -> Scope(
                Jurisdiction.ALSACE_MOSELLE,
                complete = false,
                postalCode = postal,
                warning = "Alsace-Moselle : le Vendredi saint dépend de la commune ; le calendrier ne peut pas être déclaré exhaustif depuis le seul code postal."
            )
            else -> Scope(Jurisdiction.COMMON_FRANCE, true, postal)
        }
    }

    /** Jours fériés pouvant recevoir une règle collective générique, hors 1er mai. */
    fun genericHolidays(year: Int, scope: Scope): Set<LocalDate> {
        require(year in 1900..2200) { "Année hors périmètre du calendrier" }
        val easter = easterSunday(year)
        val values = linkedSetOf(
            LocalDate.of(year, 1, 1),
            easter.plusDays(1),
            LocalDate.of(year, 5, 8),
            easter.plusDays(39),
            easter.plusDays(50),
            LocalDate.of(year, 7, 14),
            LocalDate.of(year, 8, 15),
            LocalDate.of(year, 11, 1),
            LocalDate.of(year, 11, 11),
            LocalDate.of(year, 12, 25)
        )

        when (scope.jurisdiction) {
            Jurisdiction.ALSACE_MOSELLE -> {
                // Le 26 décembre est certain ; le Vendredi saint reste volontairement non ajouté
                // car son application dépend de la commune (L3134-13).
                values += LocalDate.of(year, 12, 26)
            }
            Jurisdiction.GUADELOUPE,
            Jurisdiction.SAINT_MARTIN -> values += LocalDate.of(year, 5, 27)
            Jurisdiction.MARTINIQUE -> values += LocalDate.of(year, 5, 22)
            Jurisdiction.GUYANE -> values += LocalDate.of(year, 6, 10)
            Jurisdiction.MAYOTTE -> values += LocalDate.of(year, 4, 27)
            Jurisdiction.REUNION -> values += LocalDate.of(year, 12, 20)
            Jurisdiction.SAINT_BARTHELEMY -> values += LocalDate.of(year, 10, 9)
            Jurisdiction.COMMON_FRANCE,
            Jurisdiction.SPECIAL_TERRITORY_UNKNOWN,
            Jurisdiction.ADDRESS_UNKNOWN -> Unit
        }
        return values
    }

    fun mayFirst(year: Int): LocalDate = LocalDate.of(year, 5, 1)

    fun isGenericHoliday(date: LocalDate, scope: Scope): Boolean =
        date in genericHolidays(date.year, scope)

    fun isMayFirst(date: LocalDate): Boolean = date.monthValue == 5 && date.dayOfMonth == 1

    /** Algorithme grégorien de Meeus/Jones/Butcher. */
    internal fun easterSunday(year: Int): LocalDate {
        require(year in 1900..2200)
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
