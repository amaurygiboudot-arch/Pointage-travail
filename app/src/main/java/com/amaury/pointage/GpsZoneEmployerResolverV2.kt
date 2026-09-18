package com.amaury.pointage

/**
 * Résolution pure du lien entre une zone GPS et l'entreprise à activer.
 *
 * L'identifiant stable V2 est prioritaire. Un ancien slot 1/2 n'est utilisé que pour
 * compatibilité lorsque la zone n'a pas encore d'identifiant stable. Une association
 * explicite qui ne peut pas être prouvée bloque l'automatisme au lieu de conserver
 * silencieusement l'entreprise précédemment active.
 */
internal sealed class GpsZoneEmployerResolutionV2 {
    /** La zone n'impose aucun employeur : conserver le choix utilisateur courant. */
    object KeepCurrent : GpsZoneEmployerResolutionV2()

    /** Entreprise explicitement résolue et confirmée dans le store V2. */
    data class UseCompany(val companyId: String) : GpsZoneEmployerResolutionV2()

    /** Association explicite présente mais impossible à certifier. */
    data class Block(val reason: String) : GpsZoneEmployerResolutionV2()
}

internal fun resolveGpsZoneEmployerV2(
    companyId: String?,
    legacyCompanySlot: Int?,
    companiesReliable: Boolean,
    confirmedCompanyIds: List<String>
): GpsZoneEmployerResolutionV2 {
    val stableId = companyId?.trim()
    if (stableId != null) {
        if (stableId.isBlank()) {
            return GpsZoneEmployerResolutionV2.Block("Identifiant d'entreprise vide")
        }
        if (!companiesReliable) {
            return GpsZoneEmployerResolutionV2.Block("Stockage entreprises V2 non fiable")
        }
        return if (stableId in confirmedCompanyIds) {
            GpsZoneEmployerResolutionV2.UseCompany(stableId)
        } else {
            GpsZoneEmployerResolutionV2.Block("Entreprise associée à la zone introuvable")
        }
    }

    if (legacyCompanySlot != null) {
        if (legacyCompanySlot !in 1..2) {
            return GpsZoneEmployerResolutionV2.Block("Ancien slot d'entreprise invalide")
        }
        if (!companiesReliable) {
            return GpsZoneEmployerResolutionV2.Block("Stockage entreprises V2 non fiable")
        }
        val migratedId = confirmedCompanyIds.getOrNull(legacyCompanySlot - 1)
            ?: return GpsZoneEmployerResolutionV2.Block("Ancienne association d'entreprise introuvable")
        return GpsZoneEmployerResolutionV2.UseCompany(migratedId)
    }

    return GpsZoneEmployerResolutionV2.KeepCurrent
}
