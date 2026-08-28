package com.amaury.pointage.v2

/**
 * Point d'entrée du moteur HoraTrack V2.
 * IMPORTANT : reste désactivé tant que les tests V2 ne sont pas validés.
 */
object HoraTrackV2 {
    const val ENABLED = false
    const val SCHEMA_VERSION = 1

    enum class Layer {
        TIME,
        GPS,
        COMPANY_CONTRACT,
        LEGAL_AI,
        PAYROLL,
        COUNTERS_RIGHTS,
        PAYSLIP,
        BACKUP_RESTORE
    }

    fun activeLayers(): Set<Layer> = if (ENABLED) Layer.entries.toSet() else emptySet()
}
