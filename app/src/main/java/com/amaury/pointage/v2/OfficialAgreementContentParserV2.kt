package com.amaury.pointage.v2

/** Extrait le contenu d'une réponse officielle /consult/acco uniquement si son SIRET correspond. */
object OfficialAgreementContentParserV2 {
    data class VerifiedContent(
        val siret: String,
        val text: String
    )

    fun extractVerified(data: Any?, expectedSiret: String): VerifiedContent? {
        val root = data as? Map<*, *> ?: return null
        val acco = root["acco"] as? Map<*, *> ?: return null
        val expected = expectedSiret.filter(Char::isDigit)
        val actual = acco["siret"]?.toString().orEmpty().filter(Char::isDigit)
        if (expected.length != 14 || actual.length != 14 || actual != expected) return null
        val attachment = acco["attachment"] as? Map<*, *> ?: return null
        val text = attachment["content"]?.toString()?.trim().orEmpty()
        if (text.isBlank()) return null
        return VerifiedContent(siret = actual, text = text)
    }

    fun extract(data: Any?): String {
        val root = data as? Map<*, *> ?: return ""
        val acco = root["acco"] as? Map<*, *> ?: return ""
        val attachment = acco["attachment"] as? Map<*, *>
        return attachment?.get("content")?.toString()?.trim().orEmpty()
    }
}
