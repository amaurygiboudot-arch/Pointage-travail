package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.V2ConventionNightRuleStore
import com.amaury.pointage.v2.V2ConventionPublicHolidayPremiumStore
import com.amaury.pointage.v2.V2ConventionWeekdayPremiumStore
import java.time.LocalDate

/**
 * Arbitrage des primes collectives nuit/samedi/dimanche/jours fériés avant calcul.
 *
 * Les règles KALI peuvent être parfaitement vérifiées sans prouver pour autant qu'aucun accord
 * d'entreprise plus prioritaire n'existe. Par conséquent, une règle KALI seule reste en revue tant
 * que l'absence ACCO applicable n'est pas explicitement établie. Les candidats ACCO, eux, doivent
 * déjà avoir été validés (période, périmètre et valeur) par la chaîne existante.
 *
 * Le régime légal propre au 1er mai n'est pas représenté par publicHoliday : il doit être arbitré
 * séparément via LEGI avant toute valorisation du 1er mai travaillé.
 */
object CollectivePremiumLegalArbitrationBridgeV2 {
    data class Selection<T>(
        val resolution: PayrollLegalArbitratorV2.Resolution,
        val selectedRule: T?,
        val selectedSourceId: String?,
        val warnings: List<String>
    ) {
        val resolved: Boolean
            get() = resolution.state == PayrollLegalArbitratorV2.State.RESOLVED && selectedRule != null
    }

    data class Snapshot(
        val referenceDate: LocalDate,
        val night: Selection<NightPremiumRuleV2>,
        val saturday: Selection<WeekdayPremiumRuleV2>,
        val sunday: Selection<WeekdayPremiumRuleV2>,
        val publicHoliday: Selection<PublicHolidayPremiumRuleV2>
    ) {
        val warnings: List<String>
            get() = (night.warnings + saturday.warnings + sunday.warnings + publicHoliday.warnings).distinct()
    }

    fun load(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        period: PayrollPeriodV2.Period? = null
    ): Snapshot {
        val company = CompanyAgreementPayrollBridgeV2.load(context, companyId, referenceDate, period)
        val epochDay = referenceDate.toEpochDay()
        val branchNight = runCatching {
            V2ConventionNightRuleStore.history(context).applicable(idcc, epochDay)
        }.getOrNull()
        val weekdayHistory = runCatching { V2ConventionWeekdayPremiumStore.history(context) }.getOrNull()
        val branchSaturday = weekdayHistory?.applicable(idcc, WeekdayPremiumKindV2.SATURDAY, epochDay)
        val branchSunday = weekdayHistory?.applicable(idcc, WeekdayPremiumKindV2.SUNDAY, epochDay)
        val branchPublicHoliday = runCatching {
            V2ConventionPublicHolidayPremiumStore.history(context).applicable(idcc, epochDay)
        }.getOrNull()

        return Snapshot(
            referenceDate = referenceDate,
            night = resolveNight(company, branchNight, referenceDate),
            saturday = resolveWeekday(
                company,
                branchSaturday,
                WeekdayPremiumKindV2.SATURDAY,
                referenceDate
            ),
            sunday = resolveWeekday(
                company,
                branchSunday,
                WeekdayPremiumKindV2.SUNDAY,
                referenceDate
            ),
            publicHoliday = resolvePublicHoliday(company, branchPublicHoliday, referenceDate)
        )
    }

