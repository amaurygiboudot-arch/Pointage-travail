package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSicknessMaintenanceV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/** Audit KALI strict du maintien maladie pour le profil juridique local de l'entreprise ouverte. */
object KaliSicknessMaintenanceAuditV2 {
    private val EXPRESSIONS = listOf(
        "maintien salaire maladie",
        "indemnisation maladie",
        "arrêt travail maladie",
        "incapacité temporaire salaire"
    )

    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val pagesRead: Int,
        val articlesConsulted: Int,
        val structuredCandidates: Int,
        val saved: Boolean,
        val completed: Boolean,
        val selectedRuleId: String? = null,
        val warnings: List<String> = emptyList()
    )

    fun audit(context: Context, companyId: String, referenceDate: LocalDate): Task<Summary> {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI maladie : entreprise introuvable.")))
        if (profile.idcc.isBlank()) {
            return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI maladie : IDCC manquant.")))
        }
        if (profile.professionalStatus == null) {
            markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Statut cadre/non-cadre manquant")
            return Tasks.forResult(
                Summary(
                    profile.idcc,
                    referenceDate,
                    0,
                    0,
                    0,
                    false,
                    false,
                    warnings = listOf("KALI maladie : statut cadre/non-cadre requis avant sélection automatique d'un barème.")
                )
            )
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Collecte KALI interrompue")
                return@continueWith Summary(
                    profile.idcc,
                    referenceDate,
                    0,
                    0,
                    0,
                    false,
                    false,
                    warnings = listOf("KALI maladie : collecte officielle impossible.")
                )
            }

            val evidence = task.result
            val diagnostics = evidence.articles.map {
                OfficialKaliSicknessMaintenanceParserV2.parse(it, profile, referenceDate)
            }
            val rules = diagnostics.mapNotNull { it.rule }
            val distinct = rules.distinctBy(::fingerprint)
            var saved = false
            var completed = false
            var selectedRuleId: String? = null
            var saveError: String? = null

            if (evidence.technicalCoverageComplete && distinct.size == 1) {
                val selected = distinct.single()
                saved = runCatching {
                    V2ConventionSicknessMaintenanceStore.saveConfirmed(context, selected)
                    true
                }.getOrElse { error ->
                    saveError = error.message ?: "stockage impossible"
                    false
                }
                if (saved) {
                    selectedRuleId = selected.ruleId
                    completed = selected.extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
                        selected.extensionEffectiveFrom?.let { !referenceDate.isBefore(it) } == true
                }
            }

            val state = if (completed) ConventionMatterCoverageV2.State.CONFIRMED_RULES else ConventionMatterCoverageV2.State.INCOMPLETE
            markCoverage(
                context,
                profile,
                referenceDate,
                state,
                if (completed) "Légifrance KALI — maintien maladie structuré et applicable" else "Analyse KALI maladie incomplète"
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                structuredCandidates = distinct.size,
                saved = saved,
                completed = completed,
                selectedRuleId = selectedRuleId,
                warnings = buildList {
                    addAll(evidence.warnings)
                    diagnostics.filter { it.rule == null || it.reasons.isNotEmpty() }.take(16).forEach { diagnostic ->
                        if (diagnostic.reasons.isNotEmpty()) {
                            add("KALI maladie ${diagnostic.articleId} : ${diagnostic.reasons.joinToString()}.")
                        }
                    }
                    if (!evidence.technicalCoverageComplete) {
                        add("KALI maladie : couverture technique incomplète ; aucun barème n'est déclaré applicable.")
                    }
                    when {
                        distinct.isEmpty() -> add("KALI maladie : aucun barème complet et non ambigu ne correspond au profil ; aucun maintien n'est inventé.")
                        distinct.size > 1 -> add("KALI maladie : ${distinct.size} barèmes structurés concurrents correspondent au profil ; sélection automatique bloquée.")
                        saved && completed -> add("KALI maladie : barème unique, extension datée et profil exact vérifiés ; règle enregistrée.")
                        saved -> add("KALI maladie : règle officielle enregistrée comme preuve, mais applicabilité automatique à cette date/entreprise non démontrée.")
                        saveError != null -> add("KALI maladie : règle vérifiée mais stockage impossible : $saveError.")
                    }
                    add("KALI maladie : une recherche ciblée vide ne vaut jamais preuve d'absence de droit conventionnel.")
                }.distinct()
            )
        }
    }

    private fun fingerprint(rule: ConventionSicknessMaintenanceV2.Rule): String = buildString {
        append(rule.effectiveFrom).append('|')
        append(rule.effectiveTo ?: "").append('|')
        append(rule.classification.normalized().label()).append('|')
        append(rule.professionalStatus ?: "").append('|')
        append(rule.minimumSeniorityMonths).append('|')
        append(rule.referenceBasis.name).append('|')
        append(rule.waitingPolicy.name).append(':').append(rule.waitingDays).append('|')
        rule.tiers.forEach { tier ->
            append(tier.minimumSeniorityMonths).append(':')
            append(tier.annualLimitDays ?: -1).append(':')
            append(tier.perStopLimitDays ?: -1).append(':')
            append(tier.bandConsumptionScope.name).append(':')
            tier.bands.forEach { band -> append(band.calendarDays).append('@').append(band.targetRate).append(',') }
            append(';')
        }
        append('|').append(rule.extensionStatus.name).append('|').append(rule.extensionEffectiveFrom ?: "")
    }

    private fun markCoverage(
        context: Context,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        state: ConventionMatterCoverageV2.State,
        source: String
    ) {
        V2ConventionMatterCoverageStore.save(
            context,
            ConventionMatterCoverageV2.Record(
                idcc = profile.idcc,
                matter = ConventionMatterCoverageV2.Matter.SICKNESS_MAINTENANCE,
                effectiveFrom = referenceDate.withDayOfMonth(1),
                effectiveTo = referenceDate.withDayOfMonth(referenceDate.lengthOfMonth()),
                classification = profile.classification,
                professionalStatus = profile.professionalStatus,
                state = state,
                source = source,
                checkedAtMs = System.currentTimeMillis()
            )
        )
    }
}
