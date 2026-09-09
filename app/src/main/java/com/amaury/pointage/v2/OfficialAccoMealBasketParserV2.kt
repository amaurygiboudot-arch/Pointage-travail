package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Parseur ACCO fail-closed des paniers / indemnités repas d'entreprise.
 *
 * Une règle n'est structurée que si le même accord officiel prouve l'identité ACCOTEXT,
 * le SIRET exact, la période, le profil, le montant et les conditions d'ouverture.
 */
object OfficialAccoMealBasketParserV2 {
    data class Rule(
        val agreementId: String,
        val siret: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val classification: ConventionClassificationV2,
        val professionalStatus: String,
        val benefitId: String,
        val deliveryMode: ConventionMealBasketV2.DeliveryMode,
        val amountFormula: ConventionMealBasketV2.AmountFormula,
        val eligibilityAnyOf: List<ConventionMealBasketV2.EligibilityGroup>,
        val blockers: Set<ConventionMealBasketV2.Blocker>,
        val countingUnit: ConventionMealBasketV2.CountingUnit,
        val maxAwardsPerCalendarDay: Int = 1,
        val evidenceExcerpt: String
    ) {
        fun structurallyValid(): Boolean =
            agreementId.matches(accoTextIdRegex) &&
                siret.length == 14 && siret.all(Char::isDigit) &&
                (effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) &&
                !classification.isEmpty() &&
                professionalStatus in setOf("CADRE", "NON_CADRE") &&
                benefitId.isNotBlank() &&
                amountFormula.structurallyValid() &&
                eligibilityAnyOf.isNotEmpty() && eligibilityAnyOf.all { it.structurallyValid() } &&
                maxAwardsPerCalendarDay in 1..24 &&
                evidenceExcerpt.isNotBlank()

        val fingerprint: String
            get() = listOf(
                "ACCO_MEAL",
                agreementId,
                siret,
                effectiveFrom.toString(),
                effectiveTo?.toString().orEmpty(),
                classification.label(),
                professionalStatus,
                benefitId,
                deliveryMode.name,
                amountFingerprint(amountFormula),
                eligibilityFingerprint(eligibilityAnyOf),
                blockers.sortedBy { it.name }.joinToString(",") { it.name },
                countingUnit.name,
                maxAwardsPerCalendarDay.toString()
            ).joinToString("|")
    }

    data class Diagnostic(
        val rules: List<Rule>,
        val observedOccurrences: Int,
        val structuredOccurrences: Int,
        val unresolvedOccurrences: Int,
        val reasons: List<String>
    ) {
        val fullyStructured: Boolean
            get() = observedOccurrences > 0 &&
                unresolvedOccurrences == 0 &&
                structuredOccurrences == observedOccurrences &&
                rules.size == structuredOccurrences
    }

    private data class Period(val from: LocalDate, val to: LocalDate?)

