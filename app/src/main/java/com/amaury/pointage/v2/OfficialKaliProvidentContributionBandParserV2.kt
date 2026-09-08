package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import kotlin.math.abs

/**
 * Parse uniquement des tranches de cotisation dont les bornes PMSS et les deux taux sont écrits.
 * Les noms « tranche A/B/T1/T2 » n'emportent aucune définition implicite.
 *
 * Le parseur est volontairement fail-closed : une tranche explicitement présente mais incomplète,
 * une contradiction de taux, une rupture de continuité ou un plafond global porté uniquement par
 * un autre article que ceux contenant les tranches bloque le barème complet.
 */
object OfficialKaliProvidentContributionBandParserV2 {
    data class Parsed(
        val mentioned: Boolean,
        val bands: List<ConventionProvidentContributionV2.Band>,
        val complete: Boolean,
        val reason: String? = null
    )

    private data class ParsedBand(
        val band: ConventionProvidentContributionV2.Band,
        val sourceText: String
    )

    private data class CapEvidence(
        val multiple: Double,
        val sourceText: String
    )

    fun parse(texts: Collection<String>): Parsed {
        val mentioned = texts.any { trancheVocabulary.containsMatchIn(it) }
        if (!mentioned) return Parsed(false, emptyList(), false)

        val namedHeaders = texts.sumOf { namedBandHeaderRegex.findAll(it).count() }
        val boundedMentions = texts.sumOf { boundedBandRegex.findAll(it).count() }
        if (namedHeaders > boundedMentions) {
            return Parsed(
                true,
                emptyList(),
                false,
                "au moins une tranche nommée est dépourvue de bornes PMSS explicites"
            )
        }

        val parsedEntries = texts.flatMap { text ->
            parseText(text).map { ParsedBand(it, text) }
        }
        if (parsedEntries.isEmpty()) {
            return Parsed(
                true,
                emptyList(),
                false,
                "tranches mentionnées mais aucune tranche à bornes et taux explicites n'est complète"
            )
        }
        if (parsedEntries.size < boundedMentions) {
            return Parsed(
                true,
                parsedEntries.map { it.band }.distinctBy(::fingerprint),
                false,
                "au moins une tranche bornée ne contient pas une répartition salarié/employeur exacte et exploitable"
            )
        }

        val contradictoryBounds = parsedEntries
            .groupBy { boundsFingerprint(it.band) }
            .values
            .any { sameBounds -> sameBounds.map { fingerprint(it.band) }.distinct().size > 1 }
        if (contradictoryBounds) {
            return Parsed(
                true,
                emptyList(),
                false,
                "des tranches de mêmes bornes portent des taux contradictoires"
            )
        }

        val unique = parsedEntries
            .map { it.band }
            .distinctBy(::boundsFingerprint)
            .sortedBy { it.lowerCeilingMultiple }
        if (unique.size < 2) {
            return Parsed(
                true,
                unique,
                false,
                "une seule tranche explicite ne suffit pas à prouver un barème multi-tranches complet"
            )
        }
        if (abs(unique.first().lowerCeilingMultiple) > EPSILON) {
            return Parsed(true, unique, false, "la première tranche ne commence pas explicitement à 0 PMSS")
        }
        unique.zipWithNext().forEach { (previous, next) ->
            val previousUpper = previous.upperCeilingMultiple
                ?: return Parsed(true, unique, false, "une tranche intermédiaire est sans borne haute")
            if (abs(previousUpper - next.lowerCeilingMultiple) > EPSILON) {
                return Parsed(true, unique, false, "les tranches explicites ne sont pas contiguës")
            }
        }

        val finalUpper = unique.last().upperCeilingMultiple
            ?: return Parsed(true, unique, false, "la dernière tranche doit avoir une borne haute explicite")
        val capEvidence = texts.flatMap { text ->
            overallCapRegex.findAll(text).mapNotNull { match ->
                parseNumber(match.groupValues[1])
                    ?.takeIf { it > 0.0 && it <= 100.0 }
                    ?.let { CapEvidence(it, text) }
            }.toList()
        }
        val capValues = capEvidence.map { it.multiple }.distinct()
        if (capValues.size != 1 || abs(capValues.single() - finalUpper) > EPSILON) {
            return Parsed(
                true,
                unique,
                false,
                "la borne haute finale n'est pas confirmée par un plafond global explicite unique"
            )
        }

        // Le parseur appelant identifie actuellement les articles de financement par la présence
        // d'au moins une tranche structurée. Pour que le statut/date d'extension du plafond global
        // soit donc nécessairement contrôlé, ce plafond doit apparaître dans au moins un article
        // qui contient lui-même une tranche structurée. Un plafond porté uniquement par un article
        // séparé reste une preuve partielle et ne ferme pas la chaîne juridique.
        val bandSourceTexts = parsedEntries.map { it.sourceText }.toSet()
        val matchingCapSourceTexts = capEvidence
            .filter { abs(it.multiple - finalUpper) <= EPSILON }
            .map { it.sourceText }
            .toSet()
        if (bandSourceTexts.intersect(matchingCapSourceTexts).isEmpty()) {
            return Parsed(
                true,
                unique,
                false,
                "le plafond global est porté uniquement par un article séparé ; chaîne de preuve dates/extension incomplète"
            )
        }

        return Parsed(true, unique, true)
    }

