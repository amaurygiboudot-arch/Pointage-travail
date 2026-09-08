package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import java.time.LocalDate
import java.util.Locale

/**
 * Parseur ACCO fail-closed des cotisations de prévoyance d'entreprise.
 *
 * Première version volontairement limitée aux taux salarié/employeur explicites appliqués au
 * salaire brut. Les tranches PMSS, forfaits en euros, répartitions implicites ou périodes ambiguës
 * restent bloqués jusqu'à un modèle dédié. Le résultat est une preuve structurée locale ; il ne
 * remplace jamais à lui seul une règle de branche protégée par L2253-1.
 */
object OfficialAccoProvidentContributionParserV2 {
    enum class Basis { GROSS_SALARY }

    data class Rule(
        val agreementId: String,
        val siret: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val classification: ConventionClassificationV2,
        val professionalStatus: String,
        val minimumSeniorityMonths: Int,
        val basis: Basis,
        val employeeRate: Double,
        val employerRate: Double,
        val evidenceExcerpt: String
    ) {
        val fingerprint: String
            get() = listOf(
                "ACCO_PROVIDENT_CONTRIBUTION",
                agreementId,
                siret,
                effectiveFrom.toString(),
                effectiveTo?.toString().orEmpty(),
                classification.label(),
                professionalStatus,
                minimumSeniorityMonths.toString(),
                basis.name,
                employeeRate.toString(),
                employerRate.toString()
            ).joinToString("|")
    }

    data class Diagnostic(
        val rule: Rule?,
        val reasons: List<String>
    )

    private data class RatePair(val employee: Double, val employer: Double)
    private data class Window(val text: String, val targetOffset: Int)

