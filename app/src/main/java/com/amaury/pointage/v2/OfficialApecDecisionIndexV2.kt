package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Découvre les agréments publiés dans le tableau officiel de la Commission paritaire Apec.
 *
 * Cette couche ne sélectionne jamais "le bon" agrément : pour un IDCC exact elle retourne tous
 * les PDF officiels présents dans la ligne, y compris les historiques. La validation juridique du
 * document et son rapprochement avec KALI restent des étapes séparées et fail-closed.
 */
object OfficialApecDecisionIndexV2 {
    const val INDEX_URL = "https://commission-paritaire.apec.fr/"

    data class Candidate(
        val idcc: String,
        val documentId: String,
        val sourceUrl: String,
        val linkLabel: String,
        val rowText: String
    )

    data class Diagnostic(
        val candidates: List<Candidate>,
        val reasons: List<String>
    )

    fun parse(html: String, idcc: String): Diagnostic {
        val wanted = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (wanted.isBlank()) {
            return Diagnostic(emptyList(), listOf("APEC index : IDCC invalide."))
        }
        if (html.isBlank()) {
            return Diagnostic(emptyList(), listOf("APEC index : page officielle vide."))
        }

        val matchingRows = rowRegex.findAll(html).map { it.groupValues[1] }.filter { row ->
            cellRegex.findAll(row).any { cell -> exactIdccCell(cell.groupValues[1], wanted) }
        }.toList()

        if (matchingRows.isEmpty()) {
            return Diagnostic(
                emptyList(),
                listOf("APEC index : aucune ligne ne correspond exactement à l'IDCC $wanted ; absence d'agrément non déduite.")
            )
        }

        val candidates = matchingRows.flatMap { row ->
            val rowText = plainText(row)
            anchorRegex.findAll(row).mapNotNull { match ->
                val rawHref = decodeHtml(match.groupValues[1]).trim()
                val sourceUrl = resolveOfficialPdf(rawHref) ?: return@mapNotNull null
                Candidate(
                    idcc = wanted,
                    documentId = documentId(sourceUrl),
                    sourceUrl = sourceUrl,
                    linkLabel = plainText(match.groupValues[2]),
                    rowText = rowText
                )
            }.toList()
        }.distinctBy { it.sourceUrl }

        return Diagnostic(
            candidates = candidates,
            reasons = buildList {
                if (candidates.isEmpty()) {
                    add("APEC index : ligne IDCC $wanted trouvée mais aucun PDF officiel exploitable ; catégorie non déduite du tableau seul.")
                }
                if (matchingRows.size > 1) {
                    add("APEC index : ${matchingRows.size} lignes correspondent à l'IDCC $wanted ; tous les agréments sont conservés pour validation documentaire.")
                }
                if (candidates.size > 1) {
                    add("APEC index : ${candidates.size} agréments PDF officiels trouvés ; aucune priorité n'est inventée.")
                }
            }
        )
    }

    private fun exactIdccCell(rawCell: String, wanted: String): Boolean {
        val text = plainText(rawCell).trim()
        if (!text.matches(Regex("^0*\\d{1,4}$"))) return false
        return ConventionMinimumSalaryV2.normalizeIdcc(text) == wanted
    }

    private fun resolveOfficialPdf(rawHref: String): String? = runCatching {
        val resolved = URI(INDEX_URL).resolve(rawHref)
        if (!resolved.scheme.equals("https", ignoreCase = true)) return@runCatching null
        if (!resolved.host.equals("commission-paritaire.apec.fr", ignoreCase = true)) return@runCatching null
        val path = resolved.path.orEmpty()
        if (!path.startsWith("/assets/files/", ignoreCase = true)) return@runCatching null
        if (!path.endsWith(".pdf", ignoreCase = true)) return@runCatching null
        resolved.toASCIIString()
    }.getOrNull()

    private fun documentId(sourceUrl: String): String = runCatching {
        val name = URI(sourceUrl).path.substringAfterLast('/').ifBlank { "decision.pdf" }
        URLDecoder.decode(name, StandardCharsets.UTF_8.name())
    }.getOrDefault(sourceUrl)

    private fun plainText(html: String): String = decodeHtml(
        tagRegex.replace(html, " ")
    ).replace(Regex("\\s+"), " ").trim()

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)

    private val rowRegex = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val cellRegex = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val anchorRegex = Regex(
        "<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tagRegex = Regex("<[^>]+>", setOf(RegexOption.DOT_MATCHES_ALL))
}
