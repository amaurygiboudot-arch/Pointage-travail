package com.amaury.pointage.v2

/** Convertit une réponse /search ACCO en candidats. Le SIRET reste à vérifier via /consult/acco. */
object OfficialAgreementSearchParserV2 {
    fun parseCandidates(data: Any?): List<CompanyAgreementStoreV2.Agreement> {
        val root = data as? Map<*, *> ?: return emptyList()
        val results = root["results"] as? List<*> ?: return emptyList()

        return results.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val id = firstString(item, "id", "cid", "idAccord")
                ?.takeIf { it.startsWith("ACCOTEXT") } ?: return@mapNotNull null
            val title = firstString(item, "titre", "title", "libelle")
                ?.takeIf { it.isNotBlank() } ?: "Accord d’entreprise"
            val signatureDate = firstString(item, "dateSignature", "signatureDate", "date")
                ?.take(10)
                ?.takeIf { it.isNotBlank() }

            CompanyAgreementStoreV2.Agreement(
                id = id,
                title = title,
                effectiveFrom = signatureDate,
                effectiveTo = null,
                sourceLabel = "Légifrance",
                status = CompanyAgreementStoreV2.Status.UNKNOWN,
                notes = "Candidat trouvé dans la recherche officielle. SIRET, contenu et période d’application à vérifier via la consultation de l’accord."
            )
        }.distinctBy { it.id }
    }

    fun parse(data: Any?, expectedSiret: String): List<CompanyAgreementStoreV2.Agreement> {
        val siret = expectedSiret.filter(Char::isDigit)
        if (siret.length != 14) return emptyList()
        val root = data as? Map<*, *> ?: return emptyList()
        val results = root["results"] as? List<*> ?: return emptyList()

        return results.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val returnedSiret = findString(item) { key -> key.contains("siret", ignoreCase = true) }
                ?.filter(Char::isDigit)
            if (returnedSiret != siret) return@mapNotNull null

            val id = firstString(item, "id", "cid", "idAccord")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = firstString(item, "titre", "title", "libelle")
                ?.takeIf { it.isNotBlank() } ?: "Accord d’entreprise"
            val signatureDate = firstString(item, "dateSignature", "signatureDate", "date")
                ?.take(10)
                ?.takeIf { it.isNotBlank() }

            CompanyAgreementStoreV2.Agreement(
                id = id,
                title = title,
                effectiveFrom = signatureDate,
                effectiveTo = null,
                sourceLabel = "Légifrance",
                status = CompanyAgreementStoreV2.Status.UNKNOWN,
                notes = "SIRET vérifié dans la réponse officielle. Contenu et période d’application à valider."
            )
        }.distinctBy { it.id }
    }

    private fun firstString(map: Map<*, *>, vararg keys: String): String? {
        keys.forEach { wanted ->
            map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }
                ?.value?.toString()?.let { return it }
        }
        return null
    }

    private fun findString(value: Any?, keyMatch: (String) -> Boolean): String? {
        return when (value) {
            is Map<*, *> -> {
                value.entries.firstOrNull { keyMatch(it.key?.toString().orEmpty()) && it.value != null }
                    ?.value?.toString()
                    ?: value.values.firstNotNullOfOrNull { findString(it, keyMatch) }
            }
            is List<*> -> value.firstNotNullOfOrNull { findString(it, keyMatch) }
            else -> null
        }
    }
}
