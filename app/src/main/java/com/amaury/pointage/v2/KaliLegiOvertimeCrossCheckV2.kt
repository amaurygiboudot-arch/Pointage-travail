package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.StatutoryOvertimeRulesV2
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Recoupe les indices KALI avec le barème supplétif LEGI déjà vérifié.
 *
 * Cette couche est volontairement non destructive : elle signale les concordances et les conflits,
 * mais ne transforme jamais un ancien seuil conventionnel en seuil 35 h et ne crée aucun snapshot.
 */
object KaliLegiOvertimeCrossCheckV2 {
    data class Result(
        val statutoryAvailable: Boolean,
        val statutoryArticleId: String? = null,
        val matchingCurrentLawArticleIds: List<String> = emptyList(),
        val conflictingLegacyThresholdArticleIds: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    fun analyze(
        context: Context,
        referenceDate: LocalDate,
        diagnostics: List<OfficialKaliOvertimeRuleParserV2.ArticleDiagnostic>
    ): Result {
        val explicitRates = diagnostics.filter {
            it.kind == OfficialKaliOvertimeRuleParserV2.DiagnosticKind.EXPLICIT_RATES_WITHOUT_35H
        }
        if (explicitRates.isEmpty()) return Result(statutoryAvailable = false)

        val atMs = referenceDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val records = runCatching { LegalPayrollSourceStoreV2.snapshot(context, atMs).records }
            .getOrElse {
                return Result(
                    statutoryAvailable = false,
                    warnings = listOf("KALI/LEGI : sources LEGI locales indisponibles pour le recoupement.")
                )
            }
        val statutory = StatutoryOvertimeRulesV2.fallbackRule(records, referenceDate)
            ?: return Result(
                statutoryAvailable = false,
                warnings = listOf(
                    "KALI/LEGI : des taux KALI ont été trouvés sans seuil 35 h, mais aucun barème supplétif LEGI vérifié n'est disponible pour les recouper."
                )
            )

        val legalRates = statutory.tiers.map { (it.multiplier - 1.0) * 100.0 }
        val matching = explicitRates.filter { diagnostic ->
            diagnostic.referencesCurrentLaw &&
                diagnostic.hourThresholds.isEmpty() &&
                sameRates(diagnostic.percentages, legalRates)
        }
        val legacyThresholds = explicitRates.filter { diagnostic ->
            diagnostic.hourThresholds.any { threshold -> threshold != 35 }
        }

        return Result(
            statutoryAvailable = true,
            statutoryArticleId = statutory.articleId,
            matchingCurrentLawArticleIds = matching.map { it.article.articleId }.distinct(),
            conflictingLegacyThresholdArticleIds = legacyThresholds.map { it.article.articleId }.distinct(),
            warnings = buildList {
                if (matching.isNotEmpty()) {
                    add(
                        "KALI/LEGI : ${matching.size} article(s) KALI renvoient à la législation en vigueur et présentent les mêmes taux que le barème LEGI vérifié, sans seuil horaire contradictoire. Ils restent des preuves recoupées, pas un barème KALI autonome."
                    )
                }
                if (legacyThresholds.isNotEmpty()) {
                    add(
                        "KALI/LEGI : ${legacyThresholds.size} article(s) KALI comportent un seuil horaire historique ou différent ; HoraTrack refuse de remplacer ce seuil par 35 h automatiquement."
                    )
                }
            }
        )
    }

    private fun sameRates(left: List<Double>, right: List<Double>): Boolean {
        if (left.size != right.size || left.isEmpty()) return false
        return left.zip(right).all { (a, b) -> abs(a - b) < 0.0001 }
    }
}