    fun parse(
        profile: ConventionLegalProfileV2,
        agreementId: String,
        officialText: String
    ): Diagnostic {
        val normalizedAgreementId = agreementId.trim().uppercase(Locale.ROOT)
        if (!normalizedAgreementId.matches(accoTextIdRegex)) {
            return unresolved("identifiant ACCOTEXT officiel invalide")
        }
        val siret = profile.siret.filter(Char::isDigit)
        if (siret.length != 14) return unresolved("SIRET exact du profil manquant")
        if (officialText.isBlank()) return unresolved("texte officiel ACCO vide")

        val status = profile.professionalStatus
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return unresolved("statut cadre/non-cadre exact manquant")
        if (profile.classification.isEmpty()) {
            return unresolved("classification conventionnelle exacte manquante")
        }

        val normalized = OfficialKaliProfileMatcherV2.normalize(officialText)
        val effectiveFromCandidates = effectiveFromRegexes
            .flatMap { it.findAll(normalized).mapNotNull { match -> parseDateToken(match.groupValues[1]) }.toList() }
            .distinct()
        if (effectiveFromCandidates.size != 1) {
            return unresolved("date d'entrée en vigueur ACCO absente ou ambiguë")
        }
        val effectiveFrom = effectiveFromCandidates.single()

        val indefinite = indefiniteDurationRegex.containsMatchIn(normalized)
        val effectiveToCandidates = effectiveToRegexes
            .flatMap { it.findAll(normalized).mapNotNull { match -> parseDateToken(match.groupValues[1]) }.toList() }
            .distinct()
        if (effectiveToCandidates.size > 1 || (indefinite && effectiveToCandidates.isNotEmpty())) {
            return unresolved("durée ou date de fin ACCO contradictoire")
        }
        val effectiveTo = when {
            indefinite -> null
            effectiveToCandidates.size == 1 -> effectiveToCandidates.single()
            else -> return unresolved("durée de l'accord ACCO non démontrée")
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            return unresolved("date de fin ACCO antérieure à la date d'effet")
        }

        val windows = contributionMarkers
            .flatMap { regex -> regex.findAll(normalized).map { match -> scopedWindow(normalized, match.range.first) }.toList() }
            .distinctBy { it.text to it.targetOffset }
            .filter { window -> profileMatches(window, normalized, profile.classification, status) }
        if (windows.isEmpty()) {
            return unresolved("aucune clause de cotisation de prévoyance applicable au profil exact")
        }

        if (windows.any { unsupportedBasisRegex.containsMatchIn(it.text) }) {
            return unresolved("assiette ACCO avec tranche/PMSS/plafond non prise en charge automatiquement")
        }
        val basisConfirmed = windows.any { grossBasisRegex.containsMatchIn(it.text) }
        if (!basisConfirmed) return unresolved("assiette salaire brut non explicitement démontrée")

        val ratePairs = windows.mapNotNull(::parseRates).distinct()
        if (ratePairs.size != 1) {
            return unresolved("parts salariale et patronale absentes, incomplètes ou contradictoires")
        }
        val rates = ratePairs.single()

        val seniorityCandidates = windows.flatMap(::parseSeniority).distinct()
        if (seniorityCandidates.size != 1) {
            return unresolved("condition d'ancienneté ACCO absente ou ambiguë")
        }

        val evidence = windows
            .firstOrNull { parseRates(it) == rates }
            ?.text
            ?.take(1600)
            .orEmpty()

        return Diagnostic(
            rule = Rule(
                agreementId = normalizedAgreementId,
                siret = siret,
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                classification = profile.classification,
                professionalStatus = status,
                minimumSeniorityMonths = seniorityCandidates.single(),
                basis = Basis.GROSS_SALARY,
                employeeRate = rates.employee,
                employerRate = rates.employer,
                evidenceExcerpt = evidence
            ),
            reasons = listOf(
                "ACCO prévoyance : SIRET, date, durée, profil, ancienneté, assiette brute et répartition salarié/employeur sont explicites.",
                "ACCO prévoyance : la règle reste soumise à l'arbitrage L2253-1 et ne prouve pas à elle seule l'équivalence des garanties avec la branche."
            )
        )
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

    private fun scopedWindow(text: String, offset: Int): Window {
        val start = (offset - 700).coerceAtLeast(0)
        val end = (offset + 1100).coerceAtMost(text.length)
        return Window(text.substring(start, end), offset)
    }

    /**
     * Associe chaque pourcentage au payeur explicitement placé juste avant ou juste après.
     * Les segments sont bornés par les pourcentages voisins : un libellé appartenant au taux
     * précédent ne peut donc pas contaminer le suivant.
     */
    private fun parseRates(window: Window): RatePair? {
        val matches = percentRegex.findAll(window.text).toList()
        val employeeRates = linkedSetOf<Double>()
        val employerRates = linkedSetOf<Double>()

        matches.forEachIndexed { index, match ->
            val value = match.groupValues[1]
                .replace(',', '.')
                .toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 }
                ?.div(100.0)
                ?: return@forEachIndexed

            val previousBoundary = matches.getOrNull(index - 1)?.range?.last?.plus(1)
                ?: (match.range.first - 90).coerceAtLeast(0)
            val nextBoundary = matches.getOrNull(index + 1)?.range?.first
                ?: (match.range.last + 91).coerceAtMost(window.text.length)
            val before = window.text.substring(previousBoundary, match.range.first).takeLast(90)
            val after = window.text.substring(match.range.last + 1, nextBoundary).take(90)

            val employee = employeeBeforeRateRegex.containsMatchIn(before) ||
                employeeAfterRateRegex.containsMatchIn(after)
            val employer = employerBeforeRateRegex.containsMatchIn(before) ||
                employerAfterRateRegex.containsMatchIn(after)

            when {
                employee && !employer -> employeeRates += value
                employer && !employee -> employerRates += value
                else -> Unit
            }
        }

        if (employeeRates.size != 1 || employerRates.size != 1) return null
        return RatePair(employeeRates.single(), employerRates.single())
    }