    fun parse(
        profile: ConventionLegalProfileV2,
        agreementId: String,
        officialText: String
    ): Diagnostic {
        val acco = agreementId.trim().uppercase(Locale.ROOT)
        if (!acco.matches(accoTextIdRegex)) return unresolved("identifiant ACCOTEXT officiel invalide")

        val siret = profile.siret.filter(Char::isDigit)
        if (siret.length != 14) return unresolved("SIRET exact du profil manquant")
        if (profile.classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")
        val status = profile.professionalStatus?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it in setOf("CADRE", "NON_CADRE") }
            ?: return unresolved("statut cadre/non-cadre exact manquant")
        if (officialText.isBlank()) return unresolved("texte officiel ACCO vide")

        val text = OfficialKaliProfileMatcherV2.normalize(officialText)
        val period = parsePeriod(text)
            ?: return unresolved("date d'effet ou durée de l'accord ACCO absente/ambiguë")

        val classificationPresent = classificationVocabulary.containsMatchIn(text)
        val occurrences = mealOccurrences(text)
        if (occurrences.isEmpty()) {
            return unresolved("aucune occurrence panier/indemnité repas dans l'accord")
        }

        val rules = mutableListOf<Rule>()
        val reasons = mutableListOf<String>()
        var observed = 0
        var structured = 0
        var unresolved = 0

        occurrences.forEachIndexed { index, occurrence ->
            val targetOffset = occurrence.range.first
            val scopeWindow = if (classificationPresent) {
                OfficialKaliProfileMatcherV2.nearestScopeWindow(
                    rawText = text,
                    classification = profile.classification,
                    professionalStatus = status,
                    targetOffset = targetOffset,
                    before = 240,
                    after = 760,
                    maxClassificationSpan = 420
                )
            } else {
                text.takeIf { OfficialKaliProfileMatcherV2.statusScopeMatches(it, status) }
                    ?.let { OfficialKaliProfileMatcherV2.Window(it, 0, it.length) }
            }

            // Les clauses d'un coefficient/niveau/statut voisin sont hors profil : on les ignore.
            if (scopeWindow == null) return@forEachIndexed
            observed++

            val clause = isolateOccurrence(
                scope = scopeWindow.text,
                targetOffset = targetOffset - scopeWindow.start
            )
            if (employmentExclusionRegex.containsMatchIn(clause)) {
                unresolved++
                reasons += "ACCO repas $acco : occurrence ${index + 1} avec exclusion professionnelle non structurée ; règle bloquée."
                return@forEachIndexed
            }

            val amount = parseAmount(clause)
            val eligibility = parseEligibility(clause)
            if (amount == null || eligibility.isEmpty()) {
                unresolved++
                reasons += "ACCO repas $acco : occurrence ${index + 1} sans montant/formule et conditions complètes dans sa propre clause."
                return@forEachIndexed
            }

            val rule = Rule(
                agreementId = acco,
                siret = siret,
                effectiveFrom = period.from,
                effectiveTo = period.to,
                classification = profile.classification.normalized(),
                professionalStatus = status,
                benefitId = benefitId(clause, index),
                deliveryMode = if (mealOrCashRegex.containsMatchIn(clause)) {
                    ConventionMealBasketV2.DeliveryMode.EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED
                } else {
                    ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE
                },
                amountFormula = amount,
                eligibilityAnyOf = eligibility,
                blockers = parseBlockers(clause),
                countingUnit = if (perWorkedDayRegex.containsMatchIn(clause)) {
                    ConventionMealBasketV2.CountingUnit.WORKED_DAY
                } else {
                    ConventionMealBasketV2.CountingUnit.SHIFT
                },
                evidenceExcerpt = clause.take(1600)
            )

            if (!rule.structurallyValid()) {
                unresolved++
                reasons += "ACCO repas $acco : occurrence ${index + 1} structurée mais incohérente."
            } else {
                structured++
                rules += rule
            }
        }

        return Diagnostic(
            rules = rules.distinctBy { it.fingerprint },
            observedOccurrences = observed,
            structuredOccurrences = structured,
            unresolvedOccurrences = unresolved,
            reasons = buildList {
                addAll(reasons)
                if (observed == 0) {
                    add("ACCO repas $acco : aucune occurrence du profil exact ; cela ne prouve pas l'absence de règle d'entreprise.")
                }
                if (observed > structured) {
                    add("ACCO repas $acco : ${observed - structured} occurrence(s) du profil restent non structurées ; paquet ACCO bloqué.")
                }
            }.distinct()
        )
    }

    private fun parsePeriod(text: String): Period? {
        val starts = effectiveFromRegex.findAll(text)
            .mapNotNull { parseDateToken(it.groupValues[1]) }
            .distinct()
            .toList()
        if (starts.size != 1) return null

        val from = starts.single()
        val indefinite = indefiniteRegex.containsMatchIn(text)
        val ends = effectiveToRegex.findAll(text)
            .mapNotNull { parseDateToken(it.groupValues[1]) }
            .distinct()
            .toList()
        if (indefinite && ends.isNotEmpty()) return null
        if (ends.size > 1) return null

        val to = when {
            indefinite -> null
            ends.size == 1 -> ends.single()
            else -> return null
        }
        if (to != null && to.isBefore(from)) return null
        return Period(from, to)
    }

