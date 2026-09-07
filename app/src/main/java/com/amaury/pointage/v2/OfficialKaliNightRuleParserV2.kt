package com.amaury.pointage.v2

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * Analyse prudente d'un article KALI relatif au travail de nuit.
 *
 * Un article ne devient calculable que si une plage horaire unique et un taux unique sont explicites,
 * sans condition/catégorie détectée. Cela ne suffit pas à l'auto-enregistrer : l'audit KALI doit encore
 * vérifier l'unicité du candidat et la complétude de la recherche/consultation officielles.
 */
object OfficialKaliNightRuleParserV2 {
    data class NightWindow(
        val startMinute: Int,
        val endMinute: Int
    ) {
        init {
            require(startMinute in 0 until 24 * 60)
            require(endMinute in 0 until 24 * 60)
            require(startMinute != endMinute)
        }
    }

    data class StructuredCandidate(
        val article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        val window: NightWindow,
        val percentage: Double,
        val multiplier: Double = 1.0 + percentage / 100.0
    ) {
        /** Le moteur V2 sait désormais calculer les minutes depuis cette plage exacte. */
        val calculationReady: Boolean get() = true
    }

    enum class DiagnosticKind {
        STRUCTURED_CANDIDATE,
        CONDITIONAL_RULE,
        RATE_WITHOUT_WINDOW,
        MULTIPLE_RATES,
        NIGHT_WITHOUT_RATE,
        OTHER
    }

    data class ArticleDiagnostic(
        val article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        val kind: DiagnosticKind,
        val candidate: StructuredCandidate? = null,
        val percentages: List<Double> = emptyList(),
        val windows: List<NightWindow> = emptyList(),
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
        if (!mentionsNightWork(text)) {
            return ArticleDiagnostic(article, DiagnosticKind.OTHER)
        }

        val percentages = extractPercentages(text)
        val windows = extractWindows(text)
        val conditional = conditionalMarkers.filter { text.contains(it) }

        if (percentages.isEmpty()) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.NIGHT_WITHOUT_RATE,
                windows = windows,
                conditionalMarkers = conditional
            )
        }

        if (percentages.size > 1) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.MULTIPLE_RATES,
                percentages = percentages,
                windows = windows,
                conditionalMarkers = conditional
            )
        }

        if (conditional.isNotEmpty() || windows.size > 1) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.CONDITIONAL_RULE,
                percentages = percentages,
                windows = windows,
                conditionalMarkers = conditional
            )
        }

        if (windows.size != 1) {
            return ArticleDiagnostic(
                article = article,
                kind = DiagnosticKind.RATE_WITHOUT_WINDOW,
                percentages = percentages,
                windows = windows
            )
        }

        val percentage = percentages.single()
        val candidate = StructuredCandidate(
            article = article,
            window = windows.single(),
            percentage = percentage
        )
        return ArticleDiagnostic(
            article = article,
            kind = DiagnosticKind.STRUCTURED_CANDIDATE,
            candidate = candidate,
            percentages = percentages,
            windows = windows
        )
    }

    private fun mentionsNightWork(text: String): Boolean =
        text.contains("travail de nuit") ||
            text.contains("heures de nuit") ||
            text.contains("heure de nuit") ||
            text.contains("travail nocturne") ||
            text.contains("heures nocturnes") ||
            text.contains("heure nocturne") ||
            (text.contains("nuit") && text.contains("majoration"))

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

    private fun extractWindows(text: String): List<NightWindow> = windowRegex.findAll(text)
        .mapNotNull { match ->
            val startHour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val startMinute = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val endHour = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
            val endMinute = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            if (startHour !in 0..23 || endHour !in 0..23 || startMinute !in 0..59 || endMinute !in 0..59) {
                return@mapNotNull null
            }
            runCatching {
                NightWindow(startHour * 60 + startMinute, endHour * 60 + endMinute)
            }.getOrNull()
        }
        .distinct()
        .take(4)
        .toList()

    private val percentRegex = Regex("(\\d{1,3}(?:[,.]\\d+)?)\\s*%")

    /**
     * Exemples acceptés : « de 21 h à 6 h », « entre 21h30 et 6h00 », « de 22 heures à 5 heures ».
     * On ne tente pas d'interpréter une plage sans unité horaire explicite.
     */
    private val windowRegex = Regex(
        "(?:de|entre)\\s*(\\d{1,2})\\s*(?:h|heures?)(?:\\s*(\\d{1,2}))?\\s*(?:a|et|[-–])\\s*" +
            "(\\d{1,2})\\s*(?:h|heures?)(?:\\s*(\\d{1,2}))?"
    )

    /** Ces marqueurs empêchent de réduire une règle conditionnelle à un simple couple plage/taux. */
    private val conditionalMarkers = listOf(
        "travailleur de nuit",
        "travailleurs de nuit",
        "poste de nuit",
        "equipe de nuit",
        "travail habituel de nuit",
        "travail exceptionnel de nuit",
        "au moins",
        "plus de",
        "moins de",
        "sous reserve",
        "repos compensateur",
        "selon les conditions",
        "par accord d'entreprise",
        "par accord d’établissement",
        "par accord d'etablissement",
        "selon la categorie",
        "selon le coefficient",
        "cumul"
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
