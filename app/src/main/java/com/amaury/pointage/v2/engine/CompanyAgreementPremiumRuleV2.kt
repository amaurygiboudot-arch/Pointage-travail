package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.OfficialKaliNightRuleParserV2
import com.amaury.pointage.v2.OfficialKaliOvertimeRuleParserV2
import com.amaury.pointage.v2.OfficialKaliWeekdayPremiumRuleParserV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Conversion prudente d'une règle ACCO déjà validée en règle de prime exploitable.
 *
 * L'extraction automatique ACCO ne suffit jamais : la règle source doit déjà être applicable,
 * vérifiée et sa valeur de calcul explicitement validée. Les parseurs texte conservateurs utilisés
 * pour KALI sont réutilisés pour éviter d'accepter une plage, une condition ou plusieurs taux ici.
 */
object CompanyAgreementPremiumRuleV2 {
    data class NightRule(
        val source: CompanyAgreementStructuredRuleV2.Rule,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val rule: NightPremiumRuleV2
    ) {
        val fingerprint: String = "NIGHT|${rule.startMinute}|${rule.endMinute}|${rule.multiplier}"
    }

    data class WeekdayRule(
        val source: CompanyAgreementStructuredRuleV2.Rule,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val rule: WeekdayPremiumRuleV2
    ) {
        val fingerprint: String = "${rule.kind.name}|${rule.multiplier}"
    }

    fun night(source: CompanyAgreementStructuredRuleV2.Rule): NightRule? {
        if (!source.calculationReady) return null
        if (source.source.category != CompanyAgreementRuleExtractorV2.Category.NIGHT) return null
        val dates = dates(source) ?: return null
        val article = asVerifiedArticle(source, dates.first, dates.second)
        val candidate = OfficialKaliNightRuleParserV2.analyzeArticle(article).candidate ?: return null
        if (!candidate.calculationReady) return null
        return NightRule(
            source = source,
            effectiveFrom = dates.first,
            effectiveTo = dates.second,
            rule = NightPremiumRuleV2(
                startMinute = candidate.window.startMinute,
                endMinute = candidate.window.endMinute,
                multiplier = candidate.multiplier
            )
        )
    }

    fun weekday(
        source: CompanyAgreementStructuredRuleV2.Rule,
        kind: WeekdayPremiumKindV2
    ): WeekdayRule? {
        if (!source.calculationReady) return null
        val expectedCategory = when (kind) {
            WeekdayPremiumKindV2.SATURDAY -> CompanyAgreementRuleExtractorV2.Category.SATURDAY
            WeekdayPremiumKindV2.SUNDAY -> CompanyAgreementRuleExtractorV2.Category.SUNDAY
        }
        if (source.source.category != expectedCategory) return null
        val dates = dates(source) ?: return null
        val article = asVerifiedArticle(source, dates.first, dates.second)
        val candidate = OfficialKaliWeekdayPremiumRuleParserV2.analyzeArticle(article, kind).candidate ?: return null
        if (!candidate.calculationReady) return null
        return WeekdayRule(
            source = source,
            effectiveFrom = dates.first,
            effectiveTo = dates.second,
            rule = WeekdayPremiumRuleV2(kind, candidate.multiplier)
        )
    }

    private fun dates(source: CompanyAgreementStructuredRuleV2.Rule): Pair<LocalDate, LocalDate?>? {
        val from = parseDate(source.source.effectiveFrom) ?: return null
        val to = source.source.effectiveTo?.takeIf { it.isNotBlank() }?.let(::parseDate)
            ?: if (source.source.effectiveTo.isNullOrBlank()) null else return null
        if (to != null && to.isBefore(from)) return null
        return from to to
    }

    private fun asVerifiedArticle(
        source: CompanyAgreementStructuredRuleV2.Rule,
        from: LocalDate,
        to: LocalDate?
    ): OfficialKaliOvertimeRuleParserV2.VerifiedArticle =
        OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = "ACCO:${source.source.agreementId}:${source.source.excerpt.hashCode().toUInt().toString(16)}",
            status = "VIGUEUR",
            content = source.source.excerpt,
            effectiveFrom = from,
            effectiveTo = to,
            title = null
        )

    private fun parseDate(value: String?): LocalDate? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            runCatching { LocalDate.parse(raw, DateTimeFormatter.ofPattern("dd/MM/uuuu")) }.getOrNull()
        }
    }
}