    private fun parseAmount(text: String): ConventionMealBasketV2.AmountFormula? {
        // Le premier montant doit être rattaché explicitement à l'avantage repas. Un éventuel
        // second montant n'est candidat que s'il est relié dans la même clause (ex. « ou 8,50 € »).
        // Ainsi une clause suivante incomplète ne peut jamais récupérer le montant de la précédente.
        val fixed = fixedAmountRegex.findAll(text)
            .mapNotNull { parseNumber(it.groupValues[1])?.takeIf { value -> value in 0.01..1000.0 } }
            .distinct()
            .toList()
        val mg = minimumGuaranteedRegex.findAll(text)
            .mapNotNull { parseNumber(it.groupValues[1])?.takeIf { value -> value > 0.0 && value <= 100.0 } }
            .distinct()
            .toList()

        if (fixed.size > 1 || mg.size > 1 || (fixed.isNotEmpty() && mg.isNotEmpty())) return null
        return when {
            fixed.size == 1 -> ConventionMealBasketV2.AmountFormula.FixedEuro(fixed.single())
            mg.size == 1 -> ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(mg.single())
            externalAgreementAmountRegex.containsMatchIn(text) -> ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount
            else -> null
        }
    }

    private fun parseEligibility(text: String): List<ConventionMealBasketV2.EligibilityGroup> {
        val common = buildList<ConventionMealBasketV2.Condition> {
            if (postedWorkerRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.PostedShiftWorker)
            if (unableHomeRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal)
            if (awayWorkplaceRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace)
            if (mustEatAtWorkRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.MustEatAtWorkplace)
            if (perWorkedDayRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.WorkedDay)
        }

        // Dans une même clause, plusieurs conditions détectées sont présumées cumulatives.
        // Elles restent donc dans UN SEUL groupe allOf. Si plusieurs conditions temporelles sont
        // reliées par une alternative explicite que le parseur ne sait pas décomposer sûrement,
        // on bloque la clause plutôt que de transformer l'alternative en règle approximative.
        val temporal = mutableListOf<ConventionMealBasketV2.Condition>()

        effectiveWindowRegex.findAll(text).mapNotNull { match ->
            val hours = parseNumber(match.groupValues[1]) ?: return@mapNotNull null
            val start = parseClock(match.groupValues[2], match.groupValues[3]) ?: return@mapNotNull null
            val end = parseClock(match.groupValues[4], match.groupValues[5]) ?: return@mapNotNull null
            ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                ConventionMealBasketV2.DailyWindow(start, end),
                (hours * 60.0).toInt()
            ).takeIf { it.structurallyValid() }
        }.forEach(temporal::add)

        employerWindowRegex.findAll(text).mapNotNull { match ->
            val windowHours = parseNumber(match.groupValues[1]) ?: return@mapNotNull null
            val start = parseClock(match.groupValues[2], match.groupValues[3]) ?: return@mapNotNull null
            val end = parseClock(match.groupValues[4], match.groupValues[5]) ?: return@mapNotNull null
            val minimumHours = parseNumber(match.groupValues[6]) ?: return@mapNotNull null
            ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow(
                allowedEnvelope = ConventionMealBasketV2.DailyWindow(start, end),
                requiredWindowMinutes = (windowHours * 60.0).toInt(),
                minimumEffectiveMinutes = (minimumHours * 60.0).toInt()
            ).takeIf { it.structurallyValid() }
        }.forEach(temporal::add)

