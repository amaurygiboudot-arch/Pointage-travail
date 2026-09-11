package com.amaury.pointage

/** Texte commun aux interfaces qui consomment le store multi-entreprises. */
internal fun salaryCompanyStorageStatusText(stored: SalaryCompanyStore.ReadResult): String? = when {
    !stored.reliable -> buildString {
        append("⚠ Stockage des entreprises incohérent. ")
        append("HoraTrack n'utilise aucune entreprise ni ancien profil de secours tant que les données ne sont pas récupérées.")
        if (stored.warnings.isNotEmpty()) {
            append('\n').append(stored.warnings.distinct().joinToString(" • "))
        }
    }
    stored.repairedFromBackup -> buildString {
        append("✓ Données entreprises restaurées automatiquement depuis la dernière copie locale valide.")
        if (stored.warnings.isNotEmpty()) {
            append('\n').append(stored.warnings.distinct().joinToString(" • "))
        }
    }
    else -> null
}
