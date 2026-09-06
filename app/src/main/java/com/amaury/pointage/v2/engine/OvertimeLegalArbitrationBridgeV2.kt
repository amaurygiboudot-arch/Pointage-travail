package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.V2ConventionRuleStore
import java.time.LocalDate
import java.time.ZoneId

/**
 * Première passerelle réelle entre les sources juridiques pour une règle de paie chiffrée.
 *
 * Elle assemble :
 * - ACCO : taux d'accord d'entreprise déjà vérifiés, datés et non conflictuels ;
 * - KALI : snapshot conventionnel historique confirmé pour l'IDCC et la date ;
 * - LEGI : barème supplétif L3121-36 uniquement si son texte vérifié confirme encore 25 % / 50 %.
 *
 * BOCC et JORF restent volontairement hors du calcul : ils servent à détecter des publications qui
 * doivent ensuite être consolidées dans KALI/LEGI.
 */
object OvertimeLegalArbitrationBridgeV2 {
    data class Schedule(
        val source: PayrollLegalArbitratorV2.Source,
        val id: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val tiers: List<OvertimeTierV2>
    ) {
        init {
            require(id.isNotBlank())
            require(tiers.isNotEmpty())
        }

        val fingerprint: String = tiers
            .sortedBy { it.fromMinutes }
            .joinToString("|") { "${it.fromMinutes}:${it.toMinutes ?: -1}:${it.multiplier}" }
    }

    data class Snapshot(
        val referenceDate: LocalDate,
        val companySchedule: Schedule?,
        val branchSchedule: Schedule?,
        val statutorySchedule: Schedule?,
        val resolution: PayrollLegalArbitratorV2.Resolution,
        val warnings: List<String>
    ) {
        /** Barème complet uniquement. Un barème partiel n'arrive jamais jusqu'ici comme sélection. */
        val selectedSchedule: Schedule?
            get() = when (resolution.selected?.source) {
                PayrollLegalArbitratorV2.Source.ACCO -> companySchedule
                PayrollLegalArbitratorV2.Source.KALI -> branchSchedule
                PayrollLegalArbitratorV2.Source.LEGI -> statutorySchedule
                else -> null
            }
    }

