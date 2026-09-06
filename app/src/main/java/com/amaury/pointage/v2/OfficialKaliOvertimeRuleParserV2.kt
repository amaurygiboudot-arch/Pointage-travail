package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.OvertimeTierV2
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Transforme un article KALI consulté en barème uniquement lorsque le texte officiel contient
 * explicitement un seuil et des taux suffisamment complets. Aucun taux n'est complété par défaut.
 */
object OfficialKaliOvertimeRuleParserV2 {
    data class VerifiedArticle(
        val articleId: String,
        val status: String,
        val content: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val title: String?
    )

    data class ParsedSchedule(
        val article: VerifiedArticle,
        val weeklyRegularMinutes: Int,
        val tiers: List<OvertimeTierV2>
    ) {
        val fingerprint: String = buildString {
            append(weeklyRegularMinutes)
            append('|')
            tiers.forEach { tier ->
                append(tier.fromMinutes).append(':')
                append(tier.toMinutes ?: -1).append(':')
                append(tier.multiplier).append(';')
            }
        }
    }

    enum class DiagnosticKind {
        COMPLETE_SCHEDULE,
        EXPLICIT_RATES_WITHOUT_35H,
        THIRTY_FIVE_WITHOUT_STRUCTURED_RATES,
        LEGAL_REFERENCE_ONLY,
        OTHER
    }

    data class ArticleDiagnostic(
        val article: VerifiedArticle,
        val kind: DiagnosticKind,
        val schedule: ParsedSchedule? = null,
        val percentages: List<Double> = emptyList(),
        val hourThresholds: List<Int> = emptyList()
    )

    fun parseApplicableArticle(
        data: Any?,
        expectedArticleId: String,
        referenceDate: LocalDate
    ): VerifiedArticle? {
        if (!isKaliArticleId(expectedArticleId)) return null
        val root = data as? Map<*, *> ?: return null
        val article = findMap(
            root,
            accept = { map ->
                firstString(map, "id", "cid")?.equals(expectedArticleId, ignoreCase = true) == true
            }
        ) ?: return null

        val explicitId = firstString(article, "id", "cid")?.uppercase(Locale.ROOT) ?: return null
        if (explicitId != expectedArticleId.uppercase(Locale.ROOT)) return null

        val content = firstString(article, "content", "contenu", "texte", "texteHtml")
            ?.let(::cleanHtml)
            ?.takeIf { it.isNotBlank() } ?: return null
        val status = (
            firstString(article, "etat", "status", "legalStatus")
                ?: firstString(root, "etat", "status", "legalStatus")
            )?.uppercase(Locale.ROOT)?.trim() ?: return null
        if (status !in activeStatuses) return null

        val from = firstDate(article, "dateDebut", "dateDebutVersion", "dateStart", "startDate")
            ?: firstDate(root, "dateDebut", "dateDebutVersion", "dateStart", "startDate")
            ?: return null
        val to = firstDate(article, "dateFin", "dateFinVersion", "dateEnd", "endDate")
            ?: firstDate(root, "dateFin", "dateFinVersion", "dateEnd", "endDate")

        if (referenceDate.isBefore(from) || (to != null && referenceDate.isAfter(to))) return null
        if (status == "VIGUEUR_DIFF" && referenceDate.isBefore(from)) return null

        val title = firstString(article, "titre", "title", "num", "numero")
            ?: firstString(root, "titre", "title")
        return VerifiedArticle(explicitId, status, content, from, to, title)
    }

    /**
     * Pour l'instant on ne structure automatiquement que les formulations qui confirment clairement
     * le seuil de 35 h et soit un taux unique pour toutes les heures supplémentaires, soit deux
     * tranches 36e-43e puis au-delà. Toute autre rédaction reste une preuve à examiner, jamais un calcul.
     */
    fun parseCompleteSchedule(article: VerifiedArticle): ParsedSchedule? = analyzeArticle(article).schedule

