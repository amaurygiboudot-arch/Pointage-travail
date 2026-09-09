package com.amaury.pointage.v2.engine

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Parseur prudent d'un document contenant potentiellement une RGDU réellement constatée.
 *
 * Le texte complet n'est jamais persisté. Une proposition n'est qu'une aide de saisie :
 * l'utilisateur doit confirmer le mois, le montant et la source avant enregistrement.
 */
object EmployerGeneralReductionObservedDocumentParserV2 {
    data class Candidate(
        val amount: Double?,
        val confidence: Double,
        val sourceLabel: String? = null
    ) {
        val highConfidence: Boolean get() = amount != null && confidence >= 0.90
    }

    data class Result(
        val rgdu: Candidate,
        val warnings: List<String>
    )

    fun parse(rawText: String): Result {
        val lines = rawText
            .replace('\u00A0', ' ')
            .lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .take(4_000)
            .toList()

        val candidate = singleRgdu(lines)
        val warnings = buildList {
            if (!candidate.highConfidence) {
                add("RGDU : aucun montant unique suffisamment explicite n'a été identifié ; confirmation manuelle requise.")
            }
        }
        return Result(candidate, warnings)
    }

    private fun singleRgdu(lines: List<String>): Candidate {
        val found = mutableListOf<Triple<Double, Double, String>>()
        lines.forEach { line ->
            val normalized = normalize(line)
            val score = rgduScore(normalized)
            if (score <= 0.0) return@forEach
            val amount = lastMoneyAmount(line) ?: return@forEach
            found += Triple(amount, score, line.take(220))
        }
        if (found.isEmpty()) return Candidate(null, 0.0)

        val sorted = found.sortedByDescending { it.second }
        val best = sorted.first()
        val competing = sorted.drop(1).firstOrNull {
            it.second >= best.second - 0.05 && abs(it.first - best.first) > 0.02
        }
        return if (competing != null) {
            Candidate(
                amount = null,
                confidence = (best.second - 0.40).coerceAtLeast(0.0),
                sourceLabel = best.third
            )
        } else {
            Candidate(
                amount = best.first,
                confidence = best.second.coerceIn(0.0, 1.0),
                sourceLabel = best.third
            )
        }
    }

    private fun rgduScore(text: String): Double {
        // Un total global d'allègements n'est jamais assimilé à la RGDU seule.
        val globalTotal =
            text.contains("total allegement") ||
                text.contains("total des allegements") ||
                text.contains("total exoneration") ||
                text.contains("total des exonerations") ||
                text.contains("total reduction employeur") ||
                text.contains("total reductions employeur") ||
                text.contains("total reductions patronales")
        if (globalTotal && !text.contains("rgdu") && !text.contains("reduction generale")) return 0.0

        return when {
            Regex("\\brgdu\\b").containsMatchIn(text) -> 1.0
            text.contains("reduction generale degressive unique") -> 1.0
            text.contains("reduction generale des cotisations patronales") -> 0.99
            text.contains("reduction generale de cotisations patronales") -> 0.99
            text.contains("reduction generale") && text.contains("cotisation") -> 0.96
            text.contains("allegement general") && text.contains("cotisation") -> 0.92
            else -> 0.0
        }
    }

    private fun lastMoneyAmount(raw: String): Double? {
        val regex = Regex(
            "(?<![\\d/])([+-]?\\d{1,3}(?:[ .\\u00A0]\\d{3})*|[+-]?\\d+)(?:[,.](\\d{2}))?\\s*(€|eur|euros?)?",
            RegexOption.IGNORE_CASE
        )
        val candidates = regex.findAll(raw).mapNotNull { match ->
            val tail = raw.substring(match.range.last + 1).trimStart()
            if (tail.startsWith("%") || tail.startsWith("/") || tail.startsWith("h", ignoreCase = true)) {
                return@mapNotNull null
            }
            val hasCurrency = match.groupValues[3].isNotBlank()
            val hasDecimals = match.groupValues[2].isNotBlank()
            if (!hasCurrency && !hasDecimals) return@mapNotNull null
            val integer = match.groupValues[1]
                .replace(" ", "")
                .replace(".", "")
                .replace("\u00A0", "")
            val decimals = match.groupValues[2].ifBlank { "00" }
            "$integer.$decimals".toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..10_000_000.0 }
        }.toList()
        return candidates.lastOrNull()
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(
            value.lowercase(Locale.FRANCE),
            Normalizer.Form.NFD
        )
        return decomposed.replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
    }
}
