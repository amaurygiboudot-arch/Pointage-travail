package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.net.URI
import java.time.LocalDate
import java.util.Locale

/**
 * Parse une décision publiée par la Commission paritaire rattachée à l'Apec pour le profil exact.
 *
 * Cette couche ne rattache jamais seule la décision à un KALITEXT : elle produit une preuve APEC
 * non liée au périmètre KALI. Le rapprochement inter-sources reste une étape séparée et bloquante.
 */
object OfficialApecProtectionCategoryParserV2 {
    data class Document(
        val documentId: String,
        val sourceUrl: String,
        val content: String
    )

    data class Evidence(
        val documentId: String,
        val idcc: String,
        val decisionDate: LocalDate,
        /** Uniquement une DATE D'EFFET explicitement publiée, jamais la date de délibération. */
        val applicableFrom: LocalDate?,
        /** Une DATE D'EFFET SOUHAITEE reste informative et ne vaut pas applicabilité. */
        val requestedEffectiveFrom: LocalDate?,
        val classification: ConventionClassificationV2,
        val professionalStatus: String,
        val aniCategory: ProtectionCategoryV2.AniCategory,
        val agreementReferences: List<String>,
        val source: String
    )

    data class Diagnostic(
        val evidence: Evidence?,
        val reasons: List<String>
    )

