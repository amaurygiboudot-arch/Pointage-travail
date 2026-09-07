package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSicknessMaintenanceV2
import java.time.LocalDate
import java.util.Locale

/**
 * Parse un maintien maladie uniquement lorsque le texte KALI expose sans ambiguïté
 * la population, l'ancienneté, la base, les bandes jours/taux, leur portée, la carence et les plafonds.
 */
object OfficialKaliSicknessMaintenanceParserV2 {
    data class Diagnostic(
        val articleId: String,
        val rule: ConventionSicknessMaintenanceV2.Rule?,
        val reasons: List<String>
    )

    private data class ParsedRule(
        val minimumSeniorityMonths: Int,
        val bands: List<ConventionSicknessMaintenanceV2.Band>,
        val bandConsumptionScope: ConventionSicknessMaintenanceV2.BandConsumptionScope,
        val referenceBasis: ConventionSicknessMaintenanceV2.ReferenceBasis,
        val waitingPolicy: ConventionSicknessMaintenanceV2.WaitingPolicy,
        val waitingDays: Int,
        val annualLimitDays: Int,
        val perStopLimitDays: Int,
        val socialSecurityCoverageRequiredAfterDays: Int?
    )

    fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2,
        auditDate: LocalDate
    ): Diagnostic {
        val status = profile.professionalStatus
            ?: return Diagnostic(article.articleId, null, listOf("statut cadre/non-cadre absent de la fiche salarié"))
        val raw = listOfNotNull(article.title, article.content).joinToString(" ")
        val normalized = OfficialKaliProfileMatcherV2.normalize(raw)
        if (!mentionsSicknessMaintenance(normalized)) {
            return Diagnostic(article.articleId, null, listOf("article sans maintien maladie explicite"))
        }

        val articleUsesClassification = classificationVocabulary.any(normalized::contains)
        val windows = if (articleUsesClassification) {
            if (profile.classification.isEmpty()) {
                return Diagnostic(article.articleId, null, listOf("article classifié mais classification salarié absente"))
            }
            OfficialKaliProfileMatcherV2.windows(
                rawText = raw,
                classification = profile.classification,
                professionalStatus = status,
                before = 500,
                after = 1800,
                maxClassificationSpan = 650
            ).map { it.text }
        } else {
            statusWindows(normalized, status)
        }

        if (windows.isEmpty()) {
            return Diagnostic(article.articleId, null, listOf("population exacte du salarié non isolée dans l'article"))
        }

        val parsed = windows.mapNotNull(::parseWindow).distinctBy(::fingerprint)
        if (parsed.size != 1) {
            return Diagnostic(
                article.articleId,
                null,
                listOf("${parsed.size} barème(s) maladie complet(s) non ambigu(s) autour du profil ; règle unique non démontrée")
            )
        }

        val candidate = parsed.single()
        val extensionStatus = extensionStatus(article)
        val classification = if (articleUsesClassification) profile.classification.normalized() else ConventionClassificationV2()
        val rule = ConventionSicknessMaintenanceV2.Rule(
            idcc = profile.idcc,
            ruleId = "KALI-SICK-${article.articleId}-${status}-${classification.label().hashCode().toUInt().toString(16)}",
            effectiveFrom = article.effectiveFrom,
            effectiveTo = article.effectiveTo,
            classification = classification,
            professionalStatus = status,
            minimumSeniorityMonths = candidate.minimumSeniorityMonths,
            tiers = listOf(
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumSeniorityMonths = candidate.minimumSeniorityMonths,
                    bands = candidate.bands,
                    annualLimitDays = candidate.annualLimitDays,
                    perStopLimitDays = candidate.perStopLimitDays,
                    bandConsumptionScope = candidate.bandConsumptionScope
                )
            ),
            referenceBasis = candidate.referenceBasis,
            waitingPolicy = candidate.waitingPolicy,
            waitingDays = candidate.waitingDays,
            socialSecurityCoverageRequiredAfterDays = candidate.socialSecurityCoverageRequiredAfterDays,
            source = "Légifrance KALI — ${article.articleId}${article.title?.let { " — $it" }.orEmpty()}",
            extensionStatus = extensionStatus,
            extensionEffectiveFrom = if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) article.extensionEffectiveFrom else null
        )

        return if (rule.structurallyValid()) {
            Diagnostic(
                article.articleId,
                rule,
                buildList {
                    if (article.status.uppercase(Locale.ROOT) == "VIGUEUR_ETEN" && article.extensionEffectiveFrom == null) {
                        add("statut étendu présent mais date exacte d'extension absente ; applicabilité automatique bloquée")
                    }
                    if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED) {
                        add("texte non étendu : applicabilité à l'entreprise non démontrée")
                    }
                }
            )
        } else {
            Diagnostic(article.articleId, null, listOf("barème maladie structuré incohérent"))
        }
    }

    private fun parseWindow(window: String): ParsedRule? {
        val seniority = parseSingleSeniority(window) ?: return null
        val bands = parseBands(window) ?: return null
        val scope = parseBandConsumptionScope(window) ?: return null
        val basis = parseReferenceBasis(window) ?: return null
        val waiting = parseWaiting(window) ?: return null
        val annualLimit = parseUniqueLimit(window, annualLimitRegexes) ?: return null
        val perStopLimit = parseUniqueLimit(window, perStopLimitRegexes) ?: return null
        if (annualLimit !in 1..366 || perStopLimit !in 1..366) return null
        if (bands.sumOf { it.calendarDays } > perStopLimit) return null
        if (scope == ConventionSicknessMaintenanceV2.BandConsumptionScope.ANNUAL_CUMULATIVE && annualLimit > bands.sumOf { it.calendarDays }) return null
        val ssAfter = parseOptionalSsThreshold(window)
        return ParsedRule(
            minimumSeniorityMonths = seniority,
            bands = bands,
            bandConsumptionScope = scope,
            referenceBasis = basis,
            waitingPolicy = waiting.first,
            waitingDays = waiting.second,
            annualLimitDays = annualLimit,
            perStopLimitDays = perStopLimit,
            socialSecurityCoverageRequiredAfterDays = ssAfter
        )
    }

    private fun parseSingleSeniority(text: String): Int? {
        val values = buildList {
            seniorityRegex.findAll(text).forEach { match ->
                val amount = match.groupValues[1].toIntOrNull() ?: return@forEach
                val unit = match.groupValues[2]
                val months = if (unit.startsWith("mois")) amount else amount * 12
                if (months in 0..600) add(months)
            }
        }.distinct()
        return values.singleOrNull()
    }

    private fun parseBands(text: String): List<ConventionSicknessMaintenanceV2.Band>? {
        val clauses = text.split(clauseSeparator).map(String::trim).filter(String::isNotBlank)
        val bands = clauses.flatMap { clause ->
            val forward = dayRateRegex.findAll(clause).mapNotNull { match -> band(match.groupValues[1], match.groupValues[2]) }.toList()
            val reverse = rateDayRegex.findAll(clause).mapNotNull { match -> band(match.groupValues[2], match.groupValues[1]) }.toList()
            forward + reverse
        }.distinctBy { it.calendarDays to it.targetRate }
            .filter { it.calendarDays in 1..366 && it.targetRate in 0.0..1.0 }
        if (bands.isEmpty() || bands.size > 6) return null
        return bands
    }

    private fun band(daysRaw: String, rateRaw: String): ConventionSicknessMaintenanceV2.Band? {
        val days = daysRaw.toIntOrNull() ?: return null
        val percent = rateRaw.replace(',', '.').toDoubleOrNull() ?: return null
        if (days !in 1..366 || percent !in 0.0..100.0) return null
        return ConventionSicknessMaintenanceV2.Band(
            calendarDays = days,
            targetRate = percent / 100.0,
            label = "Maintien conventionnel à ${formatPercent(percent)} % de la base de référence"
        )
    }

    private fun parseBandConsumptionScope(text: String): ConventionSicknessMaintenanceV2.BandConsumptionScope? {
        val perStop = perStopBandScopeRegexes.any { it.containsMatchIn(text) }
        val annual = annualBandScopeRegexes.any { it.containsMatchIn(text) }
        return when {
            perStop && !annual -> ConventionSicknessMaintenanceV2.BandConsumptionScope.PER_STOP
            annual && !perStop -> ConventionSicknessMaintenanceV2.BandConsumptionScope.ANNUAL_CUMULATIVE
            else -> null
        }
    }

    private fun parseReferenceBasis(text: String): ConventionSicknessMaintenanceV2.ReferenceBasis? {
        val gross = grossBasisWords.any(text::contains)
        val net = netBasisWords.any(text::contains)
        return when {
            gross && !net -> ConventionSicknessMaintenanceV2.ReferenceBasis.GROSS
            net && !gross -> ConventionSicknessMaintenanceV2.ReferenceBasis.NET
            else -> null
        }
    }

    private fun parseWaiting(text: String): Pair<ConventionSicknessMaintenanceV2.WaitingPolicy, Int>? {
        val noWaiting = noWaitingWords.any(text::contains)
        val fixedValues = fixedWaitingRegexes.flatMap { regex ->
            regex.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
        }.filter { it in 1..30 }.distinct()
        return when {
            noWaiting && fixedValues.isEmpty() -> ConventionSicknessMaintenanceV2.WaitingPolicy.NONE to 0
            !noWaiting && fixedValues.size == 1 -> ConventionSicknessMaintenanceV2.WaitingPolicy.FIXED_EACH_STOP to fixedValues.single()
            else -> null
        }
    }

    private fun parseUniqueLimit(text: String, regexes: List<Regex>): Int? {
        val values = regexes.flatMap { regex ->
            regex.findAll(text).mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
        }.filter { it in 1..366 }.distinct()
        return values.singleOrNull()
    }

    private fun parseOptionalSsThreshold(text: String): Int? {
        val values = ssThresholdRegex.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0..30 }
            .distinct()
            .toList()
        return values.singleOrNull()
    }

    private fun statusWindows(text: String, status: String): List<String> {
        val textWithoutNonCadre = stripNonCadre(text)
        val wantedPositions = when (status) {
            "CADRE" -> cadreRegexes.flatMap { regex -> regex.findAll(textWithoutNonCadre).map { it.range.first }.toList() }.distinct()
            "NON_CADRE" -> nonCadreRegexes.flatMap { regex -> regex.findAll(text).map { it.range.first }.toList() }.distinct()
            else -> emptyList()
        }
        if (wantedPositions.isEmpty()) return emptyList()
        val hasOpposite = when (status) {
            "CADRE" -> nonCadreRegexes.any { it.containsMatchIn(text) }
            "NON_CADRE" -> cadreRegexes.any { it.containsMatchIn(textWithoutNonCadre) }
            else -> true
        }
        if (!hasOpposite) return listOf(text)
        return wantedPositions.map { position ->
            val start = (position - 450).coerceAtLeast(0)
            val end = (position + 2200).coerceAtMost(text.length)
            text.substring(start, end)
        }.distinct()
    }

    private fun stripNonCadre(value: String): String {
        var out = value
        nonCadreRegexes.forEach { regex -> out = regex.replace(out) { match -> " ".repeat(match.value.length) } }
        return out
    }

    private fun extensionStatus(article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle): ConventionMinimumSalaryV2.ExtensionStatus =
        when (article.status.uppercase(Locale.ROOT)) {
            "VIGUEUR_ETEN" -> if (article.extensionEffectiveFrom != null) ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED else ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
            "VIGUEUR_NON_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
            else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
        }

    private fun mentionsSicknessMaintenance(text: String): Boolean = sicknessWords.any(text::contains) && maintenanceWords.any(text::contains)

    private fun fingerprint(value: ParsedRule): String = buildString {
        append(value.minimumSeniorityMonths).append('|')
        append(value.referenceBasis.name).append('|')
        append(value.bandConsumptionScope.name).append('|')
        append(value.waitingPolicy.name).append(':').append(value.waitingDays).append('|')
        append(value.annualLimitDays).append(':').append(value.perStopLimitDays).append('|')
        value.bands.forEach { append(it.calendarDays).append(':').append(it.targetRate).append(';') }
        append('|').append(value.socialSecurityCoverageRequiredAfterDays ?: -1)
    }

    private fun formatPercent(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private val sicknessWords = listOf("maladie", "accident", "arret de travail", "incapacite temporaire")
    private val maintenanceWords = listOf("maintien", "indemnisation", "indemnise", "indemnisee", "indemnite complementaire")
    private val classificationVocabulary = listOf("coefficient", "niveau", "echelon", "position", "groupe", "categorie", "emploi")
    private val grossBasisWords = listOf("salaire brut", "remuneration brute", "traitement brut", "appointements bruts")
    private val netBasisWords = listOf("salaire net", "remuneration nette", "traitement net", "appointements nets")
    private val noWaitingWords = listOf("sans delai de carence", "sans carence", "des le premier jour")
    private val clauseSeparator = Regex("[.;]")

    private val seniorityRegex = Regex("(?:au moins|minimum de|apres|a partir de|ayant au moins)\\s*(\\d{1,2})\\s*(mois|ans?|annees?)\\s*d[' ]?anciennete")
    private val dayRateRegex = Regex("\\b(\\d{1,3})\\s*(?:premiers?\\s*)?jours?\\b[^.;]{0,90}?(\\d{1,3}(?:[,.]\\d+)?)\\s*%")
    private val rateDayRegex = Regex("(\\d{1,3}(?:[,.]\\d+)?)\\s*%[^.;]{0,90}?\\b(\\d{1,3})\\s*jours?\\b")

    private val perStopBandScopeRegexes = listOf(
        Regex("(?:pour|au titre de)\\s+(?:chaque|un meme)\\s+arret[^.;]{0,120}?\\b\\d{1,3}\\s*(?:premiers?\\s*)?jours?\\b[^.;]{0,90}?\\d{1,3}(?:[,.]\\d+)?\\s*%"),
        Regex("\\b\\d{1,3}\\s*(?:premiers?\\s*)?jours?\\b[^.;]{0,90}?\\d{1,3}(?:[,.]\\d+)?\\s*%[^.;]{0,120}?(?:pour|par)\\s+(?:chaque|un meme)\\s+arret")
    )
    private val annualBandScopeRegexes = listOf(
        Regex("(?:sur l[' ]ensemble des arrets|cumul(?:e|es)? des arrets|au cours d[' ]une meme annee(?: civile)?)[^.;]{0,160}?\\b\\d{1,3}\\s*(?:premiers?\\s*)?jours?\\b[^.;]{0,90}?\\d{1,3}(?:[,.]\\d+)?\\s*%"),
        Regex("\\b\\d{1,3}\\s*(?:premiers?\\s*)?jours?\\b[^.;]{0,90}?\\d{1,3}(?:[,.]\\d+)?\\s*%[^.;]{0,160}?(?:sur l[' ]ensemble des arrets|cumul(?:e|es)? des arrets|au cours d[' ]une meme annee(?: civile)?)")
    )

    private val fixedWaitingRegexes = listOf(
        Regex("(?:a chaque|pour chaque|pour tout)\\s+arret[^.;]{0,100}?(?:carence|delai de carence)\\s+(?:de\\s+)?(\\d{1,2})\\s*jours?"),
        Regex("(?:carence|delai de carence)\\s+(?:de\\s+)?(\\d{1,2})\\s*jours?[^.;]{0,100}?(?:a chaque|pour chaque|pour tout)\\s+arret")
    )
    private val annualLimitRegexes = listOf(
        Regex("(?:au cours d[' ]une meme annee(?: civile)?|sur une meme annee(?: civile)?|par an|annuellement)[^.;]{0,120}?(?:total|limite|plafond|maximum|ne peut exceder)[^.;]{0,80}?(\\d{1,3})\\s*jours?"),
        Regex("(?:total|limite|plafond|maximum)[^.;]{0,80}?(\\d{1,3})\\s*jours?[^.;]{0,120}?(?:au cours d[' ]une meme annee(?: civile)?|sur une meme annee(?: civile)?|par an|annuellement)")
    )
    private val perStopLimitRegexes = listOf(
        Regex("(?:pour un meme arret|pour chaque arret|par arret)[^.;]{0,120}?(?:total|limite|plafond|maximum|ne peut exceder)[^.;]{0,80}?(\\d{1,3})\\s*jours?"),
        Regex("(?:total|limite|plafond|maximum)[^.;]{0,80}?(\\d{1,3})\\s*jours?[^.;]{0,120}?(?:pour un meme arret|pour chaque arret|par arret)")
    )
    private val ssThresholdRegex = Regex("(?:securite sociale|assurance maladie)[^.;]{0,120}?(?:au[- ]dela de|apres|a partir de)\\s*(\\d{1,2})\\s*jours?")

    private val cadreRegexes = listOf(Regex("\\bcadres?\\b"), Regex("\\bingenieurs?\\b"))
    private val nonCadreRegexes = listOf(
        Regex("\\bnon[- ]cadres?\\b"), Regex("\\bouvriers?\\b"), Regex("\\bemployes?\\b"),
        Regex("\\btechniciens?\\b"), Regex("\\bagents? de maitrise\\b"), Regex("\\betam\\b")
    )
}