    /**
     * Classe les articles applicables sans jamais transformer un indice juridique en règle de calcul.
     * Ce diagnostic permet notamment de distinguer un ancien seuil chiffré avec des taux explicites
     * d'un véritable barème 35 h exploitable en 2026.
     */
    fun analyzeArticle(article: VerifiedArticle): ArticleDiagnostic {
        val text = normalize(article.content)
        if (!mentionsOvertime(text)) {
            return ArticleDiagnostic(article, DiagnosticKind.OTHER)
        }

        val percentages = extractPercentages(text)
        val thresholds = extractHourThresholds(text)
        val confirms35 = confirmsThirtyFiveHourThreshold(text)

        if (confirms35) {
            parseTwoTierSchedule(text)?.let { tiers ->
                val schedule = ParsedSchedule(article, 35 * 60, tiers)
                return ArticleDiagnostic(
                    article = article,
                    kind = DiagnosticKind.COMPLETE_SCHEDULE,
                    schedule = schedule,
                    percentages = percentages,
                    hourThresholds = thresholds
                )
            }
            parseSingleRateSchedule(text)?.let { tier ->
                val schedule = ParsedSchedule(article, 35 * 60, listOf(tier))
                return ArticleDiagnostic(
                    article = article,
                    kind = DiagnosticKind.COMPLETE_SCHEDULE,
                    schedule = schedule,
                    percentages = percentages,
                    hourThresholds = thresholds
                )
            }
            return ArticleDiagnostic(
                article = article,
                kind = if (referencesCurrentLaw(text) && percentages.isEmpty()) {
                    DiagnosticKind.LEGAL_REFERENCE_ONLY
                } else {
                    DiagnosticKind.THIRTY_FIVE_WITHOUT_STRUCTURED_RATES
                },
                percentages = percentages,
                hourThresholds = thresholds
            )
        }

        if (percentages.isNotEmpty()) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.EXPLICIT_RATES_WITHOUT_35H,
                percentages = percentages,
                hourThresholds = thresholds
            )
        }

        if (referencesCurrentLaw(text)) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.LEGAL_REFERENCE_ONLY,
                percentages = percentages,
                hourThresholds = thresholds
            )
        }

        return ArticleDiagnostic(
            article = article,
            kind = DiagnosticKind.OTHER,
            percentages = percentages,
            hourThresholds = thresholds
        )
    }

    private fun parseTwoTierSchedule(text: String): List<OvertimeTierV2>? {
        val firstAnchors = listOf(
            "huit premieres heures supplementaires",
            "8 premieres heures supplementaires",
            "de la 36e",
            "de la 36eme",
            "36e heure"
        )
        val secondAnchors = listOf(
            "heures suivantes",
            "au dela de la 43e",
            "au-dela de la 43e",
            "a partir de la 44e",
            "44e heure"
        )
        val first = percentageAfterAnyAnchor(text, firstAnchors) ?: return null
        val second = percentageAfterAnyAnchor(text, secondAnchors) ?: return null
        if (first <= 0.0 || second <= 0.0) return null
        return listOf(
            OvertimeTierV2(35 * 60, 43 * 60, 1.0 + first / 100.0),
            OvertimeTierV2(43 * 60, null, 1.0 + second / 100.0)
        )
    }

    private fun parseSingleRateSchedule(text: String): OvertimeTierV2? {
        val anchors = listOf(
            "toutes les heures supplementaires",
            "chaque heure supplementaire",
            "l'ensemble des heures supplementaires",
            "ensemble des heures supplementaires"
        )
        val percentage = percentageAfterAnyAnchor(text, anchors) ?: return null
        if (percentage <= 0.0) return null
        return OvertimeTierV2(35 * 60, null, 1.0 + percentage / 100.0)
    }

    private fun percentageAfterAnyAnchor(text: String, anchors: List<String>): Double? {
        val position = anchors.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
        val window = text.substring(position, minOf(text.length, position + 320))
        return percentRegex.find(window)
            ?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..200.0 }
    }

    private fun extractPercentages(text: String): List<Double> = percentRegex.findAll(text)
        .mapNotNull { match ->
            match.groupValues.getOrNull(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.0..200.0 }
        }
        .distinct()
        .take(8)
        .toList()

    private fun extractHourThresholds(text: String): List<Int> = hourThresholdRegex.findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .filter { it in 20..60 }
        .distinct()
        .take(8)
        .toList()

    private fun mentionsOvertime(text: String): Boolean =
        text.contains("heure supplementaire") || text.contains("heures supplementaires")

    private fun referencesCurrentLaw(text: String): Boolean =
        text.contains("legislation en vigueur") ||
            text.contains("dispositions legales") ||
            text.contains("dispositions reglementaires") ||
            text.contains("code du travail")

    private fun confirmsThirtyFiveHourThreshold(text: String): Boolean =
        Regex("(?:au[- ]dela de|a partir de|apres|superieure?s? a)\\s*35\\s*(?:h|heures?)").containsMatchIn(text) ||
            text.contains("35 heures") || text.contains("35 h") ||
            text.contains("36e heure") || text.contains("de la 36e") || text.contains("de la 36eme")

    private val percentRegex = Regex("(\\d{1,3}(?:[,.]\\d+)?)\\s*%")
    private val hourThresholdRegex = Regex("\\b(\\d{2})\\s*(?:h|heures?)\\b")
    private val activeStatuses = setOf("VIGUEUR", "VIGUEUR_ETEN", "VIGUEUR_NON_ETEN", "VIGUEUR_DIFF")

    private fun firstDate(map: Map<*, *>, vararg keys: String): LocalDate? =
        keys.firstNotNullOfOrNull { key -> firstString(map, key)?.let(::parseDate) }

    private fun parseDate(raw: String): LocalDate? {
        val value = raw.trim()
        if (value.isBlank() || value.equals("null", true)) return null
        value.toLongOrNull()?.takeIf { it > 0L }?.let { epoch ->
            val millis = if (epoch < 10_000_000_000L) epoch * 1000L else epoch
            return runCatching {
                Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            }.getOrNull()
        }
        runCatching { return LocalDate.parse(value.take(10)) }
        runCatching { return LocalDate.parse(value.take(10), DateTimeFormatter.ofPattern("dd/MM/yyyy")) }
        return null
    }

    private fun firstString(map: Map<*, *>, vararg keys: String): String? {
        keys.forEach { wanted ->
            map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }
                ?.value?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun findMap(value: Any?, accept: (Map<*, *>) -> Boolean, depth: Int = 0): Map<*, *>? {
        if (depth > 8) return null
        return when (value) {
            is Map<*, *> -> if (accept(value)) value else value.values.firstNotNullOfOrNull { findMap(it, accept, depth + 1) }
            is List<*> -> value.firstNotNullOfOrNull { findMap(it, accept, depth + 1) }
            else -> null
        }
    }

    private fun isKaliArticleId(value: String): Boolean =
        value.startsWith("KALIARTI") && value.drop(8).isNotBlank() && value.drop(8).all(Char::isDigit)

    private fun cleanHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()
}