        var unsafeBoundaryOrientation = false
        startsEndsWindowRegex.findAll(text).forEach { match ->
            val verbs = listOf(match.groupValues[1], match.groupValues[2]).filter { it.isNotBlank() }
            val hasStart = verbs.any { it == "commence" || it == "debute" }
            val hasEnd = verbs.any { it == "se termine" || it == "finit" }
            if (!hasStart || !hasEnd) {
                unsafeBoundaryOrientation = true
                return@forEach
            }
            val start = parseClock(match.groupValues[3], match.groupValues[4]) ?: run {
                unsafeBoundaryOrientation = true
                return@forEach
            }
            val end = parseClock(match.groupValues[5], match.groupValues[6]) ?: run {
                unsafeBoundaryOrientation = true
                return@forEach
            }
            ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow(
                ConventionMealBasketV2.DailyWindow(start, end)
            ).takeIf { it.structurallyValid() }?.let(temporal::add)
                ?: run { unsafeBoundaryOrientation = true }
        }
        if (unsafeBoundaryOrientation) return emptyList()

        if (enclosesMidnightRegex.containsMatchIn(text)) {
            temporal += ConventionMealBasketV2.Condition.ShiftEnclosesMidnight
        }
        if (startsMidnightRegex.containsMatchIn(text)) {
            temporal += ConventionMealBasketV2.Condition.ShiftStartsAtMidnight
        }

        val distinctTemporal = temporal.distinct()
        if (distinctTemporal.size > 1 && temporalAlternativeRegex.containsMatchIn(text)) return emptyList()

