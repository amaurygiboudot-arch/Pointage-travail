package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2

/**
 * Base de proratisation explicitement confirmée pour un mois contenant plusieurs versions de contrat.
 *
 * HoraTrack n'invente jamais un prorata calendaire. La seule méthode supportée ici utilise des
 * minutes planifiées de référence confirmées pour chaque segment. Ces minutes et leur source doivent
 * être fournies par une couche amont (utilisateur, entreprise ou source structurée fiable).
 */
enum class ConfirmedProrationMethodV2 {
    SCHEDULED_MINUTES
}

data class ConfirmedProrationSegmentV2(
    val versionId: String,
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
    val scheduledMinutes: Int,
    val factor: Double,
    val fullMonthBaseGross: Double,
    val proratedBaseGross: Double
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
        "Proratisation mensuelle : la base confirmée ne correspond pas exactement aux segments contractuels du mois ; calcul bloqué."
    const val UNSUPPORTED_CONTRACT_WARNING =
        "Proratisation mensuelle : ce type de contrat ne peut pas être proratisé automatiquement avec des minutes planifiées confirmées."
    const val MISSING_PAYROLL_RULE_WARNING =
        "Proratisation mensuelle : une règle nécessaire à la base mensuelle du segment n'est pas confirmée ; calcul bloqué."

    /**
     * Calcule uniquement la base mensuelle proratisée. Les primes fixes, paniers, absences,
     * majorations variables et retenues restent traités par leurs moteurs dédiés afin d'éviter
     * tout double comptage.
     */
    fun calculate(
        segments: List<EmploymentContractCoverageSegmentV2>,
        rulesByVersionId: Map<String, PayrollRulesV2>,
        proration: ConfirmedSegmentedMonthlyProrationV2?
    ): SegmentedMonthlyBaseResultV2 {
        if (proration == null) return blocked(MISSING_PRORATION_WARNING)
        if (!validProration(proration, segments)) return blocked(INVALID_PRORATION_WARNING)

        val scheduledByVersion = proration.segments.associate { it.versionId.trim() to it.scheduledMinutes }
        val totalScheduled = scheduledByVersion.values.sumOf { it.toLong() }
        if (totalScheduled <= 0L) return blocked(INVALID_PRORATION_WARNING)

        val pieces = mutableListOf<SegmentedMonthlyBasePieceV2>()
        val warnings = mutableListOf<String>()

        for (segment in segments.sortedBy { it.startEpochDay }) {
            val versionId = segment.snapshot.versionId.trim()
            val scheduled = scheduledByVersion[versionId] ?: return blocked(INVALID_PRORATION_WARNING)
            val rules = rulesByVersionId[versionId] ?: PayrollRulesV2()
            val base = fullMonthBaseGross(segment.snapshot.contract, rules)
            if (base == null) {
                warnings += when (segment.snapshot.contract.type) {
                    ContractTypeV2.FORFAIT_HOURS,
                    ContractTypeV2.FORFAIT_DAYS,
                    ContractTypeV2.FORFAIT,
                    ContractTypeV2.OTHER -> UNSUPPORTED_CONTRACT_WARNING
                    ContractTypeV2.FULL_TIME,
                    ContractTypeV2.PART_TIME -> MISSING_PAYROLL_RULE_WARNING
                }
                return blocked(warnings.distinct())
            }
            val factor = scheduled.toDouble() / totalScheduled.toDouble()
            pieces += SegmentedMonthlyBasePieceV2(
                versionId = versionId,
                scheduledMinutes = scheduled,
                factor = factor,
                fullMonthBaseGross = base,
                proratedBaseGross = base * factor
            )
        }

        return SegmentedMonthlyBaseResultV2(
            pieces = pieces,
            baseGross = pieces.sumOf { it.proratedBaseGross },
            reliable = true,
            warnings = emptyList()
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
        segments: List<EmploymentContractCoverageSegmentV2>
    ): Boolean {
        if (segments.isEmpty() || !continuous(segments)) return false
        if (proration.sourceId.isBlank() || proration.checkedAtMs < 0L) return false
        if (proration.method != ConfirmedProrationMethodV2.SCHEDULED_MINUTES) return false

        val expectedIds = segments.map { it.snapshot.versionId.trim() }
        if (expectedIds.any { it.isBlank() } || expectedIds.distinct().size != expectedIds.size) return false

        val providedIds = proration.segments.map { it.versionId.trim() }
        if (providedIds.any { it.isBlank() } || providedIds.distinct().size != providedIds.size) return false
        if (providedIds.toSet() != expectedIds.toSet()) return false
        if (proration.segments.any { it.scheduledMinutes < 0 }) return false
        if (proration.segments.sumOf { it.scheduledMinutes.toLong() } <= 0L) return false
        return true
    }

    private fun continuous(segments: List<EmploymentContractCoverageSegmentV2>): Boolean {
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

    private fun blocked(warning: String): SegmentedMonthlyBaseResultV2 = blocked(listOf(warning))

    private fun blocked(warnings: List<String>) = SegmentedMonthlyBaseResultV2(
        pieces = emptyList(),
        baseGross = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