    private fun parseText(text: String): List<ConventionProvidentContributionV2.Band> {
        val bounds = boundedBandRegex.findAll(text).toList()
        if (bounds.isEmpty()) return emptyList()
        return bounds.mapNotNull { match ->
            val nextStart = bounds.firstOrNull { it.range.first > match.range.first }?.range?.first ?: text.length
            val end = minOf(nextStart, match.range.last + 1 + MAX_RATE_DISTANCE, text.length)
            val window = text.substring(match.range.first, end)
            if (flexibleAllocationVocabulary.containsMatchIn(window)) return@mapNotNull null

            val rates = exactRatePairs(window)
            if (rates.size != 1) return@mapNotNull null
            val lower = parseNumber(match.groupValues[2]) ?: return@mapNotNull null
            val upper = parseNumber(match.groupValues[3]) ?: return@mapNotNull null
            if (lower < 0.0 || upper <= lower || upper > 100.0) return@mapNotNull null
            val (employee, employer) = rates.single()
            val labelToken = match.groupValues[1].takeIf { it.isNotBlank() }
            ConventionProvidentContributionV2.Band(
                label = labelToken?.let { "Tranche $it — $lower à $upper PMSS" }
                    ?: "Tranche $lower à $upper PMSS",
                lowerCeilingMultiple = lower,
                upperCeilingMultiple = upper,
                employeeRate = employee,
                employerRate = employer
            ).takeIf { it.structurallyValid() }
        }
    }

    private fun exactRatePairs(text: String): List<Pair<Double, Double>> = buildList {
        employeeFirstRegex.findAll(text).forEach { match ->
            val employee = percent(match.groupValues[1]) ?: return@forEach
            val employer = percent(match.groupValues[2]) ?: return@forEach
            add(employee to employer)
        }
        employerFirstRegex.findAll(text).forEach { match ->
            val employer = percent(match.groupValues[1]) ?: return@forEach
            val employee = percent(match.groupValues[2]) ?: return@forEach
            add(employee to employer)
        }
    }.distinct()

    private fun boundsFingerprint(value: ConventionProvidentContributionV2.Band): String = listOf(
        value.lowerCeilingMultiple.toString(),
        value.upperCeilingMultiple?.toString().orEmpty()
    ).joinToString("|")

    private fun fingerprint(value: ConventionProvidentContributionV2.Band): String = listOf(
        boundsFingerprint(value),
        value.employeeRate.toString(),
        value.employerRate.toString(),
        value.allocationRule.name
    ).joinToString("|")

    private fun percent(raw: String): Double? = parseNumber(raw)
        ?.takeIf { it in 0.0..100.0 }
        ?.div(100.0)

    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private val trancheVocabulary = Regex("\\btranches?\\s*(?:[a-z0-9]+)?\\b")
    private val namedBandHeaderRegex = Regex("\\btranche\\s+[a-z0-9]+\\b")
    private val boundedBandRegex = Regex(
        "\\btranche\\s*([a-z0-9]+)?\\s*[:.\\-]?\\s*(?:de\\s+)?(\\d+(?:[.,]\\d+)?)\\s*(?:a|au|-)\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:(?:fois|x)\\s*)?(?:le\\s+)?(?:pmss|plafond(?: mensuel)?(?: de la securite sociale)?)\\b"
    )
    private val employeeFirstRegex = Regex(
        "(?:part salariale|a la charge du salarie|salarie)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%[^%]{0,100}(?:part patronale|a la charge de l'employeur|employeur)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%"
    )
    private val employerFirstRegex = Regex(
        "(?:part patronale|a la charge de l'employeur|employeur)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%[^%]{0,100}(?:part salariale|a la charge du salarie|salarie)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%"
    )
    private val flexibleAllocationVocabulary = Regex(
        "\\b(?:minimum|minimale|minimal|par defaut|a defaut d[' ]accord|accord d[' ]entreprise)\\b"
    )
    private val overallCapRegex = Regex(
        "\\b(?:assiette|cotisations?|financement|salaire de reference)[^.;]{0,120}?(?:limite[e]?|plafonne[e]?|jusqu[' ]?a|dans la limite de)\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:(?:fois|x)\\s*)?(?:le\\s+)?(?:pmss|plafond(?: mensuel)?(?: de la securite sociale)?)\\b"
    )

    private const val MAX_RATE_DISTANCE = 260
    private const val EPSILON = 1e-9
}
