package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/** Audit KALI de la prime d'ancienneté pour le profil conventionnel exact. */
object KaliSeniorityPremiumAuditV2 {
    private val EXPRESSIONS = listOf("prime anciennete", "prime d anciennete")

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
            ?: return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI ancienneté : entreprise introuvable.")))
        if (profile.idcc.isBlank()) {
            return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI ancienneté : IDCC manquant.")))
        }
        if (profile.classification.isEmpty()) {
            markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Classification manquante")
            return Tasks.forResult(Summary(profile.idcc, referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI ancienneté : classification conventionnelle requise.")))
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Collecte KALI interrompue")
                return@continueWith Summary(profile.idcc, referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI ancienneté : collecte officielle impossible."))
            }
            val evidence = task.result
            val diagnostics = evidence.articles.map { OfficialKaliSeniorityPremiumParserV2.parse(it, profile, referenceDate) }
            val rules = diagnostics.mapNotNull { it.rule }.distinctBy(::fingerprint)
            var saved = false
            var completed = false
            var selectedRuleId: String? = null
            var saveError: String? = null

            if (evidence.technicalCoverageComplete && rules.size == 1) {
                val selected = rules.single()
                saved = runCatching {
                    V2ConventionSeniorityPremiumStore.saveConfirmed(context, selected)
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

            markCoverage(
                context,
                profile,
                referenceDate,
                if (completed) ConventionMatterCoverageV2.State.CONFIRMED_RULES else ConventionMatterCoverageV2.State.INCOMPLETE,
                if (completed) "Légifrance KALI — ancienneté structurée et extension datée" else "Analyse KALI ancienneté incomplète"
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                structuredCandidates = rules.size,
                saved = saved,
                completed = completed,
                selectedRuleId = selectedRuleId,
                warnings = buildList {
                    addAll(evidence.warnings)
                    diagnostics.filter { it.rule == null || it.reasons.isNotEmpty() }.take(12).forEach { d ->
                        if (d.reasons.isNotEmpty()) add("KALI ancienneté ${d.articleId} : ${d.reasons.joinToString()}.")
                    }
                    if (!evidence.technicalCoverageComplete) add("KALI ancienneté : couverture technique incomplète ; aucune formule n'est déclarée applicable.")
                    when {
                        rules.isEmpty() -> add("KALI ancienneté : aucune formule unique et explicitement rattachée à ${profile.classification.label()} ; aucune prime n'est inventée.")
                        rules.size > 1 -> add("KALI ancienneté : ${rules.size} formules structurées concurrentes correspondent au profil ; calcul automatique bloqué.")
                        saved && completed -> add("KALI ancienneté : formule unique, extension datée et profil exact vérifiés ; règle enregistrée.")
                        saved -> add("KALI ancienneté : règle officielle enregistrée comme preuve, mais applicabilité automatique à cette date/entreprise non démontrée.")
                        saveError != null -> add("KALI ancienneté : formule vérifiée mais stockage impossible : $saveError.")
                    }
                    add("KALI ancienneté : une recherche ciblée vide ne prouve jamais l'absence de prime d'ancienneté.")
                }.distinct()
            )
        }
    }

    private fun fingerprint(rule: ConventionSeniorityPremiumV2.Rule): String = buildString {
        append(rule.effectiveFrom).append('|').append(rule.effectiveTo).append('|')
        append(rule.classification.normalized().label()).append('|').append(rule.basis.name).append('|')
        rule.steps.forEach { append(it.years).append(':').append(it.rate).append(':').append(it.fixedMonthlyAmount).append(';') }
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
                matter = ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM,
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
