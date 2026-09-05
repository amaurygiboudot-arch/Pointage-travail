package com.amaury.pointage.v2

/**
 * Accès préparatoire au fonds LEGI / codes consolidés.
 *
 * Cette couche ne transforme jamais un résultat de recherche en règle de paie : elle produit
 * uniquement des candidats officiels qui doivent ensuite être consultés, datés et validés.
 */
object OfficialLegalCodeSourceV2 {
    enum class Topic(val label: String, val query: String) {
        WORKING_TIME("Durée du travail", "durée du travail"),
        OVERTIME("Heures supplémentaires", "heures supplémentaires"),
        NIGHT_WORK("Travail de nuit", "travail de nuit"),
        REST_AND_PAUSES("Repos et pauses", "repos pause"),
        PAID_LEAVE("Congés payés", "congés payés"),
        PUBLIC_HOLIDAYS("Jours fériés", "jours fériés"),
        MINIMUM_PAY("Salaire minimum", "salaire minimum")
    }

    data class Candidate(
        val articleId: String,
        val articleNumber: String?,
        val title: String?,
        val snippet: String?
    )

    data class Article(
        val articleId: String,
        val articleNumber: String?,
        val status: String?,
        val content: String,
        val effectiveFrom: String?,
        val effectiveTo: String?,
        val articleCid: String? = null
    )

    /** Corps officiel POST /search dans CODE_DATE, borné au Code du travail et à la date choisie. */
    fun searchBody(topic: Topic, atMs: Long, pageSize: Int = 10): Map<String, Any> {
        require(atMs > 0L) { "Date LEGI invalide" }
        return mapOf(
            "fond" to "CODE_DATE",
            "recherche" to mapOf(
                "champs" to listOf(
                    mapOf(
                        "typeChamp" to "ARTICLE",
                        "criteres" to listOf(
                            mapOf(
                                "typeRecherche" to "UN_DES_MOTS",
                                "valeur" to topic.query,
                                "operateur" to "ET"
                            )
                        ),
                        "operateur" to "ET"
                    )
                ),
                "filtres" to listOf(
                    mapOf("facette" to "NOM_CODE", "valeurs" to listOf("Code du travail")),
                    mapOf("facette" to "DATE_VERSION", "singleDate" to atMs),
                    mapOf("facette" to "TEXT_LEGAL_STATUS", "valeur" to "VIGUEUR")
                ),
                "pageNumber" to 1,
                "pageSize" to pageSize.coerceIn(1, 25),
                "operateur" to "ET",
                "sort" to "PERTINENCE",
                "typePagination" to "ARTICLE"
            )
        )
    }

