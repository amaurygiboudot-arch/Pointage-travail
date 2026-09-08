package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.CompanyProvidentBenefitV2
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import java.time.LocalDate
import java.util.Locale

/**
 * Parseur ACCO strict des garanties de prévoyance.
 *
 * Une famille mentionnée n'est jamais assimilée à une garantie structurée. Chaque occurrence doit
 * prouver dans son propre périmètre le profil, la formule, l'ancienneté et, pour les prestations de
 * remplacement, l'articulation avec la Sécurité sociale. Une ambiguïté laisse le paquet incomplet.
 */
object OfficialAccoProvidentBenefitParserV2 {
    data class Diagnostic(
        val rules: List<CompanyProvidentBenefitV2.Rule>,
        val observedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val structuredFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val unresolvedOccurrenceFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val reasons: List<String>
    )

    private data class Window(val text: String, val targetOffset: Int)
    private data class Parsed(
        val guarantee: CompanyProvidentBenefitV2.Guarantee,
        val minimumSeniorityMonths: Int,
        val excerpt: String
    )

    fun parse(
        profile: ConventionLegalProfileV2,
        agreementId: String,
        officialText: String
    ): Diagnostic {
        val normalizedAgreementId = agreementId.trim().uppercase(Locale.ROOT)
        if (!normalizedAgreementId.matches(accoTextIdRegex)) return unresolved("identifiant ACCOTEXT officiel invalide")
        val siret = profile.siret.filter(Char::isDigit)
        if (siret.length != 14) return unresolved("SIRET exact du profil manquant")
        val status = profile.professionalStatus?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return unresolved("statut cadre/non-cadre exact manquant")
        val classification = profile.classification.normalized()
        if (classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")
        if (officialText.isBlank()) return unresolved("texte officiel ACCO vide")

        val normalized = OfficialKaliProfileMatcherV2.normalize(officialText)
        val effectiveFromCandidates = effectiveFromRegexes
            .flatMap { regex -> regex.findAll(normalized).mapNotNull { parseDateToken(it.groupValues[1]) }.toList() }
            .distinct()
        if (effectiveFromCandidates.size != 1) return unresolved("date d'entrée en vigueur ACCO absente ou ambiguë")
        val effectiveFrom = effectiveFromCandidates.single()

        val indefinite = indefiniteDurationRegex.containsMatchIn(normalized)
        val effectiveToCandidates = effectiveToRegexes
            .flatMap { regex -> regex.findAll(normalized).mapNotNull { parseDateToken(it.groupValues[1]) }.toList() }
            .distinct()
        if (effectiveToCandidates.size > 1 || (indefinite && effectiveToCandidates.isNotEmpty())) {
            return unresolved("durée ou date de fin ACCO contradictoire")
        }
        val effectiveTo = when {
            indefinite -> null
            effectiveToCandidates.size == 1 -> effectiveToCandidates.single()
            else -> return unresolved("durée de l'accord ACCO non démontrée")
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) return unresolved("date de fin ACCO antérieure à la date d'effet")

        val observed = linkedSetOf<ConventionProvidentBenefitV2.Family>()
        val unresolvedOccurrences = linkedSetOf<ConventionProvidentBenefitV2.Family>()
        val parsed = mutableListOf<Parsed>()

        familyMarkers.forEach { (family, regex) ->
            regex.findAll(normalized).forEach { match ->
                val window = boundedWindow(normalized, match)
                if (!profileMatches(window, normalized, classification, status)) return@forEach
                observed += family
                val value = parseFamily(family, window.text)
                if (value == null) {
                    unresolvedOccurrences += family
                } else {
                    parsed += value
                }
            }
        }

        if (observed.isEmpty()) return unresolved("aucune famille de garantie de prévoyance applicable au profil exact n'est observée")

        val accepted = mutableListOf<Parsed>()
        parsed.groupBy { it.guarantee.family to it.guarantee.invalidityCategory }
            .forEach { (key, values) ->
                val distinct = values.distinctBy { parsedFingerprint(it) }
                if (distinct.size != 1) {
                    unresolvedOccurrences += key.first
                } else {
                    accepted += distinct.single()
                }
            }

        val structuredFamilies = accepted.map { it.guarantee.family }.toSet()
        val packageComplete = unresolvedOccurrences.isEmpty() && structuredFamilies.containsAll(observed)
        val rules = accepted.map { value ->
            CompanyProvidentBenefitV2.Rule(
                agreementId = normalizedAgreementId,
                siret = siret,
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                classification = classification,
                professionalStatus = status,
                minimumSeniorityMonths = value.minimumSeniorityMonths,
                guarantee = value.guarantee,
                observedFamilies = observed.toSet(),
                packageComplete = packageComplete,
                evidenceExcerpt = value.excerpt.take(1600)
            )
        }.filter { it.structurallyValid() }

        return Diagnostic(
            rules = rules,
            observedFamilies = observed,
            structuredFamilies = rules.map { it.guarantee.family }.toSet(),
            unresolvedOccurrenceFamilies = unresolvedOccurrences,
            reasons = buildList {
                if (!packageComplete) {
                    add("ACCO garanties : paquet incomplet ; au moins une occurrence observée ne prouve pas entièrement sa formule ou son périmètre.")
                }
                unresolvedOccurrences.forEach { family ->
                    add("ACCO garanties : ${family.name} observée mais occurrence ambiguë ou incomplète ; aucune équivalence n'est déduite.")
                }
                if (rules.isEmpty()) add("ACCO garanties : aucune prestation complète et non ambiguë n'a été structurée.")
            }.distinct()
        )
    }

    private fun parseFamily(
        family: ConventionProvidentBenefitV2.Family,
        window: String
    ): Parsed? {
        val formula = parseFormula(window) ?: return null
        val seniority = parseSeniorityMonths(window) ?: return null
        val maximumDuration = parseMaximumDurationDays(window)
        return when (family) {
            ConventionProvidentBenefitV2.Family.DEATH_CAPITAL -> Parsed(
                guarantee = CompanyProvidentBenefitV2.Guarantee(
                    family = family,
                    label = "Capital décès — ${formulaLabel(formula)}",
                    formula = formula,
                    socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
                ),
                minimumSeniorityMonths = seniority,
                excerpt = window
            )

            ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT -> {
                if (!incomeBenefitRegex.containsMatchIn(window)) return null
                val waiting = parseWaitingDays(window) ?: return null
                val treatment = parseSocialSecurityTreatment(window) ?: return null
                Parsed(
                    guarantee = CompanyProvidentBenefitV2.Guarantee(
                        family = family,
                        label = "Incapacité temporaire — ${formulaLabel(formula)}",
                        formula = formula,
                        waitingPeriodDays = waiting,
                        maximumDurationDays = maximumDuration,
                        socialSecurityTreatment = treatment
                    ),
                    minimumSeniorityMonths = seniority,
                    excerpt = window
                )
            }

            ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION -> {
                if (!pensionRegex.containsMatchIn(window)) return null
                val categories = invalidityCategoryRegex.findAll(window)
                    .mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { category -> category in 1..3 } }
                    .toSet()
                if (categories.size > 1) return null
                val treatment = parseSocialSecurityTreatment(window) ?: return null
                Parsed(
                    guarantee = CompanyProvidentBenefitV2.Guarantee(
                        family = family,
                        label = "Invalidité${categories.singleOrNull()?.let { " catégorie $it" }.orEmpty()} — ${formulaLabel(formula)}",
                        formula = formula,
                        maximumDurationDays = maximumDuration,
                        invalidityCategory = categories.singleOrNull(),
                        socialSecurityTreatment = treatment
                    ),
                    minimumSeniorityMonths = seniority,
                    excerpt = window
                )
            }

            ConventionProvidentBenefitV2.Family.SPOUSE_PENSION -> Parsed(
                guarantee = CompanyProvidentBenefitV2.Guarantee(
                    family = family,
                    label = "Rente conjoint — ${formulaLabel(formula)}",
                    formula = formula,
                    maximumDurationDays = maximumDuration,
                    socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
                ),
                minimumSeniorityMonths = seniority,
                excerpt = window
            )

            ConventionProvidentBenefitV2.Family.EDUCATION_PENSION -> Parsed(
                guarantee = CompanyProvidentBenefitV2.Guarantee(
                    family = family,
                    label = "Rente éducation — ${formulaLabel(formula)}",
                    formula = formula,
                    maximumDurationDays = maximumDuration,
                    socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
                ),
                minimumSeniorityMonths = seniority,
                excerpt = window
            )
        }.takeIf { it.guarantee.structurallyValid() }
    }

