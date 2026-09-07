package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/** Audit KALI du minimum salarial pour la classification exacte de l'entreprise ouverte. */
object KaliMinimumSalaryAuditV2 {
    private val EXPRESSIONS = listOf(
        "salaire minimum",
        "salaires minima",
        "minimum conventionnel",
        "remuneration minimale"
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
            ?: return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI minimum : entreprise introuvable.")))
        if (profile.idcc.isBlank()) {
            return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI minimum : IDCC manquant.")))
        }
        if (profile.classification.isEmpty()) {
            markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Classification manquante")
            return Tasks.forResult(Summary(profile.idcc, referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI minimum : classification conventionnelle requise avant recherche précise.")))
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Collecte KALI interrompue")
                return@continueWith Summary(profile.idcc, referenceDate, 0, 0, 0, false, false, warnings = listOf("KALI minimum : collecte officielle impossible."))
            }
            val evidence = task.result
            val diagnostics = evidence.articles.map { OfficialKaliMinimumSalaryParserV2.parse(it, profile, referenceDate) }
            val rules = diagnostics.mapNotNull { it.rule }
            val distinct = rules.distinctBy(::fingerprint)
            var saved = false
            var selectedRuleId: String? = null
            var saveError: String? = null

            if (evidence.technicalCoverageComplete && distinct.size == 1) {
                val selected = distinct.single()
                saved = runCatching {
                    V2ConventionMinimumSalaryStore.saveConfirmed(context, selected)
                    true
                }.getOrElse { error ->
                    saveError = error.message ?: "stockage impossible"
                    false
                }
                if (saved) selectedRuleId = selected.ruleId
            }

            val state = if (saved) ConventionMatterCoverageV2.State.CONFIRMED_RULES else ConventionMatterCoverageV2.State.INCOMPLETE
            markCoverage(
                context,
                profile,
                referenceDate,
                state,
                if (saved) "Légifrance KALI — minimum salarial structuré" else "Analyse KALI minimum incomplète"
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                structuredCandidates = distinct.size,
                saved = saved,
                completed = saved,
                selectedRuleId = selectedRuleId,
                warnings = buildList {
                    addAll(evidence.warnings)
                    diagnostics.filter { it.rule == null }.take(12).forEach { diagnostic ->
                        if (diagnostic.reasons.isNotEmpty()) add("KALI minimum ${diagnostic.articleId} : ${diagnostic.reasons.joinToString()}.")
                    }
                    if (!evidence.technicalCoverageComplete) add("KALI minimum : couverture technique incomplète ; aucun barème n'est enregistré.")
                    when {
                        distinct.isEmpty() -> add("KALI minimum : aucun montant unique rattaché avec certitude à ${profile.classification.label()} ; aucun minimum n'est inventé.")
                        distinct.size > 1 -> add("KALI minimum : ${distinct.size} barèmes structurés concurrents correspondent au profil ; sélection automatique bloquée.")
                        saved -> add("KALI minimum : barème unique officiellement vérifié et enregistré pour ${profile.classification.label()}.")
                        saveError != null -> add("KALI minimum : barème vérifié mais stockage impossible : $saveError.")
                    }
                    add("KALI minimum : une recherche ciblée vide ne vaut jamais preuve d'absence de minimum conventionnel.")
                }.distinct()
            )
        }
    }

    private fun fingerprint(rule: ConventionMinimumSalaryV2.Rule): String = listOf(
        rule.effectiveFrom.toString(),
        rule.effectiveTo?.toString().orEmpty(),
        rule.classification.normalized().label(),
        rule.amount.toString(),
        rule.periodicity.name,
        rule.extensionStatus.name
    ).joinToString("|")

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
                matter = ConventionMatterCoverageV2.Matter.MINIMUM_SALARY,
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
