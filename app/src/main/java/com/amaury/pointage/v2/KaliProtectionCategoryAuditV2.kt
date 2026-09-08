package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/**
 * Audit KALI strict de la catégorie objective ANI pour le profil salarié local exact.
 *
 * Cette étape ne valide JAMAIS l'applicabilité finale : elle collecte et persiste seulement
 * des preuves KALI rattachées à un KALITEXT exact. L'agrément APEC reste une autorité séparée
 * et la couverture PROVIDENT_CATEGORY demeure INCOMPLETE tant qu'il n'est pas rapproché.
 */
object KaliProtectionCategoryAuditV2 {
    private val EXPRESSIONS = listOf(
        "accord national interprofessionnel 17 novembre 2017",
        "article 2.1 prévoyance cadres",
        "article 2.2 prévoyance cadres",
        "catégories objectives protection sociale complémentaire",
        "extension régime cadres"
    )

    data class StructuredEvidence(
        val parsedRules: Int,
        val scopedRules: List<ConventionProtectionCategoryV2.Rule>,
        val warnings: List<String>
    )

    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val pagesRead: Int,
        val articlesConsulted: Int,
        val parsedRules: Int,
        val scopedRules: Int,
        val savedRules: Int,
        val kaliTechnicalCoverageComplete: Boolean,
        /** Toujours false dans ce lot : KALI seul ne suffit pas sans APEC. */
        val completed: Boolean,
        val warnings: List<String>,
        /** Identifiants effectivement persistés pendant CETTE exécution, jamais hérités du store. */
        val savedRuleIds: List<String> = emptyList()
    )

    fun audit(context: Context, companyId: String, referenceDate: LocalDate): Task<Summary> {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Tasks.forResult(
                Summary("", referenceDate, 0, 0, 0, 0, 0, false, false, listOf("KALI catégorie ANI : entreprise introuvable."))
            )
        if (profile.idcc.isBlank()) {
            markCoverage(context, profile, referenceDate, emptySet(), "IDCC manquant")
            return Tasks.forResult(
                Summary("", referenceDate, 0, 0, 0, 0, 0, false, false, listOf("KALI catégorie ANI : IDCC manquant."))
            )
        }
        if (profile.classification.isEmpty()) {
            markCoverage(context, profile, referenceDate, emptySet(), "Classification locale manquante")
            return Tasks.forResult(
                Summary(profile.idcc, referenceDate, 0, 0, 0, 0, 0, false, false,
                    listOf("KALI catégorie ANI : classification conventionnelle exacte requise avant audit."))
            )
        }
        if (profile.professionalStatus == null) {
            markCoverage(context, profile, referenceDate, emptySet(), "Statut cadre/non-cadre local manquant")
            return Tasks.forResult(
                Summary(profile.idcc, referenceDate, 0, 0, 0, 0, 0, false, false,
                    listOf("KALI catégorie ANI : statut cadre/non-cadre exact requis avant audit."))
            )
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                markCoverage(context, profile, referenceDate, emptySet(), "Collecte KALI interrompue")
                return@continueWith Summary(
                    profile.idcc, referenceDate, 0, 0, 0, 0, 0, false, false,
                    listOf("KALI catégorie ANI : collecte officielle impossible ; aucun classement n'est déduit.")
                )
            }

            val evidence = task.result
            val structured = structureEvidence(profile, referenceDate, evidence)
            var saved = 0
            val savedRuleIds = mutableListOf<String>()
            val saveWarnings = mutableListOf<String>()
            structured.scopedRules.forEach { rule ->
                runCatching { V2ConventionProtectionCategoryStore.saveVerified(context, rule) }
                    .onSuccess {
                        saved += 1
                        savedRuleIds += rule.ruleId
                    }
                    .onFailure { error ->
                        saveWarnings += "KALI catégorie ANI : preuve ${rule.ruleId} non enregistrée : ${error.message ?: "stockage impossible"}."
                    }
            }

            markCoverage(
                context = context,
                profile = profile,
                referenceDate = referenceDate,
                authorities = setOf(ConventionMatterCoverageV2.Authority.KALI),
                source = if (evidence.technicalCoverageComplete) {
                    "Légifrance KALI — collecte catégorie ANI terminée ; agrément APEC non contrôlé"
                } else {
                    "Légifrance KALI — collecte catégorie ANI techniquement incomplète ; agrément APEC non contrôlé"
                }
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                parsedRules = structured.parsedRules,
                scopedRules = structured.scopedRules.size,
                savedRules = saved,
                kaliTechnicalCoverageComplete = evidence.technicalCoverageComplete,
                completed = false,
                warnings = buildList {
                    addAll(evidence.warnings)
                    addAll(structured.warnings)
                    addAll(saveWarnings)
                    if (saved > 0) add("KALI catégorie ANI : $saved preuve(s) KALI scoped enregistrée(s), sans applicabilité APEC présumée.")
                    if (structured.parsedRules > structured.scopedRules.size) {
                        add("KALI catégorie ANI : certaines preuves textuelles n'ont pas de KALITEXT parent unique ; elles restent non persistées.")
                    }
                    add("KALI catégorie ANI : l'audit reste INCOMPLETE tant que l'agrément APEC exact n'est pas rapproché.")
                    add("KALI catégorie ANI : une recherche ciblée vide ne vaut jamais preuve d'absence de catégorie conventionnelle.")
                }.distinct(),
                savedRuleIds = savedRuleIds.distinct()
            )
        }
    }

    internal fun structureEvidence(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        evidence: KaliMatterEvidenceAuditV2.Evidence
    ): StructuredEvidence {
        if (ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc) != ConventionMinimumSalaryV2.normalizeIdcc(evidence.idcc)) {
            return StructuredEvidence(0, emptyList(), listOf("KALI catégorie ANI : IDCC du profil et de la collecte différents."))
        }
        if (referenceDate != evidence.referenceDate) {
            return StructuredEvidence(0, emptyList(), listOf("KALI catégorie ANI : date du profil d'audit et date de collecte différentes."))
        }

        var parsed = 0
        val scoped = mutableListOf<ConventionProtectionCategoryV2.Rule>()
        val warnings = mutableListOf<String>()
        evidence.articles.forEach { article ->
            val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
                article = article,
                profile = profile,
                auditDate = referenceDate,
                verifiedIdcc = evidence.idcc
            )
            val parsedRule = diagnostic.rule
            if (parsedRule == null) {
                if (diagnostic.reasons.isNotEmpty()) {
                    warnings += "KALI catégorie ANI ${article.articleId} : ${diagnostic.reasons.joinToString()}."
                }
                return@forEach
            }
            parsed += 1
            val scope = KaliProtectionCategoryScopeV2.attach(parsedRule, article.articleId, evidence)
            if (scope.rule != null && scope.reliable) {
                scoped += scope.rule
            } else {
                warnings += scope.warnings
            }
        }

        return StructuredEvidence(
            parsedRules = parsed,
            scopedRules = scoped.distinctBy(::fingerprint),
            warnings = warnings.distinct()
        )
    }

    private fun fingerprint(rule: ConventionProtectionCategoryV2.Rule): String = listOf(
        ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc),
        rule.ruleId,
        rule.effectiveFrom.toString(),
        rule.effectiveTo?.toString().orEmpty(),
        rule.classification.normalized().label(),
        rule.professionalStatus.orEmpty(),
        rule.aniCategory.name,
        rule.conventionScopeKey.orEmpty(),
        rule.extensionStatus.name,
        rule.extensionEffectiveFrom?.toString().orEmpty()
    ).joinToString("|")

    private fun markCoverage(
        context: Context,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        authorities: Set<ConventionMatterCoverageV2.Authority>,
        source: String
    ) {
        V2ConventionMatterCoverageStore.save(
            context,
            ConventionMatterCoverageV2.Record(
                idcc = profile.idcc,
                matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CATEGORY,
                effectiveFrom = referenceDate.withDayOfMonth(1),
                effectiveTo = referenceDate.withDayOfMonth(referenceDate.lengthOfMonth()),
                classification = profile.classification,
                professionalStatus = profile.professionalStatus,
                state = ConventionMatterCoverageV2.State.INCOMPLETE,
                source = source,
                checkedAtMs = System.currentTimeMillis(),
                authorities = authorities
            )
        )
    }
}
