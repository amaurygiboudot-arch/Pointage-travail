package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2

/**
 * Base de proratisation explicitement confirmée pour un mois contenant plusieurs segments de calcul.
 *
 * HoraTrack n'invente jamais un prorata calendaire. La seule méthode supportée ici utilise des
 * minutes planifiées de référence confirmées pour chaque segment. Ces minutes, leurs bornes et leur
 * source doivent être fournies par une couche amont (utilisateur, entreprise ou source fiable).
 */
enum class ConfirmedProrationMethodV2 {
    SCHEDULED_MINUTES
}

data class ConfirmedProrationSegmentV2(
    val versionId: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val scheduledMinutes: Int
)

data class ConfirmedSegmentedMonthlyProrationV2(
    val sourceId: String,
    val checkedAtMs: Long,
    val method: ConfirmedProrationMethodV2 = ConfirmedProrationMethodV2.SCHEDULED_MINUTES,
    val segments: List<ConfirmedProrationSegmentV2>
)

data class SegmentedMonthlyBasePieceV2(
    val versionId: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val scheduledMinutes: Int,
    val factor: Double,
    val fullMonthBaseGross: Double,
    val proratedBaseGross: Double,
    /** Version de règle conventionnelle appliquée à cette tranche, si le calcul vient de la timeline. */
    val ruleVersionId: String? = null
)

data class SegmentedMonthlyBaseResultV2(
    val pieces: List<SegmentedMonthlyBasePieceV2>,
    val baseGross: Double?,
    val reliable: Boolean,
    val warnings: List<String>
)

object ConfirmedSegmentedMonthlyProrationCalculatorV2 {
    const val MISSING_PRORATION_WARNING =
        "Proratisation mensuelle : base planifiée confirmée absente ; aucun prorata calendaire n'est inventé."
    const val INVALID_PRORATION_WARNING =
        "Proratisation mensuelle : la base confirmée ne correspond pas exactement aux segments de calcul du mois ; calcul bloqué."
    const val UNRELIABLE_TIMELINE_WARNING =
        "Proratisation mensuelle : timeline contrat/règles non fiable ou incomplète ; aucun montant segmenté n'est produit."
    const val UNSUPPORTED_CONTRACT_WARNING =
        "Proratisation mensuelle : ce type de contrat ne peut pas être proratisé automatiquement avec des minutes planifiées confirmées."
    const val MISSING_PAYROLL_RULE_WARNING =
        "Proratisation mensuelle : une règle nécessaire à la base mensuelle du segment n'est pas confirmée ; calcul bloqué."

    /**
     * Compatibilité avec le calcul par seuls segments contractuels introduit avant la timeline
     * contrat × règles. Les règles restent recherchées par version contractuelle.
     */
    fun calculate(
        segments: List<EmploymentContractCoverageSegmentV2>,
        rulesByVersionId: Map<String, PayrollRulesV2>,
        proration: ConfirmedSegmentedMonthlyProrationV2?
    ): SegmentedMonthlyBaseResultV2 {
        val inputs = segments.map { segment ->
            val versionId = segment.snapshot.versionId.trim()
            BaseSegment(
                versionId = versionId,
                ruleVersionId = null,
                startEpochDay = segment.startEpochDay,
                endEpochDay = segment.endEpochDay,
                contract = segment.snapshot.contract,
                rules = rulesByVersionId[versionId] ?: PayrollRulesV2()
            )
        }
        return calculateInputs(inputs, proration, emptyList())
    }

    /**
     * Calcule la base mensuelle sur la timeline datée contrat × règles.
     *
     * Une même version contractuelle peut donc apparaître plusieurs fois si la règle conventionnelle
     * change en cours de mois. Les bornes exactes font partie de la confirmation de proratisation :
     * une confirmation faite avant un changement de timeline devient automatiquement invalide.
     *
     * Cette couche ne valorise toujours ni heures supplémentaires variables, ni primes, ni paniers,
     * ni absences : elle ne traite que la base mensualisée afin d'éviter tout double comptage.
     */
    fun calculate(
        timeline: PayrollCalculationTimelineResultV2,
        proration: ConfirmedSegmentedMonthlyProrationV2?
    ): SegmentedMonthlyBaseResultV2 {
        if (!timeline.reliable || timeline.slices.isEmpty()) {
            return blocked((timeline.warnings + UNRELIABLE_TIMELINE_WARNING).distinct())
        }
        val inputs = timeline.slices.map { slice ->
            BaseSegment(
                versionId = slice.contractSnapshot.versionId.trim(),
                ruleVersionId = slice.ruleSnapshot.versionId.trim(),
                startEpochDay = slice.startEpochDay,
                endEpochDay = slice.endEpochDay,
                contract = slice.contractSnapshot.contract,
                rules = slice.ruleSnapshot.rules
            )
        }
        return calculateInputs(inputs, proration, timeline.warnings)
    }

    private data class BaseSegment(
        val versionId: String,
        val ruleVersionId: String?,
        val startEpochDay: Long,
        val endEpochDay: Long,
        val contract: ContractV2,
        val rules: PayrollRulesV2
    )

