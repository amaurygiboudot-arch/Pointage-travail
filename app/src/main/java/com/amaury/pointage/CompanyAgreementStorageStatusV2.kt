package com.amaury.pointage

import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import com.amaury.pointage.v2.CompanyAgreementStoreV2

/** Messages communs aux interfaces qui présentent les accords et règles ACCO locales. */
internal fun companyAgreementMetadataStorageStatusText(
    stored: CompanyAgreementStoreV2.ReadResult
): String? = when {
    !stored.reliable -> buildString {
        append("⚠ Stockage des accords ACCO incohérent. ")
        append("HoraTrack n'affiche aucun accord partiellement récupéré et bloque les modifications tant que les données ne sont pas récupérées.")
        if (stored.warnings.isNotEmpty()) {
            append('\n').append(stored.warnings.distinct().joinToString(" • "))
        }
    }
    stored.repairedFromBackup -> buildString {
        append("✓ Accords ACCO restaurés automatiquement depuis la dernière copie locale valide.")
        if (stored.warnings.isNotEmpty()) {
            append('\n').append(stored.warnings.distinct().joinToString(" • "))
        }
    }
    else -> null
}

internal fun companyAgreementRuleStorageStatusText(
    stored: CompanyAgreementRuleStoreV2.ReadResult
): String? = when {
    !stored.reliable -> buildString {
        append("⚠ Stockage des règles ACCO incohérent. ")
        append("HoraTrack n'affiche ni n'utilise les règles partiellement récupérées tant que les données ne sont pas restaurées.")
        if (stored.warnings.isNotEmpty()) {
            append('\n').append(stored.warnings.distinct().joinToString(" • "))
        }
    }
    stored.repairedFromBackup -> buildString {
        append("✓ Règles ACCO restaurées automatiquement depuis la dernière copie locale valide.")
        if (stored.warnings.isNotEmpty()) {
            append('\n').append(stored.warnings.distinct().joinToString(" • "))
        }
    }
    else -> null
}

internal fun companyAgreementSearchOutcomeText(
    foundCount: Int,
    rejectedCount: Int,
    persisted: Boolean
): String {
    val found = foundCount.coerceAtLeast(0)
    val rejected = rejectedCount.coerceAtLeast(0)
    if (found == 0) return "Recherche terminée — aucun accord vérifié pour ce SIRET."
    if (!persisted) {
        return "$found accord(s) Légifrance vérifié(s), mais HoraTrack n'a pas pu les enregistrer. L'état local reste À confirmer."
    }
    return "$found accord(s) Légifrance vérifié(s) pour ce SIRET${if (rejected > 0) " — $rejected candidat(s) écarté(s)" else ""}."
}
