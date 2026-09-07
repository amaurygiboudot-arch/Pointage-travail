package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import java.time.LocalDate
import java.util.Locale

/** Parse seulement les primes d'ancienneté à formule explicite et profil précisément identifié. */
object OfficialKaliSeniorityPremiumParserV2 {
    data class Diagnostic(
        val articleId: String,
        val rule: ConventionSeniorityPremiumV2.Rule?,
        val reasons: List<String>
    )

    fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2,
        auditDate: LocalDate
    ): Diagnostic {
        if (profile.classification.isEmpty()) return Diagnostic(article.articleId, null, listOf("classification salarié absente"))
        val raw = listOfNotNull(article.title, article.content).joinToString(" ")
        val normalized = OfficialKaliProfileMatcherV2.normalize(raw)
        if (!seniorityVocabulary.any(normalized::contains)) {
            return Diagnostic(article.articleId, null, listOf("article sans mention explicite de prime d'ancienneté"))
        }

        val windows = OfficialKaliProfileMatcherV2.windows(
            rawText = raw,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            before = 260,
            after = 760,
            maxClassificationSpan = 500
        )
        if (windows.isEmpty()) return Diagnostic(article.articleId, null, listOf("classification/statut exacts non isolés dans l'article"))

        val parsed = windows.mapNotNull { parseWindow(it.text) }.distinctBy(::fingerprint)
        if (parsed.size != 1) {
            return Diagnostic(article.articleId, null, listOf("${parsed.size} formule(s) d'ancienneté non ambiguë(s) autour du profil ; règle unique non démontrée"))
        }
        val candidate = parsed.single()
        val extensionStatus = extensionStatus(article)
        val rule = ConventionSeniorityPremiumV2.Rule(
            idcc = profile.idcc,
            ruleId = "KALI-SEN-${article.articleId}-${profile.classification.normalized().label().hashCode().toUInt().toString(16)}",
            effectiveFrom = article.effectiveFrom,
            effectiveTo = article.effectiveTo,
            classification = profile.classification.normalized(),
            basis = candidate.first,
            steps = candidate.second,
            includeConfirmedMonthlySupplement = false,
            source = "Légifrance KALI — ${article.articleId}${article.title?.let { " — $it" }.orEmpty()}",
            extensionStatus = extensionStatus,
            extensionEffectiveFrom = if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) article.extensionEffectiveFrom else null
        )
        return if (rule.structurallyValid()) Diagnostic(
            article.articleId,
            rule,
            buildList {
                if (article.status.uppercase(Locale.ROOT) == "VIGUEUR_ETEN" && article.extensionEffectiveFrom == null) {
                    add("statut étendu présent mais date exacte d'extension absente ; applicabilité automatique bloquée")
                }
            }
        ) else Diagnostic(article.articleId, null, listOf("formule structurée incohérente"))
    }

    private fun extensionStatus(article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle): ConventionMinimumSalaryV2.ExtensionStatus =
        when (article.status.uppercase(Locale.ROOT)) {
            "VIGUEUR_ETEN" -> if (article.extensionEffectiveFrom != null) {
                ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
            } else {
                ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
            }
            "VIGUEUR_NON_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
            else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
        }

    private fun parseWindow(window: String): Pair<ConventionSeniorityPremiumV2.Basis, List<ConventionSeniorityPremiumV2.Step>>? {
        if (!seniorityVocabulary.any(window::contains)) return null

        val fixed = parseFixedMonthly(window)
        val percentage = parsePercentageSteps(window)
        val perYear = parsePerYearSteps(window)
        val candidates = listOfNotNull(fixed, percentage, perYear).distinctBy(::fingerprint)
        return candidates.singleOrNull()
    }

    private fun parsePercentageSteps(window: String): Pair<ConventionSeniorityPremiumV2.Basis, List<ConventionSeniorityPremiumV2.Step>>? {
        val basis = percentageBasis(window) ?: return null
        val forward = yearPercentRegex.findAll(window).mapNotNull { m ->
            val years = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val percent = decimal(m.groupValues[2]) ?: return@mapNotNull null
            ConventionSeniorityPremiumV2.Step(years = years, rate = percent / 100.0)
        }.toList()
        val reverse = percentYearRegex.findAll(window).mapNotNull { m ->
            val percent = decimal(m.groupValues[1]) ?: return@mapNotNull null
            val years = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            ConventionSeniorityPremiumV2.Step(years = years, rate = percent / 100.0)
        }.toList()
        val steps = (forward + reverse)
            .filter { it.years in 1..60 && it.rate != null && it.rate in 0.0..1.0 }
            .distinctBy { it.years to it.rate }
            .sortedBy { it.years }
        if (steps.isEmpty() || steps.map { it.years }.distinct().size != steps.size) return null
        return basis to steps
    }

    private fun parsePerYearSteps(window: String): Pair<ConventionSeniorityPremiumV2.Basis, List<ConventionSeniorityPremiumV2.Step>>? {
        val basis = percentageBasis(window) ?: return null
        val ratePerYear = perYearRateRegex.find(window)?.groupValues?.getOrNull(1)?.let(::decimal) ?: return null
        if (ratePerYear <= 0.0 || ratePerYear > 20.0) return null
        val thresholdSection = thresholdListRegex.find(window)?.groupValues?.getOrNull(1) ?: return null
        val years = Regex("\\b(\\d{1,2})\\b").findAll(thresholdSection)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..60 }
            .distinct()
            .sorted()
            .toList()
        if (years.isEmpty()) return null
        return basis to years.map { ConventionSeniorityPremiumV2.Step(it, rate = it * ratePerYear / 100.0) }
    }

    private fun parseFixedMonthly(window: String): Pair<ConventionSeniorityPremiumV2.Basis, List<ConventionSeniorityPremiumV2.Step>>? {
        if (!monthlyWords.any(window::contains)) return null
        val steps = yearEuroRegex.findAll(window).mapNotNull { m ->
            val years = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val amount = money(m.groupValues[2]) ?: return@mapNotNull null
            if (years !in 1..60 || amount < 0.0 || amount >= 100_000.0) return@mapNotNull null
            ConventionSeniorityPremiumV2.Step(years = years, fixedMonthlyAmount = amount)
        }.distinctBy { it.years to it.fixedMonthlyAmount }.sortedBy { it.years }.toList()
        if (steps.isEmpty() || steps.map { it.years }.distinct().size != steps.size) return null
        return ConventionSeniorityPremiumV2.Basis.FIXED_MONTHLY to steps
    }

    private fun percentageBasis(window: String): ConventionSeniorityPremiumV2.Basis? {
        val actual = actualBaseWords.any(window::contains)
        val minimum = minimumBaseWords.any(window::contains)
        return when {
            actual && !minimum -> ConventionSeniorityPremiumV2.Basis.ACTUAL_MONTHLY_BASE
            minimum && !actual -> ConventionSeniorityPremiumV2.Basis.CONVENTIONAL_MINIMUM_MONTHLY
            else -> null
        }
    }

    private fun fingerprint(value: Pair<ConventionSeniorityPremiumV2.Basis, List<ConventionSeniorityPremiumV2.Step>>): String =
        value.first.name + "|" + value.second.joinToString(";") { "${it.years}:${it.rate}:${it.fixedMonthlyAmount}" }

    private fun decimal(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()
    private fun money(raw: String): Double? = raw.replace(" ", "").replace("\u00a0", "").replace(',', '.').toDoubleOrNull()

    private val seniorityVocabulary = listOf("prime d'anciennete", "prime d anciennete", "prime anciennete")
    private val actualBaseWords = listOf("salaire de base", "traitement de base", "remuneration de base")
    private val minimumBaseWords = listOf("salaire minimum", "minimum conventionnel", "salaire minimal conventionnel", "minimum hierarchique", "minimum de la classification")
    private val monthlyWords = listOf("mensuel", "mensuelle", "par mois", "mensuellement")

    private val yearPercentRegex = Regex("\\b(\\d{1,2})\\s*ans?\\b.{0,90}?(\\d{1,3}(?:[,.]\\d+)?)\\s*%")
    private val percentYearRegex = Regex("(\\d{1,3}(?:[,.]\\d+)?)\\s*%.{0,90}?\\b(?:apres|a partir de|des)\\s*(\\d{1,2})\\s*ans?\\b")
    private val yearEuroRegex = Regex("\\b(\\d{1,2})\\s*ans?\\b.{0,90}?(\\d{1,6}(?:[ \\u00a0]\\d{3})*(?:[,.]\\d{1,2})?)\\s*(?:€|euros?)(?![a-z])")
    private val perYearRateRegex = Regex("(\\d{1,3}(?:[,.]\\d+)?)\\s*%\\s*(?:par|pour chaque)\\s*(?:annee|an)\\s*d?'?anciennete")
    private val thresholdListRegex = Regex("(?:paliers?|anciennete).{0,160}?((?:\\d{1,2}\\s*(?:,|;|et|/)\\s*)+\\d{1,2})\\s*ans?")
}
