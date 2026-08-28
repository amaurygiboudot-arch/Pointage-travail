package com.amaury.pointage.v2

/**
 * Politique centrale de migration.
 * Quand HoraTrack V2 est actif, aucun moteur métier historique ne doit écrire
 * ou recalculer les domaines déjà repris par V2. Les anciens écrans peuvent
 * rester visibles mais doivent lire les résultats V2.
 */
object V2LegacyPolicy {
    enum class Domain { POINTAGE, PAUSE, GPS, HISTORY, ANALYTICS, PAYROLL, PDF }

    fun legacyAllowed(domain: Domain): Boolean = !HoraTrackV2.ENABLED

    fun requireLegacyAllowed(domain: Domain) {
        check(legacyAllowed(domain)) {
            "Moteur historique interdit pour ${domain.name} : HoraTrack V2 est actif"
        }
    }
}
