package com.amaury.pointage.v2

import java.text.Normalizer
import java.util.Locale

/**
 * Accès JORF pour la piste d'audit Salaire V2.
 *
 * Le Journal officiel sert ici à repérer et confirmer des publications législatives/réglementaires
 * potentiellement utiles à la paie. Aucun taux ni aucune règle chiffrée n'est déduit du seul titre.
 */
object OfficialJorfSourceV2 {
    data class Container(
        val containerId: String,
        val title: String?,
        val publicationDate: String?,
        val number: String?
    )

    data class Candidate(
        val textCid: String,
        val title: String,
        val nature: String?,
        val legalState: String?,
        val ministry: String?,
        val containerId: String,
        val publicationDate: String?
    )

    data class Document(
        val textCid: String?,
        val technicalId: String?,
        val title: String,
        val nature: String?,
        val legalState: String?,
        val nor: String?,
        val publicationDate: String?,
        val publicationNumber: String?,
        val hasContent: Boolean
    )

    fun lastJoBody(count: Int = 60): Map<String, Any> =
        mapOf("nbElement" to count.coerceIn(1, 100))

    fun containerBody(containerId: String, pageSize: Int = 100): Map<String, Any> {
        val id = normalizeOfficialId(containerId, "JORFCONT")
            ?: throw IllegalArgumentException("Identifiant JORFCONT invalide")
        return mapOf(
            "highlightActivated" to false,
            "id" to id,
            "pageNumber" to 1,
            "pageSize" to pageSize.coerceIn(1, 100)
        )
    }

    fun documentBody(textCid: String): Map<String, Any> {
        val cid = normalizeOfficialId(textCid, "JORFTEXT")
            ?: throw IllegalArgumentException("Identifiant JORFTEXT invalide")
        return mapOf("textCid" to cid)
    }

