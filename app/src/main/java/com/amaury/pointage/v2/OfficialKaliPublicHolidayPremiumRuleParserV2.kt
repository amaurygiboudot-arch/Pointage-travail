package com.amaury.pointage.v2

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * Analyse prudente d'une majoration KALI uniforme des jours fériés.
 *
 * HoraTrack n'accepte ici qu'une règle très simple : tous les jours fériés visés par l'article,
 * un seul taux, aucune exception par jour nommé, catégorie, ancienneté, plage horaire ou cumul.
 * Les cas spéciaux restent visibles dans le diagnostic mais ne deviennent jamais calculables.
 */
object OfficialKaliPublicHolidayPremiumRuleParserV2 {
    data class StructuredCandidate(
        val article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        val percentage: Double,
        val multiplier: Double = 1.0 + percentage / 100.0
    ) {
        val calculationReady: Boolean get() = true
    }

    enum class DiagnosticKind {
        STRUCTURED_CANDIDATE,
        CONDITIONAL_RULE,
        MULTIPLE_RATES,
        HOLIDAY_WITHOUT_RATE,
        RATE_WITHOUT_SIMPLE_PREMIUM_CONTEXT,
        OTHER
    }

    data class ArticleDiagnostic(
        val article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        val kind: DiagnosticKind,
        val candidate: StructuredCandidate? = null,
        val percentages: List<Double> = emptyList(),
        val conditionalMarkers: List<String> = emptyList()
    )

    fun parseApplicableArticle(
        data: Any?,
        expectedArticleId: String,
        referenceDate: LocalDate
    ): OfficialKaliOvertimeRuleParserV2.VerifiedArticle? =
        OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(data, expectedArticleId, referenceDate)

    fun analyzeArticle(article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle): ArticleDiagnostic {
        val text = normalize(listOfNotNull(article.title, article.content).joinToString(" "))
        if (!mentionsPublicHoliday(text)) return ArticleDiagnostic(article, DiagnosticKind.OTHER)

        val percentages = percentRegex.findAll(text)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
                    ?.takeIf { it in 0.0..200.0 }
            }
            .distinct()
            .take(8)
            .toList()

        val conditions = buildList {
            genericConditionalMarkers.filterTo(this) { text.contains(it) }
            namedHolidayMarkers.filterTo(this) { text.contains(it) }
            if (hourRangeRegex.containsMatchIn(text)) add("plage horaire")
        }.distinct()

        if (percentages.isEmpty()) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.HOLIDAY_WITHOUT_RATE,
                conditionalMarkers = conditions
            )
        }
        if (percentages.size > 1) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.MULTIPLE_RATES,
                percentages = percentages,
                conditionalMarkers = conditions
            )
        }
        if (!hasSimplePremiumContext(text)) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.RATE_WITHOUT_SIMPLE_PREMIUM_CONTEXT,
                percentages = percentages,
                conditionalMarkers = conditions
            )
        }
        if (conditions.isNotEmpty()) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.CONDITIONAL_RULE,
                percentages = percentages,
                conditionalMarkers = conditions
            )
        }

        val percentage = percentages.single()
        return ArticleDiagnostic(
            article = article,
            kind = DiagnosticKind.STRUCTURED_CANDIDATE,
            candidate = StructuredCandidate(article, percentage),
            percentages = percentages
        )
    }

    private fun mentionsPublicHoliday(text: String): Boolean =
        text.contains("jour ferie") || text.contains("jours feries") || text.contains("fete legale") ||
            text.contains("fetes legales")

    private fun hasSimplePremiumContext(text: String): Boolean =
        text.contains("majoration") || text.contains("majore") || text.contains("majoree") ||
            text.contains("majorees") || text.contains("majores")

    private val percentRegex = Regex("(\\d{1,3}(?:[,.]\\d+)?)\\s*%")

    private val hourRangeRegex = Regex(
        "(?:de|entre)\\s*\\d{1,2}\\s*(?:h|heures?)(?:\\s*\\d{1,2})?\\s*(?:a|et|[-–])\\s*" +
            "\\d{1,2}\\s*(?:h|heures?)(?:\\s*\\d{1,2})?"
    )

    /** Toute mention d'un jour nommé bloque ce modèle uniforme. */
    private val namedHolidayMarkers = listOf(
        "1er mai",
        "1 mai",
        "premier mai",
        "jour de l'an",
        "nouvel an",
        "lundi de paques",
        "ascension",
        "lundi de pentecote",
        "14 juillet",
        "assomption",
        "15 aout",
        "toussaint",
        "1er novembre",
        "11 novembre",
        "noel",
        "25 decembre",
        "vendredi saint",
        "saint-etienne",
        "26 decembre"
    )

    private val genericConditionalMarkers = listOf(
        "non cumul",
        "non-cumul",
        "cumulable",
        "cumul",
        "sauf",
        "exception",
        "exceptionnel",
        "exceptionnelle",
        "anciennete",
        "au moins",
        "plus de",
        "moins de",
        "sous reserve",
        "repos compensateur",
        "selon les conditions",
        "selon la categorie",
        "selon le coefficient",
        "par accord d'entreprise",
        "par accord d'etablissement",
        "dimanche",
        "samedi"
    )

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.FRANCE),
        Normalizer.Form.NFD
    )
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()
}
