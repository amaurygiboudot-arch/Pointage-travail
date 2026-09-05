package com.amaury.pointage.v2

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Accès BOCC pour la piste d'audit conventionnelle Salaire V2.
 *
 * Cette couche sélectionne des publications potentiellement utiles à la paie mais ne transforme
 * jamais un titre BOCC ni un PDF en règle chiffrée. KALI reste la source conventionnelle consolidée.
 */
object OfficialBoccSourceV2 {
    private val intervalFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE)

    data class Candidate(
        val title: String,
        val fileName: String,
        val pathFile: String?,
        val publicationDate: String?,
        val textDate: String?,
        val bulletinNumber: String?,
        val idMainBocc: String?,
        val idccs: List<String>,
        val department: String?,
        val displaySize: String?
    )

    data class PdfMetadata(
        val fileName: String,
        val pathToFile: String,
        val title: String?,
        val publicationDate: String?,
        val bulletinNumber: String?,
        val displaySize: String?
    )

    fun listBody(
        idcc: String,
        from: LocalDate,
        to: LocalDate,
        pageSize: Int = 50,
        pageNumber: Int = 1
    ): Map<String, Any> {
        val normalizedIdcc = idcc.filter(Char::isDigit)
            .takeIf { it.isNotBlank() && it.length <= 4 && it.toIntOrNull()?.let { n -> n > 0 } == true }
            ?: throw IllegalArgumentException("IDCC BOCC invalide")
        require(!from.isAfter(to)) { "Période BOCC invalide" }
        return mapOf(
            "idccs" to listOf(normalizedIdcc.toInt().toString()),
            "intervalPublication" to "${from.format(intervalFormatter)} > ${to.format(intervalFormatter)}",
            "pageNumber" to pageNumber.coerceIn(1, 100),
            "pageSize" to pageSize.coerceIn(1, 100),
            "searchForGlobalBocc" to false,
            "searchForTextsBocc" to true,
            "sortValue" to "BOCC_SORT_DESC"
        )
    }

    fun totalResultNumber(data: Any?): Int? {
        val root = data as? Map<*, *> ?: return null
        val raw = root.entries.firstOrNull {
            it.key?.toString()?.equals("totalResultNumber", ignoreCase = true) == true
        }?.value ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            else -> raw.toString().toIntOrNull()
        }?.takeIf { it >= 0 }
    }

    fun parseCandidates(data: Any?): List<Candidate> {
        val root = data as? Map<*, *> ?: return emptyList()

        // Réponse officielle de /list/boccTexts : les textes unitaires sont directement dans "texts".
        val directTexts = root.entries.firstOrNull {
            it.key?.toString()?.equals("texts", ignoreCase = true) == true
        }?.value as? List<*>
        if (directTexts != null) {
            return directTexts.mapNotNull(::parseTextCandidate).distinctBy { it.fileName }
        }

        // Repli défensif pour l'ancienne forme boccAndTexts déjà couverte par les fixtures historiques.
        val results = root["results"] as? List<*> ?: return emptyList()
        return buildList {
            results.forEach resultLoop@{ rawResult ->
                val result = rawResult as? Map<*, *> ?: return@resultLoop
                val global = result["globalBocc"] as? Map<*, *>
                val publicationDate = value(global, "dateParution", "publicationDate", "datePublication")
                val bulletinNumber = value(global, "numParution", "numeroParution", "number")
                val texts = result["texts"] as? List<*> ?: emptyList<Any?>()
                texts.forEach textLoop@{ rawText ->
                    val candidate = parseTextCandidate(rawText) ?: return@textLoop
                    add(
                        candidate.copy(
                            publicationDate = candidate.publicationDate ?: publicationDate,
                            bulletinNumber = candidate.bulletinNumber ?: bulletinNumber
                        )
                    )
                }
            }
        }.distinctBy { it.fileName }
    }

    private fun parseTextCandidate(rawText: Any?): Candidate? {
        val text = rawText as? Map<*, *> ?: return null
        val fileName = value(text, "fileName", "filename", "id")
            ?.trim()
            ?.takeIf(::isPdfFileName) ?: return null
        val title = value(text, "enteteTitle", "enteteTitre", "title", "titre")
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val idccs = (text["idccs"] as? List<*>)
            ?.mapNotNull { raw -> raw?.toString()?.filter(Char::isDigit)?.takeIf { it.isNotBlank() } }
            ?.distinct()
            .orEmpty()
        return Candidate(
            title = title,
            fileName = fileName,
            pathFile = value(text, "pathFile", "pathToFile", "path")?.takeIf { it.isNotBlank() },
            publicationDate = value(text, "dateParution", "publicationDate", "datePublication")
                ?.takeIf { it.isNotBlank() },
            textDate = value(text, "texteDate", "textDate", "dateTexte")?.takeIf { it.isNotBlank() },
            bulletinNumber = value(text, "numParution", "numeroParution")?.takeIf { it.isNotBlank() },
            idMainBocc = value(text, "idMainBocc", "mainBoccId")?.takeIf { it.isNotBlank() },
            idccs = idccs,
            department = value(text, "department", "departement")?.takeIf { it.isNotBlank() },
            displaySize = value(text, "displaySize", "size")?.takeIf { it.isNotBlank() }
        )
    }

    fun parsePdfMetadata(data: Any?): PdfMetadata? {
        val root = data as? Map<*, *> ?: return null
        val fileName = value(root, "fileName", "filename", "id")
            ?.trim()
            ?.takeIf(::isPdfFileName) ?: return null
        val path = value(root, "pathToFile", "pathFile", "path")
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        return PdfMetadata(
            fileName = fileName,
            pathToFile = path,
            title = value(root, "title", "titre")?.takeIf { it.isNotBlank() },
            publicationDate = value(root, "dateParution", "datePubli", "publicationDate")?.takeIf { it.isNotBlank() },
            bulletinNumber = value(root, "numParution", "numeroParution", "num")?.takeIf { it.isNotBlank() },
            displaySize = value(root, "displaySize", "size")?.takeIf { it.isNotBlank() }
        )
    }

    /** Filtre documentaire uniquement : aucune règle de paie n'est déduite de ces mots-clés. */
    fun isPayrollRelevant(candidate: Candidate): Boolean {
        val text = normalize(candidate.title)
        return PAYROLL_KEYWORDS.any(text::contains)
    }

    private fun value(map: Map<*, *>?, vararg keys: String): String? {
        if (map == null) return null
        keys.forEach { wanted ->
            map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }
                ?.value?.toString()?.let { return it }
        }
        return null
    }

    private fun isPdfFileName(value: String): Boolean =
        value.length in 5..160 && value.endsWith(".pdf", ignoreCase = true) &&
            value.all { it.isLetterOrDigit() || it in "_.-" }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val PAYROLL_KEYWORDS = listOf(
        "salaire", "remuneration", "minima", "minimum", "prime", "anciennete",
        "heure", "horaire", "travail de nuit", "repos", "conge", "rtt", "indemnite",
        "panier", "majoration", "temps de travail", "astreinte", "classification", "coefficient",
        "valeur du point", "grille", "garantie", "13e mois", "treizieme mois", "repas",
        "deplacement", "frais professionnel"
    )
}
