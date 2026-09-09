package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import java.time.LocalDate

/**
 * Dernier garde-fou sémantique appliqué à la clause ACCO isolée avant stockage.
 *
 * Le parseur principal extrait la règle ; cette couche refuse ce qui ne peut pas encore être
 * représenté sans perte : date propre à la clause, non-cumul inconnu, plafond journalier ambigu
 * ou renvoi de montant à un autre accord non encore résolu officiellement.
 */
object AccoMealBasketRuleHardeningV2 {
    data class Result(
        val rule: OfficialAccoMealBasketParserV2.Rule?,
        val warnings: List<String>
    ) {
        val reliable: Boolean get() = rule != null
    }

    fun harden(rule: OfficialAccoMealBasketParserV2.Rule): Result {
        val text = OfficialKaliProfileMatcherV2.normalize(rule.evidenceExcerpt)
        val warnings = mutableListOf<String>()

        val clauseStarts = clauseEffectiveFromRegex.findAll(text)
            .mapNotNull { parseDate(it.groupValues[1]) }
            .distinct()
            .toList()
        if (clauseStarts.size > 1) return blocked("plusieurs dates d'effet propres à la clause")
        val from = clauseStarts.singleOrNull() ?: rule.effectiveFrom
        if (from.isBefore(rule.effectiveFrom)) {
            return blocked("date d'effet de la clause antérieure à celle de l'accord")
        }

        val clauseEnds = clauseEffectiveToRegex.findAll(text)
            .mapNotNull { parseDate(it.groupValues[1]) }
            .distinct()
            .toList()
        if (clauseEnds.size > 1) return blocked("plusieurs dates de fin propres à la clause")
        val to = clauseEnds.singleOrNull() ?: rule.effectiveTo
        if (to != null && to.isBefore(from)) return blocked("période propre à la clause incohérente")
        if (rule.effectiveTo != null && to != null && to.isAfter(rule.effectiveTo)) {
            return blocked("date de fin de la clause postérieure à celle de l'accord")
        }

        if (nonCumulationRegex.containsMatchIn(text)) {
            if (rule.blockers.isEmpty()) {
                return blocked("non-cumul détecté mais avantage concurrent non structuré")
            }
            if (unknownNonCumulationTargetRegex.containsMatchIn(text)) {
                return blocked("non-cumul avec un avantage hors modèle repas actuel")
            }
        }

        val capTokens = dailyCapRegex.findAll(text).map { it.groupValues[1] }.distinct().toList()
        if (capTokens.size > 1) return blocked("plusieurs plafonds journaliers de paniers")
        val parsedCap = capTokens.singleOrNull()?.let(::parseSmallNumber)
        val mentionsDailyCap = dailyCapVocabulary.containsMatchIn(text)
        if (mentionsDailyCap && parsedCap == null) {
            return blocked("plafond journalier mentionné mais non chiffrable")
        }
        if (parsedCap != null && parsedCap !in 1..24) return blocked("plafond journalier de paniers invalide")

        val perShift = perShiftRegex.containsMatchIn(text)
        if (rule.countingUnit == ConventionMealBasketV2.CountingUnit.SHIFT && perShift && parsedCap == null) {
            return blocked("panier par poste/équipe sans plafond journalier démontré")
        }

        if (rule.amountFormula == ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount) {
            return blocked("montant renvoyé à un autre accord non encore relié à une preuve de montant exacte")
        }

        val hardened = rule.copy(
            effectiveFrom = from,
            effectiveTo = to,
            maxAwardsPerCalendarDay = parsedCap ?: rule.maxAwardsPerCalendarDay
        )
        if (!hardened.structurallyValid()) return blocked("règle durcie structurellement invalide")
        if (from != rule.effectiveFrom) warnings += "ACCO repas : date d'effet propre à la clause retenue ($from)."
        if (parsedCap != null) warnings += "ACCO repas : plafond explicite de $parsedCap panier(s) par jour retenu."
        return Result(hardened, warnings)
    }

    private fun blocked(reason: String) = Result(
        rule = null,
        warnings = listOf("ACCO repas : $reason ; clause bloquée plutôt qu'interprétée.")
    )

    private fun parseSmallNumber(raw: String): Int? = when (raw.trim()) {
        "un", "une" -> 1
        "deux" -> 2
        "trois" -> 3
        "quatre" -> 4
        else -> raw.toIntOrNull()
    }

    private fun parseDate(raw: String): LocalDate? {
        val normalized = OfficialKaliProfileMatcherV2.normalize(raw).trim().replace("1er ", "1 ")
        numericDateRegex.matchEntire(normalized)?.let { match ->
            return runCatching {
                LocalDate.of(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt())
            }.getOrNull()
        }
        textualDateRegex.matchEntire(normalized)?.let { match ->
            val month = months[match.groupValues[2]] ?: return null
            return runCatching { LocalDate.of(match.groupValues[3].toInt(), month, match.groupValues[1].toInt()) }.getOrNull()
        }
        return null
    }

    private val dateToken = "[0-9]{1,2}(?:er)?(?:[ /.-]+[a-z]+|[ /.-]+[0-9]{1,2})[ /.-]+[0-9]{4}"
    private val clauseEffectiveFromRegex = Regex("\\b(?:a compter du|a partir du|des le)\\s+($dateToken)")
    private val clauseEffectiveToRegex = Regex("\\b(?:jusqu'au|jusqu au|prendra fin le|expire le)\\s+($dateToken)")
    private val nonCumulationRegex = Regex("\\b(?:non cumulable|ne se cumule pas|pas cumulable|exclusif)\\b")
    private val unknownNonCumulationTargetRegex = Regex(
        "\\b(?:indemnite de deplacement|indemnite kilometrique|frais de transport|prime de transport|prime de deplacement)\\b"
    )
    private val dailyCapRegex = Regex(
        "\\b(?:maximum(?: de)?|au plus|limite(?: de)?)\\s+(un|une|deux|trois|quatre|[0-9]{1,2})\\s+(?:paniers?|indemnites? repas|allocations? repas)[^.;\\n]{0,45}?\\b(?:par jour|par journee|quotidien)\\b"
    )
    private val dailyCapVocabulary = Regex(
        "\\b(?:maximum|au plus|limite)\\b[^.;\\n]{0,100}?\\b(?:par jour|par journee|quotidien)\\b"
    )
    private val perShiftRegex = Regex("\\b(?:par poste|par equipe|par vacation|par shift)\\b")
    private val numericDateRegex = Regex("([0-9]{1,2})[ /.-]+([0-9]{1,2})[ /.-]+([0-9]{4})")
    private val textualDateRegex = Regex("([0-9]{1,2})\\s+([a-z]+)\\s+([0-9]{4})")
    private val months = mapOf(
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6,
        "juillet" to 7, "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12
    )
}