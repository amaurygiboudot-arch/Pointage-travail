package com.amaury.pointage

/**
 * Résout l'identité d'une entreprise issue du flux explicite AJOUTER UNE ENTREPRISE.
 *
 * Le SIRET identifie l'établissement, mais l'ID HoraTrack déjà stocké reste stable : retrouver le
 * même SIRET ne doit jamais déplacer les préférences ni casser le rattachement des anciennes sessions.
 */
internal object SalaryCompanyIdentityResolverV2 {
    enum class Failure { UNRELIABLE_STORE, INVALID_IDENTITY, AMBIGUOUS_SIRET }

    data class Resolution(
        val company: SalaryCompanyStore.Company?,
        val existing: Boolean,
        val failure: Failure? = null
    )

    fun resolve(
        stored: SalaryCompanyStore.ReadResult,
        incoming: SalaryCompanyStore.Company
    ): Resolution {
        if (!stored.reliable) return Resolution(null, false, Failure.UNRELIABLE_STORE)

        val incomingId = incoming.id.trim()
        if (incomingId.isBlank()) return Resolution(null, false, Failure.INVALID_IDENTITY)

        val rawSiret = incoming.siret.trim()
        val incomingSiret = rawSiret.filter(Char::isDigit)
        if (rawSiret.isNotBlank() && incomingSiret.length != 14) {
            return Resolution(null, false, Failure.INVALID_IDENTITY)
        }

        val exactId = stored.companies.firstOrNull { it.id == incomingId }
        val bySiret = if (incomingSiret.length == 14) {
            stored.companies.filter { it.siret.filter(Char::isDigit) == incomingSiret }
        } else {
            emptyList()
        }
        if (bySiret.size > 1) return Resolution(null, false, Failure.AMBIGUOUS_SIRET)

        val siretMatch = bySiret.singleOrNull()
        if (exactId != null && siretMatch != null && exactId.id != siretMatch.id) {
            return Resolution(null, false, Failure.AMBIGUOUS_SIRET)
        }

        val existing = exactId ?: siretMatch
        if (existing == null) {
            return Resolution(
                company = incoming.copy(
                    id = incomingId,
                    siret = incomingSiret.takeIf { it.length == 14 }.orEmpty()
                ),
                existing = false
            )
        }

        return Resolution(
            company = incoming.copy(
                id = existing.id,
                name = incoming.name.ifBlank { existing.name },
                siret = incomingSiret.takeIf { it.length == 14 } ?: existing.siret,
                address = incoming.address.ifBlank { existing.address },
                conventionName = incoming.conventionName.ifBlank { existing.conventionName },
                idcc = incoming.idcc.ifBlank { existing.idcc }
            ),
            existing = true
        )
    }
}