    private fun calculateInputs(
        segments: List<BaseSegment>,
        proration: ConfirmedSegmentedMonthlyProrationV2?,
        upstreamWarnings: List<String>
    ): SegmentedMonthlyBaseResultV2 {
        if (proration == null) return blocked(upstreamWarnings + MISSING_PRORATION_WARNING)
        if (!validProration(proration, segments)) return blocked(upstreamWarnings + INVALID_PRORATION_WARNING)

        val scheduledBySegment = proration.segments.associate {
            key(it.versionId, it.startEpochDay, it.endEpochDay) to it.scheduledMinutes
        }
        val totalScheduled = scheduledBySegment.values.fold(0L) { total, minutes ->
            val next = total + minutes.toLong()
            if (next < total) return blocked(upstreamWarnings + INVALID_PRORATION_WARNING)
            next
        }
        if (totalScheduled <= 0L) return blocked(upstreamWarnings + INVALID_PRORATION_WARNING)

        val pieces = mutableListOf<SegmentedMonthlyBasePieceV2>()
        for (segment in segments.sortedBy { it.startEpochDay }) {
            val scheduled = scheduledBySegment[key(segment.versionId, segment.startEpochDay, segment.endEpochDay)]
                ?: return blocked(upstreamWarnings + INVALID_PRORATION_WARNING)
            val base = fullMonthBaseGross(segment.contract, segment.rules)
            if (base == null) {
                val warning = when (segment.contract.type) {
                    ContractTypeV2.FORFAIT_HOURS,
                    ContractTypeV2.FORFAIT_DAYS,
                    ContractTypeV2.FORFAIT,
                    ContractTypeV2.OTHER -> UNSUPPORTED_CONTRACT_WARNING
                    ContractTypeV2.FULL_TIME,
                    ContractTypeV2.PART_TIME -> MISSING_PAYROLL_RULE_WARNING
                }
                return blocked(upstreamWarnings + warning)
            }
            val factor = scheduled.toDouble() / totalScheduled.toDouble()
            pieces += SegmentedMonthlyBasePieceV2(
                versionId = segment.versionId,
                startEpochDay = segment.startEpochDay,
                endEpochDay = segment.endEpochDay,
                scheduledMinutes = scheduled,
                factor = factor,
                fullMonthBaseGross = base,
                proratedBaseGross = base * factor,
                ruleVersionId = segment.ruleVersionId
            )
        }

        return SegmentedMonthlyBaseResultV2(
            pieces = pieces,
            baseGross = pieces.sumOf { it.proratedBaseGross },
            reliable = true,
            warnings = upstreamWarnings.distinct()
        )
    }

    private fun fullMonthBaseGross(contract: ContractV2, rules: PayrollRulesV2): Double? {
        val rate = contract.grossHourlyRate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val weekly = contract.contractualWeeklyMinutes?.takeIf { it > 0 } ?: return null

        return when (contract.type) {
            ContractTypeV2.PART_TIME -> weekly * (52.0 / 12.0) / 60.0 * rate
            ContractTypeV2.FULL_TIME -> {
                // Pour un temps plein, le seuil régulier doit être explicite. Utiliser la durée
                // contractuelle comme seuil ferait disparaître silencieusement les heures structurelles.
                val regularLimit = rules.weeklyRegularMinutes?.takeIf { it > 0 } ?: return null
                val result = FullTimeStructuralOvertimeV2.calculate(
                    contractualWeeklyMinutes = weekly,
                    regularWeeklyLimit = regularLimit,
                    paidWeeks = emptyList(),
                    grossHourlyRate = rate,
                    overtimeTiers = rules.overtimeTiers
                )
                if (result.provisionalRateUsed || result.unresolvedStructuralOvertimeMinutes > 0.0) null
                else result.monthlyBaseGross
            }
            ContractTypeV2.FORFAIT_HOURS,
            ContractTypeV2.FORFAIT_DAYS,
            ContractTypeV2.FORFAIT,
            ContractTypeV2.OTHER -> null
        }
    }

    private fun validProration(
        proration: ConfirmedSegmentedMonthlyProrationV2,
        segments: List<BaseSegment>
    ): Boolean {
        if (segments.isEmpty() || !continuous(segments)) return false
        if (proration.sourceId.isBlank() || proration.checkedAtMs < 0L) return false
        if (proration.method != ConfirmedProrationMethodV2.SCHEDULED_MINUTES) return false

        val expectedKeys = segments.map { key(it.versionId, it.startEpochDay, it.endEpochDay) }
        if (expectedKeys.any { it.first.isBlank() } || expectedKeys.distinct().size != expectedKeys.size) return false

        val providedKeys = proration.segments.map {
            if (it.endEpochDay < it.startEpochDay) return false
            key(it.versionId, it.startEpochDay, it.endEpochDay)
        }
        if (providedKeys.any { it.first.isBlank() } || providedKeys.distinct().size != providedKeys.size) return false
        if (providedKeys.toSet() != expectedKeys.toSet()) return false
        if (proration.segments.any { it.scheduledMinutes < 0 }) return false

        var total = 0L
        for (segment in proration.segments) {
            val next = total + segment.scheduledMinutes.toLong()
            if (next < total) return false
            total = next
        }
        return total > 0L
    }

    private fun continuous(segments: List<BaseSegment>): Boolean {
        val sorted = segments.sortedBy { it.startEpochDay }
        if (sorted.any { it.endEpochDay < it.startEpochDay }) return false
        for (index in 1 until sorted.size) {
            val previous = sorted[index - 1]
            val current = sorted[index]
            if (previous.endEpochDay == Long.MAX_VALUE || current.startEpochDay != previous.endEpochDay + 1L) {
                return false
            }
        }
        return true
    }

    private fun key(versionId: String, startEpochDay: Long, endEpochDay: Long): Triple<String, Long, Long> =
        Triple(versionId.trim(), startEpochDay, endEpochDay)

    private fun blocked(warning: String): SegmentedMonthlyBaseResultV2 = blocked(listOf(warning))

    private fun blocked(warnings: List<String>) = SegmentedMonthlyBaseResultV2(
        pieces = emptyList(),
        baseGross = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
