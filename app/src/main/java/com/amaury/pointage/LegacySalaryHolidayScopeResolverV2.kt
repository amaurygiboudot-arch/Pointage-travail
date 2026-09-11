package com.amaury.pointage

import com.amaury.pointage.v2.engine.FrenchPublicHolidayCalendarV2

/**
 * Résout le calendrier des jours fériés pour l'ancien chemin Salaire sans réactiver
 * silencieusement une entreprise V2 supprimée ou un store entreprises corrompu.
 */
object LegacySalaryHolidayScopeResolverV2 {
    data class Resolution(
        val scope: FrenchPublicHolidayCalendarV2.Scope?,
        val companyStoreReliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        stored: SalaryCompanyStore.ReadResult,
        companyId: String?
    ): Resolution {
        val id = companyId.orEmpty().trim()
        if (id.isBlank()) {
            return Resolution(
                scope = null,
                companyStoreReliable = true,
                warnings = emptyList()
            )
        }

        val unknownScope = FrenchPublicHolidayCalendarV2.scopeForAddress("")
        if (!stored.reliable) {
            return Resolution(
                scope = unknownScope,
                companyStoreReliable = false,
                warnings = (
                    stored.warnings +
                        "Salaire historique : stockage entreprises non fiable ; le calendrier territorial ne peut pas être confirmé et le brut reste à vérifier."
                    ).distinct()
            )
        }

        val company = stored.companies.firstOrNull { it.id == id }
        if (company == null) {
            return Resolution(
                scope = unknownScope,
                companyStoreReliable = true,
                warnings = listOf(
                    "Salaire historique : ancien employeur non rattaché à une entreprise V2 confirmée ; calendrier territorial traité comme adresse inconnue."
                )
            )
        }

        return Resolution(
            scope = FrenchPublicHolidayCalendarV2.scopeForAddress(company.address),
            companyStoreReliable = true,
            warnings = emptyList()
        )
    }
}
