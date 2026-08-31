package com.amaury.pointage.v2

import android.net.Uri

/** Construit une recherche officielle Légifrance dans le fonds des accords d'entreprise. */
object OfficialCompanyAgreementSearchV2 {
    data class Request(
        val normalizedSiret: String,
        val uri: Uri
    )

    fun build(siret: String): Request? {
        val digits = siret.filter(Char::isDigit)
        if (digits.length != 14) return null

        // La recherche reste volontairement dans le fonds ACCO. Le résultat doit ensuite
        // être vérifié avant qu'une règle ne puisse influer sur un calcul de paie.
        val uri = Uri.parse("https://www.legifrance.gouv.fr/search")
            .buildUpon()
            .appendQueryParameter("fonds", "ACCO")
            .appendQueryParameter("page", "1")
            .appendQueryParameter("query", digits)
            .appendQueryParameter("searchField", "ALL")
            .appendQueryParameter("tab_selection", "acco")
            .build()

        return Request(digits, uri)
    }
}