    fun parseCandidates(data: Any?): List<Candidate> {
        val root = data as? Map<*, *> ?: return emptyList()
        val results = root["results"] as? List<*> ?: return emptyList()

        val extracts = buildList {
            results.forEach { rawResult ->
                val result = rawResult as? Map<*, *> ?: return@forEach
                val title = firstValue(result, "title", "titre", "libelle")?.takeIf { it.isNotBlank() }
                val sections = result["sections"] as? List<*> ?: emptyList<Any?>()
                sections.forEach { rawSection ->
                    val section = rawSection as? Map<*, *> ?: return@forEach
                    val sectionExtracts = section["extracts"] as? List<*> ?: emptyList<Any?>()
                    sectionExtracts.forEach { rawExtract ->
                        val extract = rawExtract as? Map<*, *> ?: return@forEach
                        val id = firstValue(extract, "id")?.takeIf(::isLegiArticleId) ?: return@forEach
                        val values = extract["values"] as? List<*>
                        val snippet = values
                            ?.joinToString(" ") { it?.toString().orEmpty() }
                            ?.let(::cleanText)
                            ?.takeIf { it.isNotBlank() }
                            ?.take(500)
                        add(
                            Candidate(
                                articleId = id,
                                articleNumber = firstValue(extract, "num", "numArticle", "numeroArticle", "numero")
                                    ?.takeIf { it.isNotBlank() },
                                title = title,
                                snippet = snippet
                            )
                        )
                    }
                }
            }
        }
        if (extracts.isNotEmpty()) return extracts.distinctBy { it.articleId }

        // Repli défensif pour les anciennes formes de réponse déjà vues dans les tests/fixtures.
        return results.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val id = findString(item) { _, value -> value.startsWith("LEGIARTI") }
                ?.takeIf(::isLegiArticleId) ?: return@mapNotNull null
            Candidate(
                articleId = id,
                articleNumber = firstValue(item, "numArticle", "numeroArticle", "num", "numero")
                    ?.takeIf { it.isNotBlank() },
                title = firstValue(item, "title", "titre", "libelle")?.takeIf { it.isNotBlank() },
                snippet = firstValue(item, "content", "contenu", "texte")
                    ?.let(::cleanText)
                    ?.takeIf { it.isNotBlank() }
                    ?.take(500)
            )
        }.distinctBy { it.articleId }
    }

    /** Parse la réponse POST /consult/getArticle sans en déduire automatiquement une règle. */
    fun parseArticle(data: Any?): Article? {
        val root = data as? Map<*, *> ?: return null
        val explicitArticle = root.entries.firstOrNull {
            it.key?.toString()?.equals("article", ignoreCase = true) == true
        }?.value as? Map<*, *>
        val articleMap = explicitArticle ?: findMap(root) { map ->
            map.values.any { value -> value?.toString()?.let(::isLegiArticleId) == true }
        } ?: return null

        val id = firstValue(articleMap, "id")?.takeIf(::isLegiArticleId)
            ?: findString(articleMap) { key, value -> key.equals("id", ignoreCase = true) && isLegiArticleId(value) }
            ?: return null
        val cid = firstValue(articleMap, "cid")?.takeIf(::isLegiArticleId)
        val content = firstValue(articleMap, "texte", "content", "contenu", "texteHtml")
            ?.let(::cleanText)
            ?.takeIf { it.isNotBlank() } ?: return null
        return Article(
            articleId = id,
            articleNumber = firstValue(articleMap, "num", "numero", "numArticle", "numeroArticle")
                ?.takeIf { it.isNotBlank() },
            status = firstValue(articleMap, "etat", "status", "legalStatus")?.takeIf { it.isNotBlank() },
            content = content,
            effectiveFrom = firstValue(articleMap, "dateDebut", "dateStart", "startDate")?.takeIf { it.isNotBlank() },
            effectiveTo = firstValue(articleMap, "dateFin", "dateEnd", "endDate")?.takeIf { it.isNotBlank() },
            articleCid = cid
        )
    }

    private fun isLegiArticleId(value: String): Boolean =
        value.startsWith("LEGIARTI") && value.drop(8).isNotBlank() && value.drop(8).all(Char::isDigit)

    private fun firstValue(map: Map<*, *>, vararg keys: String): String? {
        keys.forEach { wanted ->
            map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }
                ?.value?.toString()?.let { return it }
        }
        map.values.forEach { nested ->
            when (nested) {
                is Map<*, *> -> firstValue(nested, *keys)?.let { return it }
                is List<*> -> nested.forEach { child ->
                    if (child is Map<*, *>) firstValue(child, *keys)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findString(value: Any?, accept: (String, String) -> Boolean): String? = when (value) {
        is Map<*, *> -> value.entries.firstNotNullOfOrNull { (key, raw) ->
            val text = raw as? String
            if (text != null && accept(key?.toString().orEmpty(), text)) text else findString(raw, accept)
        }
        is List<*> -> value.firstNotNullOfOrNull { findString(it, accept) }
        else -> null
    }

    private fun findMap(value: Any?, accept: (Map<*, *>) -> Boolean): Map<*, *>? = when (value) {
        is Map<*, *> -> if (accept(value)) value else value.values.firstNotNullOfOrNull { findMap(it, accept) }
        is List<*> -> value.firstNotNullOfOrNull { findMap(it, accept) }
        else -> null
    }

    private fun cleanText(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}
