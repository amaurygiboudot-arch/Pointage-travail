package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import kotlin.math.abs

/**
 * Parse uniquement des tranches de cotisation dont les bornes PMSS et les deux taux sont écrits.
 * Les noms « tranche A/B/T1/T2 » n'emportent aucune définition implicite.
 */
object OfficialKaliProvidentContributionBandParserV2 {
    data class Parsed(
        val mentioned: Boolean,
        val bands: List<ConventionProvidentContributionV2.Band>,
        val complete: Boolean,
        val reason: String? = null
    )

    fun parse(texts: Collection<String>): Parsed {
        val mentioned = texts.any { trancheVocabulary.containsMatchIn(it) }
        if (!mentioned) return Parsed(false, emptyList(), false)

        val parsedBands = texts.flatMap(::parseText)
        if (parsedBands.isEmpty()) {
            return Parsed(true, emptyList(), false, "tranches mentionnées mais aucune tranche à bornes et taux explicites n'est complète")
        }
        val unique = parsedBands.distinctBy(::fingerprint).sortedBy { it.lowerCeilingMultiple }
        if (unique.size != parsedBands.distinctBy(::fingerprint).size) {
            return Parsed(true, emptyList(), false, "tranches dupliquées ou contradictoires")
        }
        if (unique.size < 2) {
            return Parsed(true, unique, false, "une seule tranche explicite ne suffit pas à prouver un barème multi-tranches complet")
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
            ?: return Parsed(true, unique, true)
        val caps = texts.flatMap { text ->
            overallCapRegex.findAll(text).mapNotNull { match ->
                parseNumber(match.groupValues[1])?.takeIf { it > 0.0 && it <= 100.0 }
            }.toList()
        }.distinct()
        if (caps.size != 1 || abs(caps.single() - finalUpper) > EPSILON) {
            return Parsed(
                true,
                unique,
                false,
                "la borne haute finale n'est pas confirmée par un plafond global explicite"
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

    private fun fingerprint(value: ConventionProvidentContributionV2.Band): String = listOf(
        value.lowerCeilingMultiple.toString(),
        value.upperCeilingMultiple?.toString().orEmpty(),
        value.employeeRate.toString(),
        value.employerRate.toString(),
        value.allocationRule.name
    ).joinToString("|")

    private fun percent(raw: String): Double? = parseNumber(raw)
        ?.takeIf { it in 0.0..100.0 }
        ?.div(100.0)

    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private val trancheVocabulary = Regex("\\btranches?\\s*(?:[a-z0-9]+)?\\b")
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