    private fun parseFormula(window: String): ConventionProvidentBenefitV2.Formula? {
        val candidates = buildList {
            annualSalaryPercentRegex.findAll(window).forEach { match ->
                percentFormula(ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY, match.groupValues[1])?.let(::add)
            }
            monthlySalaryPercentRegex.findAll(window).forEach { match ->
                percentFormula(ConventionProvidentBenefitV2.Basis.MONTHLY_REFERENCE_SALARY, match.groupValues[1])?.let(::add)
            }
            pmssMultipleRegex.findAll(window).forEach { match ->
                parseNumber(match.groupValues[1])?.takeIf { it > 0.0 && it <= 100.0 }?.let { multiple ->
                    ConventionProvidentBenefitV2.Formula(
                        basis = ConventionProvidentBenefitV2.Basis.PMSS,
                        coefficient = multiple
                    ).takeIf { it.structurallyValid() }?.let(::add)
                }
            }
            fixedEuroRegex.findAll(window).forEach { match ->
                parseNumber(match.groupValues[1])?.takeIf { it >= 0.0 && it <= 10_000_000.0 }?.let { amount ->
                    ConventionProvidentBenefitV2.Formula(
                        basis = ConventionProvidentBenefitV2.Basis.FIXED_EURO,
                        fixedAmount = amount
                    ).takeIf { it.structurallyValid() }?.let(::add)
                }
            }
        }.distinctBy(::formulaFingerprint)
        return candidates.singleOrNull()
    }