        val allConditions = (common + distinctTemporal).distinct()
        if (allConditions.isEmpty()) return emptyList()
        val group = ConventionMealBasketV2.EligibilityGroup(allConditions)
        return listOf(group).filter { it.structurallyValid() }
    }

    private fun parseBlockers(text: String): Set<ConventionMealBasketV2.Blocker> {
        if (!nonCumulationRegex.containsMatchIn(text)) return emptySet()
        return buildSet {
            if (canteenRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.COMPANY_CANTEEN)
            if (providedMealRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.EMPLOYER_PROVIDED_MEAL)
            if (mealVoucherRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.MEAL_VOUCHER)
            if (sameNatureRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.OTHER_SAME_NATURE_MEAL_BENEFIT)
        }
    }

    private fun mealOccurrences(text: String): List<MatchResult> {
        val dailyCapRanges = dailyCapRegex.findAll(text).map { it.range }.toList()
        return mealOccurrenceRegex.findAll(text)
            .filterNot { occurrence -> dailyCapRanges.any { cap -> occurrence.range.first in cap } }
            .toList()
    }

    private fun isolateOccurrence(scope: String, targetOffset: Int): String {
        val occurrences = mealOccurrences(scope)
        val target = occurrences.minByOrNull { abs(it.range.first - targetOffset) } ?: return scope
        val previous = occurrences.lastOrNull { it.range.first < target.range.first }
        val next = occurrences.firstOrNull { it.range.first > target.range.first }
        val start = maxOf(
            (target.range.first - 260).coerceAtLeast(0),
            previous?.range?.last?.plus(1) ?: 0
        )
        val end = minOf(
            scope.length,
            next?.range?.first ?: scope.length,
            target.range.last + 1 + 760
        )
        return if (end > start) scope.substring(start, end) else ""
    }

    private fun parseDateToken(raw: String): LocalDate? {
        val normalized = OfficialKaliProfileMatcherV2.normalize(raw)
            .trim()
            .replace("1er ", "1 ")

        numericDateRegex.matchEntire(normalized)?.let { match ->
            return runCatching {
                LocalDate.of(
                    match.groupValues[3].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[1].toInt()
                )
            }.getOrNull()
        }
        textualDateRegex.matchEntire(normalized)?.let { match ->
            val month = months[match.groupValues[2]] ?: return null
            return runCatching {
                LocalDate.of(match.groupValues[3].toInt(), month, match.groupValues[1].toInt())
            }.getOrNull()
        }
        return null
    }

    private fun parseClock(hourRaw: String, minuteRaw: String): Int? {
        val hour = hourRaw.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = minuteRaw.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return hour * 60 + minute
    }

    private fun parseNumber(raw: String): Double? =
        raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun benefitId(text: String, index: Int): String = when {
        unableHomeRegex.containsMatchIn(text) || awayWorkplaceRegex.containsMatchIn(text) -> "MEAL_AWAY_${index + 1}"
        explicitNightBenefitRegex.containsMatchIn(text) || employerWindowRegex.containsMatchIn(text) ||
            effectiveWindowRegex.containsMatchIn(text) || enclosesMidnightRegex.containsMatchIn(text) -> "MEAL_NIGHT_${index + 1}"
        postedWorkerRegex.containsMatchIn(text) -> "MEAL_SHIFT_${index + 1}"
        else -> "MEAL_DAY_${index + 1}"
    }

    private fun unresolved(reason: String) = Diagnostic(
        rules = emptyList(),
        observedOccurrences = 0,
        structuredOccurrences = 0,
        unresolvedOccurrences = 0,
        reasons = listOf("ACCO repas : $reason ; aucune règle d'entreprise n'est enregistrée.")
    )

    internal fun amountFingerprint(value: ConventionMealBasketV2.AmountFormula): String = when (value) {
        is ConventionMealBasketV2.AmountFormula.FixedEuro -> "FIXED:${value.amount}"
        is ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple -> "MG:${value.multiplier}"
        ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount -> "EXTERNAL"
    }

    internal fun eligibilityFingerprint(groups: List<ConventionMealBasketV2.EligibilityGroup>): String =
        groups.joinToString("||") { group ->
            group.allOf.joinToString("&") { it.toString() }
        }

    private val accoTextIdRegex = Regex("^ACCOTEXT\\d+$")
    private val classificationVocabulary = Regex(
        "\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction)s?\\b|\\bposte\\s*[:\\-]"
    )
    private val mealOccurrenceRegex = Regex(
        "\\b(?:paniers?(?: repas| de nuit)?|indemnite(?:s)?(?: de)? repas|allocation(?:s)? de repas|prime(?:s)? de panier)\\b"
    )
    private val explicitNightBenefitRegex = Regex(
        "\\b(?:paniers? de nuit|prime(?:s)? de panier de nuit|indemnite(?:s)? repas de nuit)\\b"
    )
    private val employmentExclusionRegex = Regex(
        "\\b(?:sauf|a l'exception de|a l'exception des|hors)\\s+(?:les?\\s+)?[a-z][a-z -]{2,80}(?:[.;,]|$)"
    )
    private val dailyCapRegex = Regex(
        "\\b(?:maximum(?: de)?|au plus|limite(?: de)?)\\s+(un|une|deux|trois|quatre|[0-9]{1,2})\\s+(?:paniers?|indemnites? repas|allocations? repas)[^.;\\n]{0,45}?\\b(?:par jour|par journee|quotidien)\\b"
    )
    private val fixedAmountRegex = Regex(
        "(?:(?:panier|indemnite|allocation|prime)[^.;\\n]{0,120}?|\\bou\\s+)([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?\\b)"
    )
    private val minimumGuaranteedRegex = Regex(
        "([0-9]+(?:[.,][0-9]+)?)\\s*(?:fois|x)\\s*(?:le\\s+)?minimum garanti\\b"
    )
    private val externalAgreementAmountRegex = Regex(
        "\\bmontant\\b[^.;\\n]{0,120}?\\b(?:fixe|determine|prevu)\\b[^.;\\n]{0,80}?\\b(?:accord|avenant)\\b"
    )
    private val postedWorkerRegex = Regex("\\b(?:travail poste|travail en equipes?|equipes? successives?|personnel poste)\\b")
    private val unableHomeRegex = Regex("\\b(?:ne peut|impossibilite de)\\s+(?:pas\\s+)?(?:regagner|rentrer a)\\s+(?:son\\s+)?domicile\\b")
    private val awayWorkplaceRegex = Regex("\\b(?:travail(?:le)? sur chantier|deplacement professionnel|hors (?:du|de son) lieu habituel de travail)\\b")
    private val mustEatAtWorkRegex = Regex("\\b(?:oblige|tenu)\\s+de\\s+(?:prendre|consommer)\\s+(?:son\\s+)?repas\\s+(?:sur|au)\\s+(?:le\\s+)?lieu de travail\\b")
    private val perWorkedDayRegex = Regex("\\b(?:par|pour chaque)\\s+(?:jour|journee)\\s+travaille(?:e)?\\b")
    private val effectiveWindowRegex = Regex(
        "(?:au moins|minimum de)\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h(?:eures?)?[^.;\\n]{0,80}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?"
    )
    private val employerWindowRegex = Regex(
        "plage\\s+de\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h(?:eures?)?[^.;\\n]{0,80}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?[^.;\\n]{0,120}?(?:au moins|minimum de)\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h"
    )
    private val startsEndsWindowRegex = Regex(
        "(commence|debute|se termine|finit)(?:\\s+ou\\s+(commence|debute|se termine|finit))?[^.;\\n]{0,60}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?"
    )
    private val temporalAlternativeRegex = Regex(
        "\\b(?:ou|soit)\\b"
    )
    private val enclosesMidnightRegex = Regex("\\b(?:encadre|comprend|inclut|traverse)\\s+minuit\\b")
    private val startsMidnightRegex = Regex("\\b(?:commence|debute)\\s+a\\s+minuit\\b")
    private val nonCumulationRegex = Regex("\\b(?:non cumulable|ne se cumule pas|pas cumulable|exclusif)\\b")
    private val canteenRegex = Regex("\\b(?:cantine|restaurant d'entreprise)\\b")
    private val providedMealRegex = Regex("\\brepas\\s+(?:fourni|pris en charge)\\s+par\\s+l'employeur\\b")
    private val mealVoucherRegex = Regex("\\b(?:titre|ticket)[- ]restaurant\\b")
    private val sameNatureRegex = Regex("\\bavantage\\s+(?:de )?meme nature\\b")
    private val mealOrCashRegex = Regex(
        "\\brepas\\s+fourni\\s+par\\s+l'employeur[^.;\\n]{0,160}?(?:a defaut|sinon)[^.;\\n]{0,100}?(?:indemnite|panier)\\b"
    )
    private val effectiveFromRegex = Regex(
        "(?:entre(?:ra|e)? en vigueur|prend effet|date d'effet)\\s*(?:a compter du|le|:)?\\s*([0-9]{1,2}(?:er)?(?:[ /.-]+[a-z]+|[ /.-]+[0-9]{1,2})[ /.-]+[0-9]{4})"
    )
    private val effectiveToRegex = Regex(
        "(?:prendra fin|expire(?:ra)?|jusqu'au|date de fin)\\s*(?:le|:)?\\s*([0-9]{1,2}(?:er)?(?:[ /.-]+[a-z]+|[ /.-]+[0-9]{1,2})[ /.-]+[0-9]{4})"
    )
    private val indefiniteRegex = Regex("\\b(?:duree indeterminee|pour une duree indeterminee|conclu a duree indeterminee)\\b")
    private val numericDateRegex = Regex("([0-9]{1,2})[ /.-]+([0-9]{1,2})[ /.-]+([0-9]{4})")
    private val textualDateRegex = Regex("([0-9]{1,2})\\s+([a-z]+)\\s+([0-9]{4})")
    private val months = mapOf(
        "janvier" to 1,
        "fevrier" to 2,
        "mars" to 3,
        "avril" to 4,
        "mai" to 5,
        "juin" to 6,
        "juillet" to 7,
        "aout" to 8,
        "septembre" to 9,
        "octobre" to 10,
        "novembre" to 11,
        "decembre" to 12
    )
}