    private fun parseSeniority(window: Window): List<Int> {
        val values = mutableListOf<Int>()
        if (zeroSeniorityRegex.containsMatchIn(window.text)) values += 0
        seniorityMonthsRegex.findAll(window.text).forEach { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it in 0..600 }?.let(values::add)
        }
        return values.distinct()
    }

    private fun parseDateToken(raw: String): LocalDate? {
        val token = raw.trim().lowercase(Locale.FRANCE).replace("1er", "1")
        val numeric = Regex("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})$").matchEntire(token)
        if (numeric != null) {
            return runCatching {
                LocalDate.of(
                    numeric.groupValues[3].toInt(),
                    numeric.groupValues[2].toInt(),
                    numeric.groupValues[1].toInt()
                )
            }.getOrNull()
        }
        val textual = Regex("^(\\d{1,2})\\s+([a-z]+)\\s+(\\d{4})$").matchEntire(token) ?: return null
        val month = months[textual.groupValues[2]] ?: return null
        return runCatching {
            LocalDate.of(textual.groupValues[3].toInt(), month, textual.groupValues[1].toInt())
        }.getOrNull()
    }

    private fun unresolved(reason: String) = Diagnostic(
        rule = null,
        reasons = listOf("ACCO prévoyance : $reason ; aucune cotisation d'entreprise n'est appliquée automatiquement.")
    )

    private val accoTextIdRegex = Regex("^ACCOTEXT\\d+$")
    private val classificationVocabulary = Regex(
        "\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction|poste)s?\\b"
    )
    private val dateToken =
        "(\\d{1,2}(?:er)?[./-]\\d{1,2}[./-]\\d{4}|\\d{1,2}(?:er)?\\s+(?:janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)\\s+\\d{4})"
    private val effectiveFromRegexes = listOf(
        Regex("\\b(?:entre en vigueur|prend effet|s'applique)\\s+(?:a compter du|a compter de|le|du)?\\s*$dateToken"),
        Regex("\\bdate d'effet\\s*[:.-]?\\s*$dateToken")
    )
    private val effectiveToRegexes = listOf(
        Regex("\\b(?:cesse de produire ses effets|prend fin|expire)\\s+(?:le)?\\s*$dateToken"),
        Regex("\\bjusqu'au\\s+$dateToken")
    )
    private val indefiniteDurationRegex = Regex("\\b(?:conclu|conclue)?\\s*(?:pour)?\\s*une duree indeterminee\\b|\\bduree indeterminee\\b")
    private val contributionMarkers = listOf(
        Regex("\\bcotisations? de prevoyance\\b"),
        Regex("\\bcotisations? prevoyance\\b"),
        Regex("\\bpart salariale prevoyance\\b"),
        Regex("\\bpart patronale prevoyance\\b"),
        Regex("\\bfinancement du regime de prevoyance\\b")
    )
    private val grossBasisRegex = Regex("\\b(?:salaire|remuneration) brute?\\b|\\bassiette[^.;]{0,80}?(?:salaire|remuneration) brute?\\b")
    private val unsupportedBasisRegex = Regex("\\b(?:pmss|plafond(?: de la securite sociale)?|tranche[s]?|fraction du plafond)\\b")
    private val percentRegex = Regex("(\\d{1,3}(?:[.,]\\d{1,4})?)\\s*%")
    private val employeeBeforeRateRegex = Regex(
        "\\b(?:part salariale|a la charge du salarie)\\b\\s*(?:[:=.-]?\\s*)?(?:(?:est|fixee)\\s+(?:a|de)\\s+|(?:a|de)\\s+)?$"
    )
    private val employerBeforeRateRegex = Regex(
        "\\b(?:part patronale|a la charge de l'employeur)\\b\\s*(?:[:=.-]?\\s*)?(?:(?:est|fixee)\\s+(?:a|de)\\s+|(?:a|de)\\s+)?$"
    )
    private val employeeAfterRateRegex = Regex(
        "^\\s*(?:[:=.-]?\\s*)?(?:a la charge du salarie|part salariale)\\b"
    )
    private val employerAfterRateRegex = Regex(
        "^\\s*(?:[:=.-]?\\s*)?(?:a la charge de l'employeur|part patronale)\\b"
    )
    private val zeroSeniorityRegex = Regex(
        "\\b(?:sans condition d'anciennete|des l'embauche|a compter de l'embauche)\\b"
    )
    private val seniorityMonthsRegex = Regex(
        "\\b(?:au moins|apres|a partir de)\\s*(\\d{1,3})\\s*mois(?: d'anciennete)?\\b"
    )
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
