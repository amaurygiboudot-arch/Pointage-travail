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
            val occurrences = mealOccurrenceRegex.findAll(body).toList()
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
                    reasons += "KALI repas $kaliText $articleId : portée territoriale détectée mais non structurée dans ce lot ; règle bloquée."
                    return@forEachIndexed
                }

                val amount = parseAmount(occurrenceText)
                val eligibility = parseEligibility(occurrenceText)
                if (amount == null || eligibility.isEmpty()) {
                    unresolved++
                    reasons += "KALI repas $kaliText $articleId : occurrence ${index + 1} sans montant/formule et conditions complètes dans sa propre clause ; règle bloquée."
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
                    ruleId = "KALI-MEAL-$kaliText-$articleId-${index + 1}",
                    benefitId = benefitId(occurrenceText, index),
                    effectiveFrom = article.effectiveFrom,
                    effectiveTo = article.effectiveTo,
                    classification = profile.classification.normalized(),
                    professionalStatus = status,
                    excludedEmployments = excludedEmployment?.let(::setOf) ?: emptySet(),
                    deliveryMode = delivery,
                    amountFormula = amount,
                    eligibilityAnyOf = eligibility,
                    blockers = parseBlockers(occurrenceText),
                    countingUnit = if (perWorkedDayRegex.containsMatchIn(occurrenceText)) {
                        ConventionMealBasketV2.CountingUnit.WORKED_DAY
                    } else ConventionMealBasketV2.CountingUnit.SHIFT,
                    maxAwardsPerCalendarDay = 1,
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
        val fixed = fixedAmountRegex.findAll(text).mapNotNull { parseNumber(it.groupValues[1])?.takeIf { value -> value in 0.01..1000.0 } }.distinct().toList()
        val mg = minimumGuaranteedRegex.findAll(text).mapNotNull { parseNumber(it.groupValues[1])?.takeIf { value -> value > 0.0 && value <= 100.0 } }.distinct().toList()
        if (fixed.size > 1 || mg.size > 1 || (fixed.isNotEmpty() && mg.isNotEmpty())) return null
        return when {
            fixed.size == 1 -> ConventionMealBasketV2.AmountFormula.FixedEuro(fixed.single())
            mg.size == 1 -> ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(mg.single())
            externalAgreementAmountRegex.containsMatchIn(text) -> ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount
            else -> null
        }
    }

    private fun parseEligibility(text: String): List<ConventionMealBasketV2.EligibilityGroup> {
        val groups = mutableListOf<ConventionMealBasketV2.EligibilityGroup>()
        val common = buildList<ConventionMealBasketV2.Condition> {
            if (postedWorkerRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.PostedShiftWorker)
            if (unableHomeRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal)
            if (awayWorkplaceRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace)
            if (mustEatAtWorkRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.MustEatAtWorkplace)
            if (perWorkedDayRegex.containsMatchIn(text)) add(ConventionMealBasketV2.Condition.WorkedDay)
        }

        effectiveWindowRegex.findAll(text).mapNotNull { match ->
            val hours = parseNumber(match.groupValues[1]) ?: return@mapNotNull null
            val start = parseClock(match.groupValues[2], match.groupValues[3]) ?: return@mapNotNull null
            val end = parseClock(match.groupValues[4], match.groupValues[5]) ?: return@mapNotNull null
            ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                ConventionMealBasketV2.DailyWindow(start, end), (hours * 60.0).toInt()
            ).takeIf { it.structurallyValid() }
        }.forEach { groups += ConventionMealBasketV2.EligibilityGroup((common + it).distinct()) }

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
        }.forEach { groups += ConventionMealBasketV2.EligibilityGroup((common + it).distinct()) }

        startsEndsWindowRegex.findAll(text).mapNotNull { match ->
            val start = parseClock(match.groupValues[1], match.groupValues[2]) ?: return@mapNotNull null
            val end = parseClock(match.groupValues[3], match.groupValues[4]) ?: return@mapNotNull null
            ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow(
                ConventionMealBasketV2.DailyWindow(start, end)
            ).takeIf { it.structurallyValid() }
        }.forEach { groups += ConventionMealBasketV2.EligibilityGroup((common + it).distinct()) }

        if (enclosesMidnightRegex.containsMatchIn(text)) {
            groups += ConventionMealBasketV2.EligibilityGroup((common + ConventionMealBasketV2.Condition.ShiftEnclosesMidnight).distinct())
        }
        if (startsMidnightRegex.containsMatchIn(text)) {
            groups += ConventionMealBasketV2.EligibilityGroup((common + ConventionMealBasketV2.Condition.ShiftStartsAtMidnight).distinct())
        }
        if (groups.isEmpty() && common.isNotEmpty()) groups += ConventionMealBasketV2.EligibilityGroup(common.distinct())
        return groups.filter { it.structurallyValid() }.distinctBy { group -> group.allOf.joinToString("|") { it.toString() } }
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

    private fun isolateMealOccurrence(scope: String, targetOffset: Int): String {
        val occurrences = mealOccurrenceRegex.findAll(scope).toList()
        val target = occurrences.minByOrNull { abs(it.range.first - targetOffset) } ?: return scope
        val previous = occurrences.lastOrNull { it.range.first < target.range.first }
        val next = occurrences.firstOrNull { it.range.first > target.range.first }
        val start = maxOf((target.range.first - 260).coerceAtLeast(0), previous?.range?.last?.plus(1) ?: 0)
        val end = minOf(scope.length, next?.range?.first ?: scope.length, target.range.last + 1 + 760)
        return if (end > start) scope.substring(start, end) else ""
    }

    private fun parseClock(hourRaw: String, minuteRaw: String): Int? {
        val hour = hourRaw.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = minuteRaw.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return hour * 60 + minute
    }

    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun benefitId(text: String, index: Int): String = when {
        unableHomeRegex.containsMatchIn(text) || awayWorkplaceRegex.containsMatchIn(text) -> "MEAL_AWAY_${index + 1}"
        employerWindowRegex.containsMatchIn(text) || effectiveWindowRegex.containsMatchIn(text) || enclosesMidnightRegex.containsMatchIn(text) -> "MEAL_NIGHT_${index + 1}"
        postedWorkerRegex.containsMatchIn(text) -> "MEAL_SHIFT_${index + 1}"
        else -> "MEAL_DAY_${index + 1}"
    }

    private fun unresolved(reason: String) = Diagnostic(
        rules = emptyList(), observedOccurrences = 0, structuredOccurrences = 0, unresolvedOccurrences = 0,
        reasons = listOf("KALI repas : $reason ; aucun droit n'est enregistré.")
    )

    private val mealOccurrenceRegex = Regex("\\b(?:paniers?(?: repas| de nuit)?|indemnite(?:s)?(?: de)? repas|allocation(?:s)? de repas|prime(?:s)? de panier)\\b")
    private val fixedAmountRegex = Regex("(?:panier|indemnite|allocation|prime)[^.;\\n]{0,120}?([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?\\b)")
    private val minimumGuaranteedRegex = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(?:fois|x)\\s*(?:le\\s+)?minimum garanti\\b")
    private val externalAgreementAmountRegex = Regex("\\bmontant\\b[^.;\\n]{0,120}?\\b(?:fixe|determine|prevu)\\b[^.;\\n]{0,80}?\\b(?:accord|avenant)\\b")
    private val postedWorkerRegex = Regex("\\b(?:travail poste|travail en equipes?|equipes? successives?|personnel poste)\\b")
    private val unableHomeRegex = Regex("\\b(?:ne peut|impossibilite de)\\s+(?:pas\\s+)?(?:regagner|rentrer a)\\s+(?:son\\s+)?domicile\\b")
    private val awayWorkplaceRegex = Regex("\\b(?:travail(?:le)? sur chantier|deplacement professionnel|hors (?:du|de son) lieu habituel de travail)\\b")
    private val mustEatAtWorkRegex = Regex("\\b(?:oblige|tenu)\\s+de\\s+(?:prendre|consommer)\\s+(?:son\\s+)?repas\\s+(?:sur|au)\\s+(?:le\\s+)?lieu de travail\\b")
    private val perWorkedDayRegex = Regex("\\b(?:par|pour chaque)\\s+(?:jour|journee)\\s+travaille(?:e)?\\b")
    private val effectiveWindowRegex = Regex("(?:au moins|minimum de)\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h(?:eures?)?[^.;\\n]{0,80}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?")
    private val employerWindowRegex = Regex("plage\\s+de\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h(?:eures?)?[^.;\\n]{0,80}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?[^.;\\n]{0,120}?(?:au moins|minimum de)\\s+([0-9]+(?:[.,][0-9]+)?)\\s*h")
    private val startsEndsWindowRegex = Regex("(?:commence|debute|se termine|finit)(?:\\s+ou\\s+(?:commence|debute|se termine|finit))?[^.;\\n]{0,60}?entre\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?\\s+et\\s+([0-9]{1,2})\\s*h(?:\\s*([0-9]{1,2}))?")
    private val enclosesMidnightRegex = Regex("\\b(?:encadre|comprend|inclut|traverse)\\s+minuit\\b")
    private val startsMidnightRegex = Regex("\\b(?:commence|debute)\\s+a\\s+minuit\\b")
    private val nonCumulationRegex = Regex("\\b(?:non cumulable|ne se cumule pas|pas cumulable|exclusif)\\b")
    private val canteenRegex = Regex("\\b(?:cantine|restaurant d'entreprise)\\b")
    private val providedMealRegex = Regex("\\brepas\\s+(?:fourni|pris en charge)\\s+par\\s+l'employeur\\b")
    private val mealVoucherRegex = Regex("\\b(?:titre|ticket)[- ]restaurant\\b")
    private val sameNatureRegex = Regex("\\bavantage\\s+(?:de )?meme nature\\b")
    private val mealOrCashRegex = Regex("\\brepas\\s+fourni\\s+par\\s+l'employeur[^.;\\n]{0,160}?(?:a defaut|sinon)[^.;\\n]{0,100}?(?:indemnite|panier)\\b")
    private val excludedEmploymentRegex = Regex("\\b(?:sauf|a l'exception des?)\\s+([a-z][a-z -]{2,40}?)(?:[.;,]|$)")
    private val territorialVocabulary = Regex("\\b(?:departement|departements|region|regions|zone geographique|territoire territorial)\\b")
    private val classificationVocabulary = Regex("\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction|poste)s?\\b")
    private val kaliTextIdRegex = Regex("^KALITEXT\\d+$")
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
    private val acceptedStatuses = setOf("VIGUEUR", "VIGUEUR_ETEN", "VIGUEUR_NON_ETEN")
}