    fun parse(document: Document, profile: ConventionLegalProfileV2): Diagnostic {
        if (!isOfficialApecUrl(document.sourceUrl)) {
            return unresolved("source APEC officielle non démontrée")
        }
        if (document.documentId.isBlank() || document.content.isBlank()) {
            return unresolved("document APEC vide ou sans identifiant")
        }
        if (profile.classification.isEmpty()) {
            return unresolved("classification conventionnelle locale absente")
        }
        val status = profile.professionalStatus
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return unresolved("statut cadre/non-cadre local absent ou invalide")

        val text = OfficialKaliProfileMatcherV2.normalize(document.content)
        val documentIdccs = idccRegex.findAll(text)
            .mapNotNull { ConventionMinimumSalaryV2.normalizeIdcc(it.groupValues[1]).takeIf(String::isNotBlank) }
            .toSet()
        val wantedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        if (wantedIdcc.isBlank() || documentIdccs.size != 1 || wantedIdcc !in documentIdccs) {
            return unresolved("IDCC APEC absent, multiple ou différent du profil")
        }

        val decisionDates = agreementDecisionDateRegex.findAll(text)
            .mapNotNull { parseDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .distinct()
            .toList()
        if (decisionDates.size != 1) {
            return unresolved("date de délibération/agrément APEC non unique ou non prouvée")
        }

        val categories = buildList {
            categoryClauses(text, article21ValidationRegex).forEach { clause ->
                if (classificationEvidence(clause, profile.classification) == true) {
                    add(ProtectionCategoryV2.AniCategory.ARTICLE_2_1)
                }
            }
            categoryClauses(text, article22ValidationRegex).forEach { clause ->
                if (classificationEvidence(clause, profile.classification) == true) {
                    add(ProtectionCategoryV2.AniCategory.ARTICLE_2_2)
                }
            }
            categoryClauses(text, extensionValidationRegex).forEach { clause ->
                if (classificationEvidence(clause, profile.classification) == true) {
                    add(ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE)
                }
            }
        }.distinct()

        if (categories.isEmpty()) {
            return unresolved("aucune validation APEC ne couvre explicitement la classification exacte du salarié")
        }
        if (categories.size != 1) {
            return unresolved("plusieurs catégories APEC contradictoires couvrent la classification du salarié")
        }
        val category = categories.single()
        if (!statusCompatible(category, status)) {
            return unresolved("statut professionnel incompatible avec la catégorie APEC trouvée")
        }

        val explicitEffectDates = effectDateRegex.findAll(text)
            .mapNotNull { parseDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .distinct().toList()
        val requestedDates = requestedEffectDateRegex.findAll(text)
            .mapNotNull { parseDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .distinct().toList()
        val applicableFrom = explicitEffectDates.singleOrNull()
        val requestedFrom = requestedDates.singleOrNull()
        val references = agreementReferenceRegex.findAll(text)
            .map { it.value.trim() }
            .filter { it.length >= 12 }
            .distinct()
            .take(12)
            .toList()

        return Diagnostic(
            evidence = Evidence(
                documentId = document.documentId.trim(),
                idcc = wantedIdcc,
                decisionDate = decisionDates.single(),
                applicableFrom = applicableFrom,
                requestedEffectiveFrom = requestedFrom,
                classification = profile.classification.normalized(),
                professionalStatus = status,
                aniCategory = category,
                agreementReferences = references,
                source = document.sourceUrl
            ),
            reasons = buildList {
                if (explicitEffectDates.size > 1) add("plusieurs DATE D'EFFET explicites : applicabilité automatique bloquée")
                if (applicableFrom == null) add("DATE D'EFFET APEC exacte non prouvée ; la date de délibération n'est pas utilisée comme substitut")
                if (requestedFrom != null) add("DATE D'EFFET SOUHAITEE détectée : conservée comme information seulement")
                if (references.isEmpty()) add("référence exacte de l'accord/avenant non extraite ; rapprochement KALI/APEC bloqué")
            }
        )
    }

    private fun categoryClauses(text: String, regex: Regex): List<String> = regex.findAll(text)
        .map { it.value }
        .toList()

    private fun classificationEvidence(clause: String, classification: ConventionClassificationV2): Boolean? {
        classification.coefficient?.let { coefficient ->
            val ranges = coefficientRangeRegex.findAll(clause).mapNotNull { match ->
                val from = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val to = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                minOf(from, to)..maxOf(from, to)
            }.toList()
            if (ranges.any { coefficient in it }) return true
            val exact = coefficientExactRegex.findAll(clause).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
            if (coefficient in exact) return true
            val listed = coefficientListRegex.findAll(clause)
                .flatMap { numberRegex.findAll(it.groupValues[1]).mapNotNull { n -> n.value.toIntOrNull() } }
                .toSet()
            if (coefficient in listed) return true
            if (coefficientVocabulary.containsMatchIn(clause)) return null
        }

        val level = classification.level?.let(::token)
        val echelon = classification.echelon?.let(::token)
        val mentionsLevel = levelVocabulary.containsMatchIn(clause)
        val mentionsEchelon = echelonVocabulary.containsMatchIn(clause)
        if (mentionsLevel || mentionsEchelon) {
            if (mentionsLevel && level == null) return null
            if (mentionsEchelon && echelon == null) return null
            if (mentionsLevel && mentionsEchelon) {
                val wantedLevel = level ?: return null
                val wantedEchelon = echelon ?: return null
                return levelEchelonPairRegex.findAll(clause).any { match ->
                    levelEquivalent(match.groupValues[1], wantedLevel) &&
                        match.groupValues[2]
                            .split(Regex("\\s*(?:,|/|et|ou)\\s*"))
                            .map(::token)
                            .any { it == wantedEchelon }
                }
            }
            if (mentionsLevel) {
                val wanted = level ?: return null
                if (levelExactRegex.findAll(clause).any { levelEquivalent(it.groupValues[1], wanted) }) return true
                return null
            }
            if (mentionsEchelon) {
                val wanted = echelon ?: return null
                if (echelonExactRegex.findAll(clause).any { token(it.groupValues[1]) == wanted }) return true
                if (echelonListRegex.findAll(clause).any { match ->
                        match.groupValues[1].split(Regex("\\s*(?:,|/|et|ou)\\s*")).map(::token).any { it == wanted }
                    }) return true
                return null
            }
        }

        classification.position?.let { if (labelValueMatch(clause, "position", it)) return true }
        classification.group?.let { if (labelValueMatch(clause, "groupe", it)) return true }
        classification.category?.let { if (labelValueMatch(clause, "categorie", it)) return true }
        classification.employment?.let {
            val wanted = OfficialKaliProfileMatcherV2.normalize(it)
            if (Regex("\\b(?:emploi|fonction|poste)\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b").containsMatchIn(clause)) return true
        }
        return null
    }

    private fun labelValueMatch(clause: String, label: String, raw: String): Boolean {
        val wanted = OfficialKaliProfileMatcherV2.normalize(raw)
        return Regex("\\b${label}s?\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b").containsMatchIn(clause) ||
            Regex("\\b${label}s?\\s*[:.\\-]?\\s*([a-z0-9ivx]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9ivx]+)+)")
                .findAll(clause)
                .any { match -> match.groupValues[1].split(Regex("\\s*(?:,|/|et|ou)\\s*")).map(::token).any { it == token(raw) } }
    }

    private fun statusCompatible(category: ProtectionCategoryV2.AniCategory, status: String): Boolean = when (category) {
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1 -> status == "CADRE"
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE -> status == "NON_CADRE"
        else -> false
    }

    private fun isOfficialApecUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("commission-paritaire.apec.fr", ignoreCase = true)
    }.getOrDefault(false)

    private fun parseDate(day: String, month: String, year: String): LocalDate? {
        val d = day.toIntOrNull() ?: return null
        val y = year.toIntOrNull() ?: return null
        val m = month.toIntOrNull() ?: monthNumber(month) ?: return null
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    private fun monthNumber(raw: String): Int? = when (token(raw)) {
        "JANVIER" -> 1
        "FEVRIER" -> 2
        "MARS" -> 3
        "AVRIL" -> 4
        "MAI" -> 5
        "JUIN" -> 6
        "JUILLET" -> 7
        "AOUT" -> 8
        "SEPTEMBRE" -> 9
        "OCTOBRE" -> 10
        "NOVEMBRE" -> 11
        "DECEMBRE" -> 12
        else -> null
    }

    private fun token(raw: String): String = OfficialKaliProfileMatcherV2.normalize(raw).uppercase(Locale.FRANCE)

    private fun levelEquivalent(left: String, right: String): Boolean {
        val a = romanOrArabic(left)
        val b = romanOrArabic(right)
        return if (a != null && b != null) a == b else token(left) == token(right)
    }

    private fun romanOrArabic(raw: String): Int? {
        token(raw).toIntOrNull()?.let { return it }
        return when (token(raw)) {
            "I" -> 1; "II" -> 2; "III" -> 3; "IV" -> 4; "V" -> 5; "VI" -> 6
            "VII" -> 7; "VIII" -> 8; "IX" -> 9; "X" -> 10; "XI" -> 11; "XII" -> 12
            else -> null
        }
    }

    private fun unresolved(reason: String) = Diagnostic(null, listOf("APEC catégorie ANI : $reason ; aucune catégorie n'est validée."))

    private const val DATE_PART = "(\\d{1,2})[./\\- ]+(\\d{1,2}|janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)[./\\- ]+(\\d{4})"
    private val idccRegex = Regex("\\bidcc\\s*[:.\\-]?\\s*0*(\\d{1,4})\\b")
    private val agreementDecisionDateRegex = Regex("\\b(?:agrement|deliberation)\\s+du\\s+$DATE_PART")
    private val requestedEffectDateRegex = Regex("\\bdate\\s+d[' ]effet\\s+souhaitee?\\s*[:.\\-]?\\s*$DATE_PART")
    private val effectDateRegex = Regex("\\bdate\\s+d[' ]effet(?!\\s+souhaitee?)\\s*[:.\\-]?\\s*$DATE_PART")
    private val agreementReferenceRegex = Regex("\\b(?:accord|avenant|convention)[^.;]{0,220}?(?:du\\s+\\d{1,2}\\s+(?:janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)\\s+\\d{4}|du\\s+\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})")

    private val article21ValidationRegex = Regex("\\b(?:la\\s+commission[^.]{0,120})?valide[^.]{0,320}?article\\s*2[.,]1\\b")
    private val article22ValidationRegex = Regex("\\b(?:la\\s+commission[^.]{0,120})?valide[^.]{0,320}?article\\s*2[.,]2\\b")
    private val extensionValidationRegex = Regex("\\b(?:la\\s+commission[^.]{0,120})?valide[^.]{0,360}?(?:peuvent?\\s+etre\\s+integres?|integration|categorie\\s+des\\s+cadres)[^.]{0,180}?(?:decret\\s*2021-1002|r\\.?\\s*242-1-1)\\b")

    private val numberRegex = Regex("\\d{1,4}")
    private val coefficientVocabulary = Regex("\\bcoefficients?\\b")
    private val coefficientRangeRegex = Regex("\\bcoefficients?\\s*[:.\\-]?\\s*(\\d{1,4})\\s*(?:a|au|-)\\s*(?:coefficient\\s*)?(\\d{1,4})\\b")
    private val coefficientExactRegex = Regex("\\bcoefficients?\\s*[:.\\-]?\\s*(\\d{1,4})\\b")
    private val coefficientListRegex = Regex("\\bcoefficients?\\s*[:.\\-]?\\s*((?:\\d{1,4})(?:\\s*(?:,|/|et|ou)\\s*(?:coefficients?\\s*)?\\d{1,4})+)\\b")
    private val levelVocabulary = Regex("\\bniveaux?\\b")
    private val echelonVocabulary = Regex("\\bechelons?\\b")
    private val levelExactRegex = Regex("\\bniveaux?\\s*[:.\\-]?\\s*([a-z0-9ivx]+)\\b")
    private val echelonExactRegex = Regex("\\bechelons?\\s*[:.\\-]?\\s*([a-z0-9]+)\\b")
    private val echelonListRegex = Regex("\\bechelons?\\s*[:.\\-]?\\s*([a-z0-9]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9]+)+)")
    private val levelEchelonPairRegex = Regex("\\bniveaux?\\s*[:.\\-]?\\s*([a-z0-9ivx]+)\\s*(?:[-,/]\\s*)?echelons?\\s*[:.\\-]?\\s*([a-z0-9]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9]+)*)\\b")
}