    fun parseLastContainers(data: Any?): List<Container> {
        val root = data as? Map<*, *> ?: return emptyList()
        val containers = mapValue(root, "containers") as? List<*> ?: return emptyList()
        return containers.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val id = value(map, "id", "cid")?.let { normalizeOfficialId(it, "JORFCONT") }
                ?: return@mapNotNull null
            Container(
                containerId = id,
                title = value(map, "titre", "title")?.takeIf { it.isNotBlank() },
                publicationDate = value(map, "relevantDate", "datePubli", "datePublication")
                    ?.takeIf { it.isNotBlank() },
                number = value(map, "num", "numero")?.takeIf { it.isNotBlank() }
            )
        }.distinctBy { it.containerId }
    }

    fun parseContainerCandidates(
        data: Any?,
        fallbackContainerId: String,
        fallbackPublicationDate: String? = null
    ): List<Candidate> {
        val root = data as? Map<*, *> ?: return emptyList()
        val items = (mapValue(root, "items") as? List<*>) ?: listOf(root)
        val result = mutableListOf<Candidate>()

        items.forEach { rawItem ->
            val item = rawItem as? Map<*, *> ?: return@forEach
            val joCont = (mapValue(item, "joCont") as? Map<*, *>) ?: item
            val containerId = value(joCont, "id", "cid")
                ?.let { normalizeOfficialId(it, "JORFCONT") }
                ?: normalizeOfficialId(fallbackContainerId, "JORFCONT")
                ?: return@forEach
            val publicationDate = value(joCont, "datePubli", "relevantDate", "datePublication")
                ?.takeIf { it.isNotBlank() } ?: fallbackPublicationDate
            val structure = mapValue(joCont, "structure") as? Map<*, *> ?: return@forEach
            collectStructureLinks(structure, containerId, publicationDate, result)
        }

        return result.distinctBy { it.textCid }
    }

    private fun collectStructureLinks(
        structure: Map<*, *>,
        containerId: String,
        publicationDate: String?,
        out: MutableList<Candidate>
    ) {
        (mapValue(structure, "liens") as? List<*>)?.forEach { raw ->
            parseLink(raw, containerId, publicationDate)?.let(out::add)
        }
        (mapValue(structure, "tms") as? List<*>)?.forEach { raw ->
            collectTms(raw, containerId, publicationDate, out)
        }
    }

    private fun collectTms(
        raw: Any?,
        containerId: String,
        publicationDate: String?,
        out: MutableList<Candidate>
    ) {
        val tms = raw as? Map<*, *> ?: return
        (mapValue(tms, "liensTxt") as? List<*>)?.forEach { link ->
            parseLink(link, containerId, publicationDate)?.let(out::add)
        }
        (mapValue(tms, "tms") as? List<*>)?.forEach { child ->
            collectTms(child, containerId, publicationDate, out)
        }
    }

    private fun parseLink(raw: Any?, containerId: String, publicationDate: String?): Candidate? {
        val map = raw as? Map<*, *> ?: return null
        val cid = value(map, "id", "cid", "textCid")
            ?.let { normalizeOfficialId(it, "JORFTEXT") } ?: return null
        val title = value(map, "titre", "title")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return Candidate(
            textCid = cid,
            title = title,
            nature = value(map, "nature")?.takeIf { it.isNotBlank() },
            legalState = value(map, "etat", "legalStatus")?.takeIf { it.isNotBlank() },
            ministry = value(map, "ministere", "emetteur", "autorite")?.takeIf { it.isNotBlank() },
            containerId = containerId,
            publicationDate = publicationDate
        )
    }

    fun parseDocument(data: Any?): Document? {
        val root = data as? Map<*, *> ?: return null
        val cid = value(root, "cid", "textCid")?.let { normalizeOfficialId(it, "JORFTEXT") }
        val technicalId = value(root, "id")?.takeIf { it.isNotBlank() }
        val title = value(root, "title", "jorfText", "titre")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return Document(
            textCid = cid,
            technicalId = technicalId,
            title = title,
            nature = value(root, "nature")?.takeIf { it.isNotBlank() },
            legalState = value(root, "etat", "jurisState")?.takeIf { it.isNotBlank() },
            nor = value(root, "nor")?.takeIf { it.isNotBlank() },
            publicationDate = value(root, "dateParution", "datePubli")?.takeIf { it.isNotBlank() },
            publicationNumber = value(root, "numParution", "textNumber")?.takeIf { it.isNotBlank() },
            hasContent = hasDocumentContent(root)
        )
    }

    /**
     * Filtre documentaire prudent : JORF sert ici à repérer des règles générales utiles à la paie
     * privée. Les actes individuels et mesures sectorielles de fonction publique ne doivent pas
     * polluer le moteur simplement parce que leur titre contient « retraite », « prime » ou
     * « indemnité ».
     */
    fun isPayrollRelevant(candidate: Candidate): Boolean {
        val title = normalize(candidate.title)
        if (EXCLUDED_TITLE_PATTERNS.any(title::contains)) return false
        return STRONG_PAYROLL_KEYWORDS.any(title::contains)
    }

    private fun hasDocumentContent(root: Map<*, *>): Boolean {
        if (value(root, "notice", "resume", "visa", "observations")?.isNotBlank() == true) return true
        val articles = mapValue(root, "articles") as? List<*>
        if (!articles.isNullOrEmpty() && articles.any(::containsTextualContent)) return true
        val sections = mapValue(root, "sections") as? List<*>
        if (!sections.isNullOrEmpty() && sections.any(::containsTextualContent)) return true
        return false
    }

    private fun containsTextualContent(raw: Any?): Boolean = when (raw) {
        is Map<*, *> -> {
            raw.entries.any { (key, value) ->
                val name = key?.toString().orEmpty().lowercase(Locale.ROOT)
                when {
                    name in setOf("texte", "textehtml", "content", "contenu") && value?.toString()?.isNotBlank() == true -> true
                    value is Map<*, *> || value is List<*> -> containsTextualContent(value)
                    else -> false
                }
            }
        }
        is List<*> -> raw.any(::containsTextualContent)
        else -> false
    }

    private fun normalizeOfficialId(value: String, prefix: String): String? {
        val normalized = value.trim().uppercase(Locale.ROOT)
        return normalized.takeIf { it.startsWith(prefix) && it.removePrefix(prefix).all(Char::isDigit) && it.length > prefix.length }
    }

    private fun mapValue(map: Map<*, *>, wanted: String): Any? =
        map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }?.value

    private fun value(map: Map<*, *>, vararg keys: String): String? {
        keys.forEach { key ->
            map.entries.firstOrNull { it.key?.toString()?.equals(key, ignoreCase = true) == true }
                ?.value?.toString()?.let { return it }
        }
        return null
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.FRANCE),
        Normalizer.Form.NFD
    ).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val STRONG_PAYROLL_KEYWORDS = listOf(
        "salaire", "remuneration", "smic", "salaire minimum", "minima salariaux",
        "prime", "cotisation", "heures supplementaires", "temps de travail", "duree du travail",
        "conge paye", "conges payes", "repos compensateur", "rtt", "indemnite", "majoration",
        "travail de nuit", "paie", "bulletin de paie", "apprentissage", "alternance", "licenciement"
    )

    private val EXCLUDED_TITLE_PATTERNS = listOf(
        "admission a la retraite",
        "radiation des cadres",
        "nomination",
        "liste des postes difficiles",
        "prix de specialites pharmaceutiques",
        "prix des specialites pharmaceutiques",
        "fonctionnaires",
        "magistrature",
        "police nationale",
        "tribunaux administratifs",
        "cours administratives d'appel",
        "agents du ministere"
    )
}
