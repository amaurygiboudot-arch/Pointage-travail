package com.amaury.pointage.v2

import java.time.LocalDate

/** Faits légaux externes strictement datés nécessaires au calcul des paniers. */
object MealBasketLegalFactsV2 {
    data class MinimumGuaranteed(
        val amount: Double,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate,
        val source: String
    )

    /**
     * Dernière date à laquelle la continuité du barème a été vérifiée sur Légifrance.
     * Une paie postérieure est volontairement bloquée jusqu'à une nouvelle vérification officielle.
     */
    private val VERIFIED_THROUGH = LocalDate.of(2026, 9, 8)

    private val minimumGuaranteedHistory = listOf(
        MinimumGuaranteed(
            amount = 4.22,
            effectiveFrom = LocalDate.of(2024, 11, 1),
            effectiveTo = LocalDate.of(2025, 12, 31),
            source = "Décret n° 2024-951 du 23 octobre 2024 — article 2"
        ),
        MinimumGuaranteed(
            amount = 4.25,
            effectiveFrom = LocalDate.of(2026, 1, 1),
            effectiveTo = VERIFIED_THROUGH,
            source = "Décret n° 2025-1228 du 17 décembre 2025 — article 2"
        )
    )

    fun minimumGuaranteed(date: LocalDate, companyAddress: String): MinimumGuaranteed? {
        if (!FrenchCompanyTerritoryV2.minimumGuaranteedTerritoryConfirmed(companyAddress)) return null
        return minimumGuaranteedHistory.singleOrNull {
            !date.isBefore(it.effectiveFrom) && !date.isAfter(it.effectiveTo)
        }
    }
}

/** Extraction volontairement conservatrice du territoire depuis l'adresse entreprise confirmée. */
object FrenchCompanyTerritoryV2 {
    fun departmentCode(address: String): String? {
        val postal = postalCodes(address).singleOrNull() ?: return null
        if (postal.startsWith("20")) return null // Corse : 2A/2B impossible à déduire du seul CP.
        if (postal.startsWith("97") || postal.startsWith("98")) return postal.take(3)
        val department = postal.take(2).toIntOrNull() ?: return null
        return department.takeIf { it in 1..95 }?.toString()?.padStart(2, '0')
    }

    /** Périmètre de l'article 2 des décrets MG : métropole + collectivités françaises visées. */
    fun minimumGuaranteedTerritoryConfirmed(address: String): Boolean {
        val postal = postalCodes(address).singleOrNull() ?: return false
        val prefix2 = postal.take(2).toIntOrNull()
        if (prefix2 != null && prefix2 in 1..95) return true
        return postal.take(3) in setOf("971", "972", "973", "974", "975", "976")
    }

    private fun postalCodes(address: String): List<String> = Regex("(?<![0-9])([0-9]{5})(?![0-9])")
        .findAll(address)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}