    fun load(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        period: PayrollPeriodV2.Period? = null,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Snapshot {
        val agreement = CompanyAgreementPayrollBridgeV2.load(context, companyId, referenceDate, period)
        val branch = V2ConventionRuleStore.history(context).applicable(idcc, referenceDate.toEpochDay())
        val atMs = referenceDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val legalRecords = LegalPayrollSourceStoreV2.snapshot(context, atMs).records
        return assemble(referenceDate, agreement, branch, legalRecords, sourceKnowledge)
    }

    internal fun assemble(
        referenceDate: LocalDate,
        companyAgreement: CompanyAgreementPayrollBridgeV2.Snapshot?,
        branchSnapshot: ConventionRuleSnapshotV2?,
        legalRecords: List<LegalPayrollSourceStoreV2.Record>,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Snapshot {
        val warnings = mutableListOf<String>()

        val companySchedule = companyAgreement?.let { agreement ->
            when {
                agreement.hasOvertimeConflicts -> {
                    warnings += "ACCO : conflit entre plusieurs règles d'heures supplémentaires ; aucune priorité automatique n'est appliquée."
                    null
                }
                agreement.hasPeriodChanges -> {
                    warnings += "ACCO : changement de barème pendant la période ; l'arbitrage doit être fait par sous-période."
                    null
                }
                agreement.safeOvertimeRules.isEmpty() -> null
                else -> {
                    val tiers = agreement.safeOvertimeRules
                        .sortedBy { it.band.fromHourInclusive }
                        .map { rule ->
                            OvertimeTierV2(
                                fromMinutes = (rule.band.fromHourInclusive - 1) * 60,
                                toMinutes = rule.band.toHourInclusive?.times(60),
                                multiplier = 1.0 + rule.percent / 100.0
                            )
                        }
                    if (!coversWholeOvertimeRange(tiers)) {
                        warnings += "ACCO : barème d'heures supplémentaires partiel ; il n'est pas utilisé comme barème complet tant que les tranches manquantes ne sont pas confirmées."
                        null
                    } else {
                        val ids = agreement.safeOvertimeRules
                            .map { it.source.source.agreementId }
                            .distinct()
                            .sorted()
                        Schedule(
                            source = PayrollLegalArbitratorV2.Source.ACCO,
                            id = "ACCO:${ids.joinToString(",")}",
                            effectiveFrom = referenceDate,
                            effectiveTo = referenceDate,
                            tiers = tiers
                        )
                    }
                }
            }
        }

        val branchSchedule = branchSnapshot
            ?.takeIf { it.rules.overtimeTiers.isNotEmpty() }
            ?.let { snapshot ->
                val tiers = snapshot.rules.overtimeTiers
                if (!coversWholeOvertimeRange(tiers)) {
                    warnings += "KALI : barème conventionnel partiel ; l'arbitrage automatique est bloqué tant que toutes les tranches ne sont pas confirmées."
                    null
                } else {
                    Schedule(
                        source = PayrollLegalArbitratorV2.Source.KALI,
                        id = "KALI:${snapshot.versionId}:${snapshot.sourceId}",
                        effectiveFrom = LocalDate.ofEpochDay(snapshot.effectiveFromEpochDay),
                        effectiveTo = snapshot.effectiveToEpochDay?.let(LocalDate::ofEpochDay),
                        tiers = tiers
                    )
                }
            }

        val statutoryRule = StatutoryOvertimeRulesV2.fallbackRule(legalRecords, referenceDate)
        val statutorySchedule = statutoryRule?.let { rule ->
            Schedule(
                source = PayrollLegalArbitratorV2.Source.LEGI,
                id = "LEGI:${rule.articleId}:${rule.articleNumber}",
                effectiveFrom = rule.effectiveFrom,
                effectiveTo = rule.effectiveTo,
                tiers = rule.tiers
            )
        }

        val schedules = listOfNotNull(companySchedule, branchSchedule, statutorySchedule)
        val candidates = schedules.map { schedule ->
            PayrollLegalArbitratorV2.Candidate(
                id = schedule.id,
                source = schedule.source,
                effectiveFrom = schedule.effectiveFrom,
                effectiveTo = schedule.effectiveTo,
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = schedule.fingerprint
            )
        }
        val resolution = PayrollLegalArbitratorV2.resolve(
            candidates = candidates,
            referenceDate = referenceDate,
            policy = PayrollLegalArbitratorV2.Policy.OVERTIME_RATE_L3121_33_36,
            sourceKnowledge = sourceKnowledge
        )

        if (resolution.state != PayrollLegalArbitratorV2.State.RESOLVED) {
            warnings += resolution.explanation
        }

        return Snapshot(
            referenceDate = referenceDate,
            companySchedule = companySchedule,
            branchSchedule = branchSchedule,
            statutorySchedule = statutorySchedule,
            resolution = resolution,
            warnings = warnings.distinct()
        )
    }

    /**
     * Pour être considéré comme barème complet, la couverture commence après 35 h, reste continue
     * et la dernière tranche est ouverte. Une règle partielle reste une piste mais ne remplace pas
     * silencieusement les tranches absentes d'une autre source.
     */
    internal fun coversWholeOvertimeRange(tiers: List<OvertimeTierV2>): Boolean {
        if (tiers.isEmpty()) return false
        val sorted = tiers.sortedBy { it.fromMinutes }
        if (sorted.first().fromMinutes != 35 * 60) return false
        for (index in 0 until sorted.lastIndex) {
            val end = sorted[index].toMinutes ?: return false
            if (end != sorted[index + 1].fromMinutes) return false
        }
        return sorted.last().toMinutes == null
    }
}
