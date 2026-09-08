package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/**
 * Audit KALI strict des cotisations conventionnelles de prévoyance.
 *
 * La catégorie ANI doit déjà être vérifiée par le lot KALI + APEC. Cette étape recherche ensuite
 * bénéficiaires, assiette et taux dans KALI et ne persiste qu'un barème assemblé dans un KALITEXT
 * unique. Une recherche vide ou techniquement incomplète ne prouve jamais l'absence de cotisation.
 */
object KaliProvidentContributionAuditV2 {
    private val EXPRESSIONS = listOf(
        "prévoyance cotisation",
        "cotisations régime prévoyance",
        "salaire de référence prévoyance",
        "assiette prévoyance",
        "bénéficiaires prévoyance",
        "part salariale prévoyance",
        "part patronale prévoyance"
    )

    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val pagesRead: Int,
        val articlesConsulted: Int,
        val structured: Boolean,
        val saved: Boolean,
        val completed: Boolean,
        val selectedRuleId: String? = null,
        val warnings: List<String> = emptyList()
    )

    internal data class Completion(
        val state: ConventionMatterCoverageV2.State,
        val completed: Boolean
    )

    fun audit(context: Context, companyId: String, referenceDate: LocalDate): Task<Summary> {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Tasks.forResult(
                Summary("", referenceDate, 0, 0, false, false, false, warnings = listOf("KALI prévoyance cotisations : entreprise introuvable."))
            )
        if (profile.idcc.isBlank()) {
            return Tasks.forResult(
                Summary("", referenceDate, 0, 0, false, false, false, warnings = listOf("KALI prévoyance cotisations : IDCC manquant."))
            )
        }
        if (profile.professionalStatus == null) {
            markCoverage(
                context,
                profile,
                referenceDate,
                ConventionMatterCoverageV2.State.INCOMPLETE,
                emptySet(),
                "Statut cadre/non-cadre local manquant"
            )
            return Tasks.forResult(
                Summary(
                    profile.idcc,
                    referenceDate,
                    0,
                    0,
                    false,
                    false,
                    false,
                    warnings = listOf("KALI prévoyance cotisations : statut cadre/non-cadre exact requis avant audit.")
                )
            )
        }

        val category = VerifiedProtectionCategoryProviderV2.resolve(context, companyId, referenceDate)
        if (!category.reliable || !category.category.confirmed) {
            markCoverage(
                context,
                profile,
                referenceDate,
                ConventionMatterCoverageV2.State.INCOMPLETE,
                emptySet(),
                "Catégorie ANI KALI + APEC non confirmée"
            )
            return Tasks.forResult(
                Summary(
                    idcc = profile.idcc,
                    referenceDate = referenceDate,
                    pagesRead = 0,
                    articlesConsulted = 0,
                    structured = false,
                    saved = false,
                    completed = false,
                    warnings = (category.warnings +
                        "KALI prévoyance cotisations : catégorie ANI exacte requise avant recherche de barème.").distinct()
                )
            )
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                markCoverage(
                    context,
                    profile,
                    referenceDate,
                    ConventionMatterCoverageV2.State.INCOMPLETE,
                    setOf(ConventionMatterCoverageV2.Authority.KALI),
                    "Collecte KALI cotisations prévoyance interrompue"
                )
                return@continueWith Summary(
                    profile.idcc,
                    referenceDate,
                    0,
                    0,
                    false,
                    false,
                    false,
                    warnings = listOf("KALI prévoyance cotisations : collecte officielle impossible.")
                )
            }

            val evidence = task.result
            val diagnostic = OfficialKaliProvidentContributionParserV2.parse(
                profile = profile,
                protectionCategory = category.category,
                evidence = evidence
            )
            val rule = diagnostic.rule
            var saved = false
            var saveError: String? = null
            if (rule != null) {
                saved = runCatching {
                    V2ConventionProvidentContributionStore.saveVerified(context, rule)
                    true
                }.getOrElse { error ->
                    saveError = error.message ?: "stockage impossible"
                    false
                }
            }

            val completion = evaluateCompletion(
                technicalCoverageComplete = evidence.technicalCoverageComplete,
                rule = rule,
                saved = saved,
                referenceDate = referenceDate
            )
            markCoverage(
                context = context,
                profile = profile,
                referenceDate = referenceDate,
                state = completion.state,
                authorities = setOf(ConventionMatterCoverageV2.Authority.KALI),
                source = if (completion.completed) {
                    "Légifrance KALI — cotisations prévoyance structurées, étendues et applicables"
                } else {
                    "Analyse KALI cotisations prévoyance incomplète"
                }
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                structured = rule != null,
                saved = saved,
                completed = completion.completed,
                selectedRuleId = rule?.ruleId?.takeIf { saved },
                warnings = buildList {
                    addAll(evidence.warnings)
                    addAll(diagnostic.reasons)
                    if (!evidence.technicalCoverageComplete) {
                        add("KALI prévoyance cotisations : couverture technique incomplète ; aucun barème n'est déclaré applicable.")
                    }
                    when {
                        rule == null -> add("KALI prévoyance cotisations : aucun barème complet et non ambigu n'a été assemblé ; aucune cotisation n'est inventée.")
                        saveError != null -> add("KALI prévoyance cotisations : règle structurée mais stockage impossible : $saveError.")
                        saved && completion.completed -> add("KALI prévoyance cotisations : barème unique, extension datée et catégorie ANI vérifiée ; règle enregistrée.")
                        saved -> add("KALI prévoyance cotisations : règle officielle enregistrée comme preuve, mais applicabilité automatique non démontrée pour cette date/entreprise.")
                    }
                    add("KALI prévoyance cotisations : une recherche ciblée vide ne vaut jamais preuve d'absence de cotisation conventionnelle.")
                }.distinct()
            )
        }
    }

    internal fun evaluateCompletion(
        technicalCoverageComplete: Boolean,
        rule: ConventionProvidentContributionV2.Rule?,
        saved: Boolean,
        referenceDate: LocalDate
    ): Completion {
        val completed = technicalCoverageComplete &&
            saved &&
            rule != null &&
            rule.structurallyValid() &&
            rule.extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
            rule.extensionEffectiveFrom?.let { !referenceDate.isBefore(it) } == true
        return Completion(
            state = if (completed) {
                ConventionMatterCoverageV2.State.CONFIRMED_RULES
            } else {
                ConventionMatterCoverageV2.State.INCOMPLETE
            },
            completed = completed
        )
    }

    private fun markCoverage(
        context: Context,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        state: ConventionMatterCoverageV2.State,
        authorities: Set<ConventionMatterCoverageV2.Authority>,
        source: String
    ) {
        V2ConventionMatterCoverageStore.save(
            context,
            ConventionMatterCoverageV2.Record(
                idcc = profile.idcc,
                matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
                effectiveFrom = referenceDate.withDayOfMonth(1),
                effectiveTo = referenceDate.withDayOfMonth(referenceDate.lengthOfMonth()),
                classification = profile.classification,
                professionalStatus = profile.professionalStatus,
                state = state,
                source = source,
                checkedAtMs = System.currentTimeMillis(),
                authorities = authorities
            )
        )
    }
}