    private fun percentFormula(
        basis: ConventionProvidentBenefitV2.Basis,
        raw: String
    ): ConventionProvidentBenefitV2.Formula? {
        val value = parseNumber(raw)?.takeIf { it > 0.0 && it <= 10_000.0 } ?: return null
        return ConventionProvidentBenefitV2.Formula(basis = basis, coefficient = value / 100.0)
            .takeIf { it.structurallyValid() }
    }

    private fun parseWaitingDays(window: String): Int? {
        val candidates = buildSet {
            if (noWaitingRegex.containsMatchIn(window)) add(0)
            franchiseRegex.findAll(window).forEach { match ->
                match.groupValues[1].toIntOrNull()?.takeIf { it in 0..3660 }?.let(::add)
            }
            startDayRegex.findAll(window).forEach { match ->
                match.groupValues[1].toIntOrNull()?.let { day -> (day - 1).takeIf { it in 0..3660 }?.let(::add) }
            }
        }
        return candidates.singleOrNull()
    }

    private fun parseMaximumDurationDays(window: String): Int? {
        val candidates = durationDaysRegex.findAll(window)
            .mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { days -> days in 1..36600 } }
            .toSet()
        return candidates.singleOrNull()
    }

    private fun parseSocialSecurityTreatment(window: String): ConventionProvidentBenefitV2.SocialSecurityTreatment? = when {
        deductSsRegex.containsMatchIn(window) -> ConventionProvidentBenefitV2.SocialSecurityTreatment.DEDUCT_SOCIAL_SECURITY
        includedSsRegex.containsMatchIn(window) -> ConventionProvidentBenefitV2.SocialSecurityTreatment.INCLUDED_IN_TARGET_TOTAL
        additionalSsRegex.containsMatchIn(window) -> ConventionProvidentBenefitV2.SocialSecurityTreatment.ADDITIONAL_TO_SOCIAL_SECURITY
        else -> null
    }

    private fun parseSeniorityMonths(window: String): Int? {
        val candidates = buildSet {
            if (noSeniorityRegex.containsMatchIn(window)) add(0)
            seniorityRegex.findAll(window).forEach { match ->
                val value = match.groupValues[1].toIntOrNull() ?: return@forEach
                val months = when {
                    match.groupValues[2].startsWith("an") -> value * 12
                    match.groupValues[2].startsWith("mois") -> value
                    else -> null
                }?.takeIf { it in 0..600 }
                if (months != null) add(months)
            }
        }
        return candidates.singleOrNull()
    }

    private fun profileMatches(
        window: Window,
        wholeText: String,
        classification: ConventionClassificationV2,
        status: String
    ): Boolean {
        val hasClassification = classificationVocabulary.containsMatchIn(window.text)
        return if (hasClassification) {
            OfficialKaliProfileMatcherV2.nearestScopeMatches(
                rawText = wholeText,
                classification = classification,
                professionalStatus = status,
                targetOffset = window.targetOffset,
                maxClassificationSpan = 420
            )
        } else {
            OfficialKaliProfileMatcherV2.statusScopeMatches(window.text, status)
        }
    }

    private fun boundedWindow(text: String, match: MatchResult): Window {
        val desiredStart = (match.range.first - 260).coerceAtLeast(0)
        val desiredEnd = (match.range.last + 1 + 520).coerceAtMost(text.length)
        val previous = familyBoundaryRegex.findAll(text, desiredStart)
            .takeWhile { it.range.first < match.range.first }
            .lastOrNull()
        val next = familyBoundaryRegex.find(text, (match.range.last + 1).coerceAtMost(text.length))
        val start = if (previous != null) match.range.first else desiredStart
        val end = minOf(desiredEnd, next?.range?.first ?: desiredEnd)
        return Window(
            text = if (end > start) text.substring(start, end) else "",
            targetOffset = match.range.first
        )
    }

    private fun parsedFingerprint(value: Parsed): String = listOf(
        CompanyProvidentBenefitV2.fingerprint(value.guarantee),
        value.minimumSeniorityMonths.toString()
    ).joinToString("|")

    private fun formulaFingerprint(value: ConventionProvidentBenefitV2.Formula): String = listOf(
        value.basis.name,
        value.coefficient?.toString().orEmpty(),
        value.fixedAmount?.toString().orEmpty()
    ).joinToString("|")

    private fun formulaLabel(value: ConventionProvidentBenefitV2.Formula): String = when (value.basis) {
        ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY -> "${value.coefficient?.times(100.0)} % du salaire annuel de référence"
        ConventionProvidentBenefitV2.Basis.MONTHLY_REFERENCE_SALARY -> "${value.coefficient?.times(100.0)} % du salaire mensuel de référence"
        ConventionProvidentBenefitV2.Basis.PMSS -> "${value.coefficient} PMSS"
        ConventionProvidentBenefitV2.Basis.FIXED_EURO -> "${value.fixedAmount} €"
    }

    private fun parseDateToken(raw: String): LocalDate? {
        val token = raw.trim().lowercase(Locale.FRANCE).replace("1er", "1")
        val numeric = Regex("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})$").matchEntire(token)
        if (numeric != null) {
            return runCatching {
                LocalDate.of(numeric.groupValues[3].toInt(), numeric.groupValues[2].toInt(), numeric.groupValues[1].toInt())
            }.getOrNull()
        }
        val textual = Regex("^(\\d{1,2})\\s+([a-z]+)\\s+(\\d{4})$").matchEntire(token) ?: return null
        val month = months[textual.groupValues[2]] ?: return null
        return runCatching { LocalDate.of(textual.groupValues[3].toInt(), month, textual.groupValues[1].toInt()) }.getOrNull()
    }

    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').replace(" ", "").toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun unresolved(reason: String) = Diagnostic(
        rules = emptyList(),
        observedFamilies = emptySet(),
        structuredFamilies = emptySet(),
        unresolvedOccurrenceFamilies = emptySet(),
        reasons = listOf("ACCO garanties prévoyance : $reason ; aucune garantie d'entreprise n'est enregistrée.")
    )

    private val accoTextIdRegex = Regex("^ACCOTEXT\\d+$")
    private val classificationVocabulary = Regex("\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction|poste)s?\\b")
    private val dateToken = "(\\d{1,2}(?:er)?[./-]\\d{1,2}[./-]\\d{4}|\\d{1,2}(?:er)?\\s+(?:janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)\\s+\\d{4})"
    private val effectiveFromRegexes = listOf(
        Regex("\\b(?:entre en vigueur|prend effet|s'applique)\\s+(?:a compter du|a compter de|le|du)?\\s*$dateToken"),
        Regex("\\bdate d'effet\\s*[:.-]?\\s*$dateToken")
    )
    private val effectiveToRegexes = listOf(
        Regex("\\b(?:cesse de produire ses effets|prend fin|expire)\\s+(?:le)?\\s*$dateToken"),
        Regex("\\bjusqu'au\\s+$dateToken")
    )
    private val indefiniteDurationRegex = Regex("\\b(?:conclu|conclue)?\\s*(?:pour)?\\s*une duree indeterminee\\b|\\bduree indeterminee\\b")

    private val deathRegex = Regex("\\b(?:capital|garantie) deces\\b")
    private val incapacityRegex = Regex("\\b(?:incapacite temporaire|incapacite de travail|arret de travail)\\b")
    private val invalidityRegex = Regex("\\binvalidite\\b")
    private val spousePensionRegex = Regex("\\brente (?:de )?(?:conjoint|conjoint survivant)\\b")
    private val educationPensionRegex = Regex("\\brente education\\b")
    private val familyMarkers = linkedMapOf(
        ConventionProvidentBenefitV2.Family.DEATH_CAPITAL to deathRegex,
        ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT to incapacityRegex,
        ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION to invalidityRegex,
        ConventionProvidentBenefitV2.Family.SPOUSE_PENSION to spousePensionRegex,
        ConventionProvidentBenefitV2.Family.EDUCATION_PENSION to educationPensionRegex
    )
    private val familyBoundaryRegex = Regex(
        "\\b(?:(?:capital|garantie) deces|incapacite temporaire|incapacite de travail|arret de travail|invalidite|rente (?:de )?(?:conjoint|conjoint survivant)|rente education)\\b"
    )

    private val annualSalaryPercentRegex = Regex("(\\d{1,4}(?:[.,]\\d{1,4})?)\\s*%\\s*(?:du|de la)\\s*(?:salaire|remuneration)\\s+annuel(?:le)?(?:\\s+brut(?:e)?)?(?:\\s+de reference)?")
    private val monthlySalaryPercentRegex = Regex("(\\d{1,4}(?:[.,]\\d{1,4})?)\\s*%\\s*(?:du|de la)\\s*(?:salaire|remuneration)\\s+mensuel(?:le)?(?:\\s+brut(?:e)?)?(?:\\s+de reference)?")
    private val pmssMultipleRegex = Regex("(\\d{1,3}(?:[.,]\\d{1,4})?)\\s*(?:fois|x)\\s*(?:le\\s+)?pmss\\b")
    private val fixedEuroRegex = Regex("\\b(?:capital|rente|indemnite)[^.;]{0,80}?(\\d{1,7}(?:[.,]\\d{1,2})?)\\s*(?:€|euros?)\\b")
    private val incomeBenefitRegex = Regex("\\b(?:indemnite(?:s)? journaliere(?:s)?|revenu de remplacement|maintien de salaire|maintien de la remuneration)\\b")
    private val pensionRegex = Regex("\\b(?:rente|pension)\\b")
    private val invalidityCategoryRegex = Regex("\\b(?:categorie|cat)\\s*([123])\\b")
    private val noWaitingRegex = Regex("\\b(?:sans franchise|sans delai de carence)\\b")
    private val franchiseRegex = Regex("\\b(?:franchise|carence)\\s*(?:de|:)\\s*(\\d{1,4})\\s*jours?\\b")
    private val startDayRegex = Regex("\\ba compter du\\s*(\\d{1,4})(?:e|eme)?\\s*jour\\b")
    private val durationDaysRegex = Regex("\\b(?:pendant|dans la limite de|pour une duree maximale de)\\s*(\\d{1,5})\\s*jours?\\b")
    private val noSeniorityRegex = Regex("\\b(?:sans condition d'anciennete|des l'embauche|a compter de l'embauche)\\b")
    private val seniorityRegex = Regex("\\b(?:au moins|apres|a partir de)\\s*(\\d{1,3})\\s*(ans?|mois)(?: d'anciennete)?\\b")
    private val deductSsRegex = Regex("\\b(?:sous deduction|deduction faite|apres deduction)[^.;]{0,120}(?:securite sociale|ijss|indemnites journalieres)\\b")
    private val includedSsRegex = Regex("\\b(?:y compris|incluant|prestations comprises)[^.;]{0,120}(?:securite sociale|ijss|indemnites journalieres)\\b")
    private val additionalSsRegex = Regex("\\b(?:en complement|s'ajoute|s'ajoutent)[^.;]{0,120}(?:securite sociale|ijss|indemnites journalieres)\\b")

    private val months = mapOf(
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4,
        "mai" to 5, "juin" to 6, "juillet" to 7, "aout" to 8,
        "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12
    )
}
