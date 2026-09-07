package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.WeekdayPremiumKindV2
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * Structure uniquement une majoration simple portant sur toutes les heures payées du samedi ou du dimanche.
 * Toute plage horaire, autre jour, jour férié, catégorie ou condition détectée bloque l'auto-application.
 */
object OfficialKaliWeekdayPremiumRuleParserV2 {
    data class StructuredCandidate(
        val article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        val kind: WeekdayPremiumKindV2,
        val percentage: Double,
        val multiplier: Double = 1.0 + percentage / 100.0
    ) {
        val calculationReady: Boolean get() = true
    }

    enum class DiagnosticKind {
        STRUCTURED_CANDIDATE,
        CONDITIONAL_RULE,
        MULTIPLE_RATES,
        DAY_WITHOUT_RATE,
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

    fun analyzeArticle(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        target: WeekdayPremiumKindV2
    ): ArticleDiagnostic {
        val text = normalize(listOfNotNull(article.title, article.content).joinToString(" "))
        if (!mentionsTargetDay(text, target)) {
            return ArticleDiagnostic(article, DiagnosticKind.OTHER)
        }

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
            if (hourRangeRegex.containsMatchIn(text)) add("plage horaire")
            when (target) {
                WeekdayPremiumKindV2.SATURDAY -> if (text.contains("dimanche")) add("dimanche")
                WeekdayPremiumKindV2.SUNDAY -> if (text.contains("samedi")) add("samedi")
            }
            if (text.contains("jour ferie") || text.contains("jours feries")) add("jour férié")
        }.distinct()

        if (percentages.isEmpty()) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.DAY_WITHOUT_RATE,
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

        if (conditions.isNotEmpty()) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.CONDITIONAL_RULE,
                percentages = percentages,
                conditionalMarkers = conditions
            )
        }

        val percentage = percentages.single()
        val candidate = StructuredCandidate(article, target, percentage)
        return ArticleDiagnostic(
            article = article,
            kind = DiagnosticKind.STRUCTURED_CANDIDATE,
            candidate = candidate,
            percentages = percentages
        )
    }

    private fun mentionsTargetDay(text: String, target: WeekdayPremiumKindV2): Boolean = when (target) {
        WeekdayPremiumKindV2.SATURDAY -> text.contains("samedi")
        WeekdayPremiumKindV2.SUNDAY -> text.contains("dimanche")
    }

    private val percentRegex = Regex("(\\d{1,3}(?:[,.]\\d+)?)\\s*%")

    private val hourRangeRegex = Regex(
        "(?:de|entre)\\s*\\d{1,2}\\s*(?:h|heures?)(?:\\s*\\d{1,2})?\\s*(?:a|et|[-–])\\s*" +
            "\\d{1,2}\\s*(?:h|heures?)(?:\\s*\\d{1,2})?"
    )

    private val genericConditionalMarkers = listOf(
        "exceptionnel",
        "exceptionnelle",
        "exceptionnellement",
        "habituel",
        "habituelle",
        "occasionnel",
        "occasionnelle",
        "volontaire",
        "sur volontariat",
        "au moins",
        "plus de",
        "moins de",
        "a partir de",
        "sous reserve",
        "repos compensateur",
        "selon les conditions",
        "selon la categorie",
        "selon le coefficient",
        "par accord d'entreprise",
        "par accord d'etablissement"
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
