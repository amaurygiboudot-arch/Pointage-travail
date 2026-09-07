package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import java.time.LocalDate
import java.util.Locale

/** Parse uniquement un minimum explicitement rattaché à la classification exacte du salarié. */
object OfficialKaliMinimumSalaryParserV2 {
    data class Diagnostic(
        val articleId: String,
        val rule: ConventionMinimumSalaryV2.Rule?,
        val reasons: List<String>
    )

    private data class ParsedValue(
        val amount: Double,
        val periodicity: ConventionMinimumSalaryV2.Periodicity
    )

    fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2,
        auditDate: LocalDate
    ): Diagnostic {
        if (profile.classification.isEmpty()) return Diagnostic(article.articleId, null, listOf("classification salarié absente"))
        val raw = listOfNotNull(article.title, article.content).joinToString(" ")
        val normalized = OfficialKaliProfileMatcherV2.normalize(raw)
        if (!minimumVocabulary.any(normalized::contains)) {
            return Diagnostic(article.articleId, null, listOf("article sans vocabulaire explicite de minimum salarial"))
        }

        val windows = OfficialKaliProfileMatcherV2.windows(raw, profile.classification, profile.professionalStatus)
        if (windows.isEmpty()) {
            return Diagnostic(article.articleId, null, listOf("classification/statut exacts non isolés dans l'article"))
        }

        val parsed = windows.mapNotNull { window -> parseWindow(window.text) }.distinct()
        if (parsed.size != 1) {
            return Diagnostic(
                article.articleId,
                null,
                listOf("${parsed.size} valeur(s) minimum non ambiguë(s) autour des occurrences du profil ; règle unique non démontrée")
            )
        }

        val extensionStatus = when (article.status.uppercase(Locale.ROOT)) {
            "VIGUEUR_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
            "VIGUEUR_NON_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
            else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
        }
        val value = parsed.single()
        val rule = ConventionMinimumSalaryV2.Rule(
            idcc = profile.idcc,
            ruleId = "KALI-MIN-${article.articleId}-${profile.classification.normalized().label().hashCode().toUInt().toString(16)}",
            effectiveFrom = article.effectiveFrom,
            effectiveTo = article.effectiveTo,
            classification = profile.classification.normalized(),
            amount = value.amount,
            periodicity = value.periodicity,
            source = "Légifrance KALI — ${article.articleId}${article.title?.let { " — $it" }.orEmpty()}",
            extensionStatus = extensionStatus,
            // Le statut VIGUEUR_ETEN prouve l'extension à la date auditée, sans inventer sa date historique exacte.
            extensionEffectiveFrom = if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) auditDate else null
        )
        return if (rule.structurallyValid()) Diagnostic(article.articleId, rule, emptyList())
        else Diagnostic(article.articleId, null, listOf("règle structurée incohérente"))
    }

    private fun parseWindow(window: String): ParsedValue? {
        val amounts = euroAmountRegex.findAll(window)
            .mapNotNull { match -> parseMoney(match.groupValues[1]) }
            .filter { it > 0.0 && it < 1_000_000.0 }
            .distinct()
            .toList()
        if (amounts.size != 1) return null

        val periodicities = buildSet {
            if (monthlyWords.any(window::contains)) add(ConventionMinimumSalaryV2.Periodicity.MONTHLY)
            if (hourlyWords.any(window::contains)) add(ConventionMinimumSalaryV2.Periodicity.HOURLY)
            if (annualWords.any(window::contains)) add(ConventionMinimumSalaryV2.Periodicity.ANNUAL)
        }
        if (periodicities.size != 1) return null
        return ParsedValue(amounts.single(), periodicities.single())
    }

    private fun parseMoney(raw: String): Double? = raw
        .replace(" ", "")
        .replace("\u00a0", "")
        .replace(',', '.')
        .toDoubleOrNull()

    private val minimumVocabulary = listOf(
        "salaire minimum", "salaires minima", "salaire minimal", "minimum conventionnel",
        "remuneration minimale", "remunerations minimales", "minima conventionnels"
    )
    private val monthlyWords = listOf("mensuel", "mensuelle", "par mois", "mensuellement")
    private val hourlyWords = listOf("horaire", "par heure", "de l'heure")
    private val annualWords = listOf("annuel", "annuelle", "par an", "annuellement")
    private val euroAmountRegex = Regex("(?<!\\d)(\\d{1,6}(?:[ \\u00a0]\\d{3})*(?:[,.]\\d{1,2})?)\\s*(?:€|euros?)(?![a-z])")
}
