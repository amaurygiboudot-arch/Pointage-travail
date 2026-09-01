package com.amaury.pointage.v2

/** Extrait uniquement le texte exploitable d'une réponse officielle /consult/acco. */
object OfficialAgreementContentParserV2 {
    fun extract(data: Any?): String {
        val root = data as? Map<*, *> ?: return ""
        val acco = root["acco"] as? Map<*, *> ?: return ""
        val attachment = acco["attachment"] as? Map<*, *>
        return attachment?.get("content")?.toString()?.trim().orEmpty()
    }
}
