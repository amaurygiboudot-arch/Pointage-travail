package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Parseur KALI strict des paniers / indemnités repas.
 *
 * Une occurrence n'est structurée que si le même bloc juridique prouve le profil, le montant,
 * les conditions d'ouverture et la filiation KALI. Chaque occurrence du profil est isolée de ses
 * voisines : une formule appartenant à un autre panier ne peut jamais compléter la clause courante.
 */
object OfficialKaliMealBasketParserV2 {
    const val SAFE_RULE_PREFIX = "KALI-MEAL-V2C-"

    data class Diagnostic(
        val rules: List<ConventionMealBasketV2.Rule>,
        val observedOccurrences: Int,
        val structuredOccurrences: Int,
        val unresolvedOccurrences: Int,
        val reasons: List<String>
    )

    fun parse(
        profile: ConventionLegalProfileV2,
        evidence: KaliMatterEvidenceAuditV2.Evidence
    ): Diagnostic = parse(
        profile = profile,
        verifiedIdcc = evidence.idcc,
        auditDate = evidence.referenceDate,
        articles = evidence.articles,
        articleTextIds = evidence.articleTextIds,
        ambiguousArticleTextIds = evidence.ambiguousArticleTextIds
    )

    internal fun parse(
        profile: ConventionLegalProfileV2,
        verifiedIdcc: String,
        auditDate: LocalDate,
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>,
        articleTextIds: Map<String, String>,
        ambiguousArticleTextIds: Set<String> = emptySet()
    ): Diagnostic {
        val profileIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        val kaliIdcc = ConventionMinimumSalaryV2.normalizeIdcc(verifiedIdcc)
        if (profileIdcc.isBlank() || profileIdcc != kaliIdcc) return unresolved("IDCC du profil et de KALI différents")
        if (profile.classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")
        val status = profile.professionalStatus?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return unresolved("statut cadre/non-cadre exact manquant")

        val ambiguous = ambiguousArticleTextIds.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val rules = mutableListOf<ConventionMealBasketV2.Rule>()
        val reasons = mutableListOf<String>()
        var observed = 0
        var structured = 0
        var unresolved = 0

        articles.forEach articleLoop@ { article ->
            val articleId = article.articleId.trim().uppercase(Locale.ROOT)
            if (!articleId.matches(kaliArticleIdRegex) || articleId in ambiguous) return@articleLoop
            val officialStatus = article.status.trim().uppercase(Locale.ROOT)
            if (officialStatus !in acceptedStatuses) return@articleLoop
            if (auditDate.isBefore(article.effectiveFrom) || article.effectiveTo?.let(auditDate::isAfter) == true) return@articleLoop
            val kaliText = (articleTextIds[articleId]
                ?: articleTextIds.entries.firstOrNull { it.key.equals(articleId, ignoreCase = true) }?.value)
                ?.trim()?.uppercase(Locale.ROOT)
                ?.takeIf { it.matches(kaliTextIdRegex) }
                ?: return@articleLoop

            val title = OfficialKaliProfileMatcherV2.normalize(article.title.orEmpty())
            val body = OfficialKaliProfileMatcherV2.normalize(article.content)
            val text = listOf(title, body).filter { it.isNotBlank() }.joinToString("\n")
            if (text.isBlank()) return@articleLoop
            val bodyOffset = if (title.isBlank()) 0 else title.length + 1
            val classificationPresent = classificationVocabulary.containsMatchIn(text)
            val occurrences = mealOccurrences(body)
            if (occurrences.isEmpty()) return@articleLoop

            occurrences.forEachIndexed { index, occurrence ->
                val targetOffset = bodyOffset + occurrence.range.first
                val scopeWindow = if (classificationPresent) {
                    OfficialKaliProfileMatcherV2.nearestScopeWindow(
                        rawText = text,
                        classification = profile.classification,
                        professionalStatus = status,
                        targetOffset = targetOffset,
                        before = 220,
                        after = 700,
                        maxClassificationSpan = 360
                    )
                } else {
                    text.takeIf { OfficialKaliProfileMatcherV2.statusScopeMatches(it, status) }
                        ?.let { OfficialKaliProfileMatcherV2.Window(it, 0, it.length) }
                }
                if (scopeWindow == null) return@forEachIndexed
                observed++

                val occurrenceText = isolateMealOccurrence(
                    scope = scopeWindow.text,
                    targetOffset = targetOffset - scopeWindow.start
                )
                if (territorialVocabulary.containsMatchIn(occurrenceText)) {
                    unresolved++
                    reasons += "KALI repas $kaliText $articleId : portée territoriale détectée mais non structurée ; règle bloquée."
                    return@forEachIndexed
                }
                // Le moteur d'applicabilité compare les emplois exacts. Une liste composée telle que
                // « sauf les gardiens et les veilleurs » ne peut donc pas être transformée sûrement
                // en exclusions singulières sans inventer une normalisation métier. On bloque.
                if (compoundEmploymentExclusionRegex.containsMatchIn(occurrenceText)) {
                    unresolved++
                    reasons += "KALI repas $kaliText $articleId : exclusion professionnelle composée non structurée ; règle bloquée."
                    return@forEachIndexed
                }

                val amount = parseAmount(occurrenceText)
                val eligibility = parseEligibility(occurrenceText)
                val blockers = parseBlockers(occurrenceText)
                val cap = parseDailyCap(occurrenceText)
                val countingUnit = if (perWorkedDayRegex.containsMatchIn(occurrenceText)) {
                    ConventionMealBasketV2.CountingUnit.WORKED_DAY
                } else ConventionMealBasketV2.CountingUnit.SHIFT
                val clausePeriod = clausePeriod(article.effectiveFrom, article.effectiveTo, occurrenceText)
                val unsafeCap = dailyCapVocabulary.containsMatchIn(occurrenceText) && cap == null
                val unsafePerShift = countingUnit == ConventionMealBasketV2.CountingUnit.SHIFT &&
                    perShiftRegex.containsMatchIn(occurrenceText) && cap == null

                if (amount == null || eligibility.isEmpty() || blockers == null || clausePeriod == null || unsafeCap || unsafePerShift) {
                    unresolved++
                    reasons += "KALI repas $kaliText $articleId : occurrence ${index + 1} ambiguë/incomplète dans sa propre clause ; règle bloquée."
                    return@forEachIndexed
                }

                val extensionStatus = when {
                    officialStatus == "VIGUEUR_ETEN" && article.extensionEffectiveFrom != null -> ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
                    officialStatus == "VIGUEUR_NON_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
                    else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
                }
                val extensionDate = article.extensionEffectiveFrom.takeIf {
                    extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
                }
                val excludedEmployment = excludedEmploymentRegex.find(occurrenceText)?.groupValues?.getOrNull(1)
                    ?.trim()?.takeIf { it.isNotBlank() }
                val delivery = if (mealOrCashRegex.containsMatchIn(occurrenceText)) {
                    ConventionMealBasketV2.DeliveryMode.EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED
                } else ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE

                val rule = ConventionMealBasketV2.Rule(
                    idcc = kaliIdcc,
                    ruleId = "$SAFE_RULE_PREFIX$kaliText-$articleId-${index + 1}",
                    benefitId = benefitId(occurrenceText, index),
                    effectiveFrom = clausePeriod.first,
                    effectiveTo = clausePeriod.second,
                    classification = profile.classification.normalized(),
                    professionalStatus = status,
                    excludedEmployments = excludedEmployment?.let(::setOf) ?: emptySet(),
                    deliveryMode = delivery,
                    amountFormula = amount,
                    eligibilityAnyOf = eligibility,
                    blockers = blockers,
                    countingUnit = countingUnit,
                    maxAwardsPerCalendarDay = cap ?: 1,
                    source = "Légifrance KALI — $kaliText — $articleId",
                    conventionScopeKey = kaliText,
                    evidenceArticleIds = setOf(articleId),
                    extensionStatus = extensionStatus,
                    extensionEffectiveFrom = extensionDate
                )
                if (!rule.structurallyValid()) {
                    unresolved++
                    reasons += "KALI repas $kaliText $articleId : occurrence ${index + 1} structurée mais incohérente ; règle rejetée."
                } else {
                    structured++
                    rules += rule
                }
            }
        }

        return Diagnostic(
            rules = rules.distinctBy { it.ruleId },
            observedOccurrences = observed,
            structuredOccurrences = structured,
            unresolvedOccurrences = unresolved,
            reasons = buildList {
                addAll(reasons)
                if (observed == 0) add("KALI repas : aucune occurrence panier/indemnité repas du profil exact n'a été observée ; cela ne prouve jamais une absence de droit.")
                if (observed > structured) add("KALI repas : ${observed - structured} occurrence(s) du profil restent non structurées ; couverture de la matière bloquée.")
            }.distinct()
        )
    }

    private fun parseAmount(text: String): ConventionMealBasketV2.AmountFormula? {
        val fixed = fixedAmountRegex.findAll(text)
            .mapNotNull { parseNumber(it.groupValues[1])?.takeIf { value -> value in 0.01..1000.0 } }
            .distinct().toList()
        val mg = minimumGuaranteedRegex.findAll(text)
            .mapNotNull { parseNumber(it.groupValues[1])?.takeIf { value -> value > 0.0 && value <= 100.0 } }
            .distinct().toList()
        if (fixed.size > 1 || mg.size > 1 || (fixed.isNotEmpty() && mg.isNotEmpty())) return null
        return when {
            fixed.size == 1 -> ConventionMealBasketV2.AmountFormula.FixedEuro(fixed.single())
            mg.size == 1 -> ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(mg.single())
            externalAgreementAmountRegex.containsMatchIn(text) -> null
            else -> null
        }
    }

    private fun parseEligibility(text: String): List<ConventionMealBasketV2.EligibilityGroup> {
        val explicitTimeRange = timeRangeVocabulary.containsMatchIn(text)
        val parsedTimeRange = effectiveWindowRegex.containsMatchIn(text) ||
            employerWindowRegex.containsMatchIn(text) || startsEndsWindowRegex.containsMatchIn(text)
        if (explicitTimeRange && !parsedTimeRange) return emptyList()

        val common = buildList<ConventionMealBasketV2.Condition> {
            if (postedWorkerRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.PostedShiftWorker)
            if (unableHomeRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal)
            if (awayWorkplaceRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace)
            if (mustEatAtWorkRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.MustEatAtWorkplace)
            if (perWorkedDayRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.WorkedDay)
        }
        val temporal = mutableListOf<ConventionMealBasketV2.Condition>()

        effectiveWindowRegex.findAll(text).mapNotNull { match ->
            val hours = parseNumber(match.groupValues[1]) ?: return@mapNotNull null
            val start = parseClock(match.groupValues[2], match.groupValues[3]) ?: return@mapNotNull null
            val end = parseClock(match.groupValues[4], match.groupValues[5]) ?: return@mapNotNull null
            ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                ConventionMealBasketV2.DailyWindow(start, end), (hours * 60.0).toInt()
            ).takeIf { it.structurallyValid() }
        }.forEach(temporal::add)

        employerWindowRegex.findAll(text).mapNotNull { match ->
            val windowHours = parseNumber(match.groupValues[1]) ?: return@mapNotNull null
            val start = parseClock(match.groupValues[2], match.groupValues[3]) ?: return@mapNotNull null
            val end = parseClock(match.groupValues[4], match.groupValues[5]) ?: return@mapNotNull null
            val minimumHours = parseNumber(match.groupValues[6]) ?: return@mapNotNull null
            ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow(
                ConventionMealBasketV2.DailyWindow(start, end),
                (windowHours * 60.0).toInt(),
                (minimumHours * 60.0).toInt()
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

        if (enclosesMidnightRegex.containsMatchIn(text)) temporal += ConventionMealBasketV2.Condition.ShiftEnclosesMidnight
        if (startsMidnightRegex.containsMatchIn(text)) temporal += ConventionMealBasketV2.Condition.ShiftStartsAtMidnight

        val distinctTemporal = temporal.distinct()
        if (distinctTemporal.size > 1 && temporalAlternativeRegex.containsMatchIn(text)) return emptyList()
        val allConditions = (common + distinctTemporal).distinct()
        if (allConditions.isEmpty()) return emptyList()
        return listOf(ConventionMealBasketV2.EligibilityGroup(allConditions)).filter { it.structurallyValid() }
    }

    /** null = non-cumul présent mais insuffisamment décrit. */
    private fun parseBlockers(text: String): Set<ConventionMealBasketV2.Blocker>? {
        if (!nonCumulationRegex.containsMatchIn(text)) {
            return if (cumulationVocabularyRegex.containsMatchIn(text)) null else emptySet()
        }
        if (unknownNonCumulationTargetRegex.containsMatchIn(text)) return null
        val blockers = buildSet {
            if (canteenRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.COMPANY_CANTEEN)
            if (providedMealRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.EMPLOYER_PROVIDED_MEAL)
            if (mealVoucherRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.MEAL_VOUCHER)
            if (sameNatureRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Blocker.OTHER_SAME_NATURE_MEAL_BENEFIT)
        }
        return blockers.takeIf { it.isNotEmpty() }
    }

    private fun parseDailyCap(text: String): Int? {
        val tokens = dailyCapRegex.findAll(text).map { it.groupValues[1] }.distinct().toList()
        if (tokens.size != 1) return null
        return when (val token = tokens.single()) {
            "un", "une" -> 1
            "deux" -> 2
            "trois" -> 3
            "quatre" -> 4
            else -> token.toIntOrNull()
        }?.takeIf { it in 1..24 }
    }

    private fun clausePeriod(articleFrom: LocalDate, articleTo: LocalDate?, text: String): Pair<LocalDate, LocalDate?>? {
        val starts = clauseEffectiveFromRegex.findAll(text).mapNotNull { parseDate(it.groupValues[1]) }.distinct().toList()
        if (starts.size > 1) return null
        val from = starts.singleOrNull() ?: articleFrom
        if (from.isBefore(articleFrom)) return null
        val ends = clauseEffectiveToRegex.findAll(text).mapNotNull { parseDate(it.groupValues[1]) }.distinct().toList()
        if (ends.size > 1) return null
        val to = ends.singleOrNull() ?: articleTo
        if (to != null && (to.isBefore(from) || articleTo?.let(to::isAfter) == true)) return null
        return from to to
    }

    private fun mealOccurrences(text: String): List<MatchResult> {
        val dailyCapRanges = dailyCapRegex.findAll(text).map { it.range }.toList()
        return mealOccurrenceRegex.findAll(text)
            .filterNot { occurrence -> dailyCapRanges.any { cap -> occurrence.range.first in cap } }
            .toList()
    }

    private fun isolateMealOccurrence(scope: String, targetOffset: Int): String {
        val occurrences = mealOccurrences(scope)
        val target = occurrences.minByOrNull { abs(it.range.first - targetOffset) } ?: return scope
        val previous = occurrences.lastOrNull { it.range.first < target.range.first }
        val next = occurrences.firstOrNull { it.range.first > target.range.first }
        val start = maxOf((target.range.first - 260).coerceAtLeast(0), previous?.range?.last?.plus(1) ?: 0)
        val end = minOf(scope.length, next?.range?.first ?: scope.length, target.range.last + 1 + 760)
        return if (end > start) scope.substring(start, end) else ""
    }

    private fun parseDate(raw: String): LocalDate? {
        val normalized = OfficialKaliProfileMatcherV2.normalize(raw).trim().replace("1er ", "1 ")
        numericDateRegex.matchEntire(normalized)?.let { match ->
            return runCatching { LocalDate.of(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt()) }.getOrNull()
        }
        textualDateRegex.matchEntire(normalized)?.let { match ->
            val month = months[match.groupValues[2]] ?: return null
            return runCatching { LocalDate.of(match.groupValues[3].toInt(), month, match.groupValues[1].toInt()) }.getOrNull()
        }
        return null
    }

    private fun parseClock(hourRaw: String, minuteRaw: String): Int? {
        val hour = hourRaw.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = minuteRaw.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return hour * 60 + minute
    }

    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun benefitId(text: String, index: Int): String = when {
        unableHomeRegex.containsMatchIn(text) || awayWorkplaceRegex.containsMatchIn(text) -> "MEAL_AWAY_${index + 1}"
        explicitNightBenefitRegex.containsMatchIn(text) || employerWindowRegex.containsMatchIn(text) ||
            effectiveWindowRegex.containsMatchIn(text) || enclosesMidnightRegex.containsMatchIn(text) -> "MEAL_NIGHT_${index + 1}"
        postedWorkerRegex.containsMatchIn(text) -> "MEAL_SHIFT_${index + 1}"
        else -> "MEAL_DAY_${index + 1}"
    }

    private fun unresolved(reason: String) = Diagnostic(
        rules = emptyList(), observedOccurrences = 0, structuredOccurrences = 0, unresolvedOccurrences = 0,
        reasons = listOf("KALI repas : $reason ; aucun droit n'est enregistré.")
    )

    private val dateToken = "[0-9]{1,2}(?:er)?(?:[ /.-]+[a-z]+|[ /.-]+[0-9]{1,2})[ /.-]+[0-9]{4}"
    private val mealOccurrenceRegex = Regex("\\b(?:paniers?(?: repas| de nuit)?|indemnite(?:s)?(?: de)? repas|allocation(?:s)? de repas|prime(?:s)? de panier)\\b")
    private val explicitNightBenefitRegex = Regex("\\b(?:paniers? de nuit|prime(?:s)? de panier de nuit|indemnite(?:s)? repas de nuit)\\b")
    private val fixedAmountRegex = Regex("(?:(?:panier|indemnite|allocation|prime)[^.;\\n]{0,120}?|\\bou\\s+)([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?\\b)")
    private val minimumGuaranteedRegex = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(?:fois|x)\\s*(?:le\\s+)?minimum garanti\\b")
    private val externalAgreementAmountRegex = Regex("\\bmontant\\b[^.;\\n]{0,120}?\\b(?:fixe|determine|prevu)\\b[^.;\\n]{0,80}?\\b(?:accord|avenant)\\b")
    private val postedWorkerRegex = Regex("\\b(?:travail poste|travail en equipes?|equipes? successives?|personnel poste)\\b")
    private val unableHomeRegex = Regex("\\b(?:ne peut|impossibilite de)\\s+(?:pas\\s+)?(?:regagner|rentrer a)\\s+(?:son\\s+)?domicile\\b")
    private val awayWorkplaceRegex = Regex("\\b(?:travail(?:le)? sur chantier|deplacement professionnel|hors (?:du|de son) lieu habituel de travail)\\b")
    private val mustEatAtWorkRegex = Regex("\\b(?:oblige|tenu)\\s+de\\s+(?:prendre|consommer)\\s+(?:son\\s+)?repas\\s+(?:sur|au)\\s+(?:le\\s+)?lieu de travail\\b")
    private val perWorkedDayRegex = Regex("\\b(?:par|pour chaque)\\s+(?:jour|journee)\\s+travaille(?:e)?\\b")
    private val effectiveWindowRegex = Regex("(?:au moins|minimum de)\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h(?:eures?)?[^.;\\n]{0,80}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?")
    private val employerWindowRegex = Regex("plage\\s+de\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h(?:eures?)?[^.;\\n]{0,80}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?[^.;\\n]{0,120}?(?:au moins|minimum de)\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h")
    private val startsEndsWindowRegex = Regex("(commence|debute|se termine|finit)(?:\\s+ou\\s+(commence|debute|se termine|finit))?[^.;\\n]{0,60}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?")
    private val timeRangeVocabulary = Regex("\\bentre\\s+[0-9]{1,2}\\s*h(?:\\s*[0-9]{1,2})?\\s+et\\s+[0-9]{1,2}\\s*h(?:\\s*[0-9]{1,2})?")
    private val temporalAlternativeRegex = Regex("\\b(?:ou|soit)\\b")
    private val enclosesMidnightRegex = Regex("\\b(?:encadre|comprend|inclut|traverse)\\s+minuit\\b")
    private val startsMidnightRegex = Regex("\\b(?:commence|debute)\\s+a\\s+minuit\\b")
    private val nonCumulationRegex = Regex("\\b(?:non cumulable|ne se cumule pas|ne peut(?: pas)? se cumuler|ne peut etre cumule(?:e)?|pas cumulable|exclusif)\\b")
    private val cumulationVocabularyRegex = Regex("\\b(?:cumul|cumulable|cumule(?:e)?|cumuler)\\b")
    private val canteenRegex = Regex("\\b(?:cantine|restaurant d'entreprise)\\b")
    private val providedMealRegex = Regex("\\brepas\\s+(?:fourni|pris en charge)\\s+par\\s+l'employeur\\b")
    private val mealVoucherRegex = Regex("\\b(?:titre|ticket)[- ]restaurant\\b")
    private val sameNatureRegex = Regex("\\bavantage\\s+(?:de )?meme nature\\b")
    private val unknownNonCumulationTargetRegex = Regex("\\b(?:indemnite de deplacement|indemnite kilometrique|frais de transport|prime de transport|prime de deplacement)\\b")
    private val mealOrCashRegex = Regex("\\brepas\\s+fourni\\s+par\\s+l'employeur[^.;\\n]{0,160}?(?:a defaut|sinon)[^.;\\n]{0,100}?(?:indemnite|panier)\\b")
    private val dailyCapRegex = Regex("\\b(?:maximum(?: de)?|au plus|limite(?: de)?)\\s+(un|une|deux|trois|quatre|[0-9]{1,2})\\s+(?:paniers?|indemnites? repas|allocations? repas)[^.;\\n]{0,45}?\\b(?:par jour|par journee|quotidien)\\b")
    private val dailyCapVocabulary = Regex("\\b(?:maximum|au plus|limite)\\b[^.;\\n]{0,100}?\\b(?:par jour|par journee|quotidien)\\b")
    private val perShiftRegex = Regex("\\b(?:par poste|par equipe|par vacation|par shift)\\b")
    private val clauseEffectiveFromRegex = Regex("\\b(?:a compter du|a partir du|des le)\\s+($dateToken)")
    private val clauseEffectiveToRegex = Regex("\\b(?:jusqu'au|jusqu au|prendra fin le|expire le)\\s+($dateToken)")
    private val numericDateRegex = Regex("([0-9]{1,2})[ /.-]+([0-9]{1,2})[ /.-]+([0-9]{4})")
    private val textualDateRegex = Regex("([0-9]{1,2})\\s+([a-z]+)\\s+([0-9]{4})")
    private val months = mapOf(
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6,
        "juillet" to 7, "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12
    )
    private val excludedEmploymentRegex = Regex("\\b(?:sauf|a l'exception des?)\\s+([a-z][a-z -]{2,40}?)(?:[.;,]|$)")
    private val compoundEmploymentExclusionRegex = Regex("\\b(?:sauf|a l'exception des?)\\s+[^.;\\n]{1,80}?\\b(?:et|ou)\\b[^.;\\n]{1,80}(?:[.;,]|$)")
    private val territorialVocabulary = Regex("\\b(?:departement|departements|region|regions|zone geographique|territoire territorial)\\b")
    private val classificationVocabulary = Regex("\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction)s?\\b|\\bposte\\s*[:\\-]")
    private val kaliTextIdRegex = Regex("^KALITEXT\\d+$")
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
    private val acceptedStatuses = setOf("VIGUEUR", "VIGUEUR_ETEN", "VIGUEUR_NON_ETEN")
}