    internal fun resolveNight(
        company: CompanyAgreementPayrollBridgeV2.Snapshot,
        branch: ConventionNightRuleSnapshotV2?,
        referenceDate: LocalDate,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Selection<NightPremiumRuleV2> {
        val accoRules = company.calculationReadyRules.mapNotNull(CompanyAgreementPremiumRuleV2::night)
        val ruleByCandidateId = linkedMapOf<String, NightPremiumRuleV2>()
        val sourceByCandidateId = linkedMapOf<String, String>()
        val candidates = mutableListOf<PayrollLegalArbitratorV2.Candidate>()

        accoRules.forEach { acco ->
            val id = accoCandidateId(acco.source)
            candidates += PayrollLegalArbitratorV2.Candidate(
                id = id,
                source = PayrollLegalArbitratorV2.Source.ACCO,
                effectiveFrom = acco.effectiveFrom,
                effectiveTo = acco.effectiveTo,
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = acco.fingerprint
            )
            ruleByCandidateId[id] = acco.rule
            sourceByCandidateId[id] = "legifrance:ACCO:${acco.source.source.agreementId}"
        }

        branch?.let { kali ->
            val id = "KALI:NIGHT:${kali.versionId}"
            candidates += PayrollLegalArbitratorV2.Candidate(
                id = id,
                source = PayrollLegalArbitratorV2.Source.KALI,
                effectiveFrom = LocalDate.ofEpochDay(kali.effectiveFromEpochDay),
                effectiveTo = kali.effectiveToEpochDay?.let(LocalDate::ofEpochDay),
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = "NIGHT|${kali.rule.startMinute}|${kali.rule.endMinute}|${kali.rule.multiplier}"
            )
            ruleByCandidateId[id] = kali.rule
            sourceByCandidateId[id] = kali.sourceId
        }

        val raw = PayrollLegalArbitratorV2.resolve(
            candidates = candidates,
            referenceDate = referenceDate,
            policy = PayrollLegalArbitratorV2.Policy.ENTERPRISE_PREVAILS_L2253_3,
            sourceKnowledge = sourceKnowledge
        )
        val resolution = raw.copy(
            explanation = when (raw.state) {
                PayrollLegalArbitratorV2.State.RESOLVED ->
                    "Travail de nuit (L3122-15) : accord d'entreprise/établissement examiné avant la branche."
                else -> raw.explanation
            }
        )
        val selectedId = resolution.selected?.id
        val selectedRule = selectedId?.let(ruleByCandidateId::get)
        return Selection(
            resolution = resolution,
            selectedRule = selectedRule,
            selectedSourceId = selectedId?.let(sourceByCandidateId::get),
            warnings = selectionWarnings("nuit", resolution, branch != null, accoRules.isNotEmpty())
        )
    }

    internal fun resolveWeekday(
        company: CompanyAgreementPayrollBridgeV2.Snapshot,
        branch: ConventionWeekdayPremiumSnapshotV2?,
        kind: WeekdayPremiumKindV2,
        referenceDate: LocalDate,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Selection<WeekdayPremiumRuleV2> {
        val accoRules = company.calculationReadyRules.mapNotNull { rule ->
            CompanyAgreementPremiumRuleV2.weekday(rule, kind)
        }
        val ruleByCandidateId = linkedMapOf<String, WeekdayPremiumRuleV2>()
        val sourceByCandidateId = linkedMapOf<String, String>()
        val candidates = mutableListOf<PayrollLegalArbitratorV2.Candidate>()

        accoRules.forEach { acco ->
            val id = accoCandidateId(acco.source)
            candidates += PayrollLegalArbitratorV2.Candidate(
                id = id,
                source = PayrollLegalArbitratorV2.Source.ACCO,
                effectiveFrom = acco.effectiveFrom,
                effectiveTo = acco.effectiveTo,
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = acco.fingerprint
            )
            ruleByCandidateId[id] = acco.rule
            sourceByCandidateId[id] = "legifrance:ACCO:${acco.source.source.agreementId}"
        }

        branch?.let { kali ->
            val id = "KALI:${kind.name}:${kali.versionId}"
            candidates += PayrollLegalArbitratorV2.Candidate(
                id = id,
                source = PayrollLegalArbitratorV2.Source.KALI,
                effectiveFrom = LocalDate.ofEpochDay(kali.effectiveFromEpochDay),
                effectiveTo = kali.effectiveToEpochDay?.let(LocalDate::ofEpochDay),
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = "${kind.name}|${kali.rule.multiplier}"
            )
            ruleByCandidateId[id] = kali.rule
            sourceByCandidateId[id] = kali.sourceId
        }

        val resolution = PayrollLegalArbitratorV2.resolve(
            candidates = candidates,
            referenceDate = referenceDate,
            policy = PayrollLegalArbitratorV2.Policy.ENTERPRISE_PREVAILS_L2253_3,
            sourceKnowledge = sourceKnowledge
        )
        val selectedId = resolution.selected?.id
        val selectedRule = selectedId?.let(ruleByCandidateId::get)
        val label = when (kind) {
            WeekdayPremiumKindV2.SATURDAY -> "samedi"
            WeekdayPremiumKindV2.SUNDAY -> "dimanche"
        }
        return Selection(
            resolution = resolution,
            selectedRule = selectedRule,
            selectedSourceId = selectedId?.let(sourceByCandidateId::get),
            warnings = selectionWarnings(label, resolution, branch != null, accoRules.isNotEmpty())
        )
    }

    internal fun resolvePublicHoliday(
        company: CompanyAgreementPayrollBridgeV2.Snapshot,
        branch: ConventionPublicHolidayPremiumSnapshotV2?,
        referenceDate: LocalDate,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Selection<PublicHolidayPremiumRuleV2> {
        val accoRules = company.calculationReadyRules.mapNotNull(CompanyAgreementPremiumRuleV2::publicHoliday)
        val ruleByCandidateId = linkedMapOf<String, PublicHolidayPremiumRuleV2>()
        val sourceByCandidateId = linkedMapOf<String, String>()
        val candidates = mutableListOf<PayrollLegalArbitratorV2.Candidate>()

        accoRules.forEach { acco ->
            val id = accoCandidateId(acco.source)
            candidates += PayrollLegalArbitratorV2.Candidate(
                id = id,
                source = PayrollLegalArbitratorV2.Source.ACCO,
                effectiveFrom = acco.effectiveFrom,
                effectiveTo = acco.effectiveTo,
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = acco.fingerprint
            )
            ruleByCandidateId[id] = acco.rule
            sourceByCandidateId[id] = "legifrance:ACCO:${acco.source.source.agreementId}"
        }

        branch?.let { kali ->
            val id = "KALI:PUBLIC_HOLIDAY:${kali.versionId}"
            candidates += PayrollLegalArbitratorV2.Candidate(
                id = id,
                source = PayrollLegalArbitratorV2.Source.KALI,
                effectiveFrom = LocalDate.ofEpochDay(kali.effectiveFromEpochDay),
                effectiveTo = kali.effectiveToEpochDay?.let(LocalDate::ofEpochDay),
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = "PUBLIC_HOLIDAY|${kali.rule.multiplier}"
            )
            ruleByCandidateId[id] = kali.rule
            sourceByCandidateId[id] = kali.sourceId
        }

        val resolution = PayrollLegalArbitratorV2.resolve(
            candidates = candidates,
            referenceDate = referenceDate,
            policy = PayrollLegalArbitratorV2.Policy.ENTERPRISE_PREVAILS_L2253_3,
            sourceKnowledge = sourceKnowledge
        )
        val selectedId = resolution.selected?.id
        val selectedRule = selectedId?.let(ruleByCandidateId::get)
        return Selection(
            resolution = resolution,
            selectedRule = selectedRule,
            selectedSourceId = selectedId?.let(sourceByCandidateId::get),
            warnings = selectionWarnings("jours fériés", resolution, branch != null, accoRules.isNotEmpty())
        )
    }

    private fun accoCandidateId(rule: CompanyAgreementStructuredRuleV2.Rule): String =
        "ACCO:${rule.source.agreementId}:${rule.source.category.name}:" +
            rule.source.excerpt.hashCode().toUInt().toString(16)

    private fun selectionWarnings(
        label: String,
        resolution: PayrollLegalArbitratorV2.Resolution,
        hasBranchRule: Boolean,
        hasCompanyRule: Boolean
    ): List<String> = buildList {
        when (resolution.state) {
            PayrollLegalArbitratorV2.State.RESOLVED -> {
                val source = resolution.selected?.source?.name.orEmpty()
                add("Arbitrage $label : règle $source vérifiée retenue pour la date de paie.")
            }
            PayrollLegalArbitratorV2.State.CONFLICT ->
                add("Arbitrage $label : plusieurs règles incompatibles sont applicables ; aucune majoration n'est calculée.")
            PayrollLegalArbitratorV2.State.REVIEW_REQUIRED -> {
                if (hasBranchRule && !hasCompanyRule) {
                    add("Arbitrage $label : règle KALI connue, mais l'absence d'une règle ACCO prioritaire n'est pas confirmée ; application bloquée.")
                } else {
                    add("Arbitrage $label : contrôle juridique complémentaire requis avant application automatique.")
                }
            }
            PayrollLegalArbitratorV2.State.NO_APPLICABLE_RULE ->
                add("Arbitrage $label : aucune règle collective calculable n'est actuellement retenue.")
        }
    }
}
