package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.HttpsCallableResult
import java.time.LocalDate
import java.util.Locale

/**
 * Audit final KALI + APEC de la catégorie objective ANI.
 *
 * Une règle approuvée n'est persistée que si, dans la même exécution :
 * - la collecte KALI est techniquement complète ;
 * - chaque KALITEXT utile possède une identité formelle exploitable ;
 * - tous les agréments APEC découverts pour l'IDCC ont été téléchargés/extraits ;
 * - la résolution combinée est fiable à la date demandée.
 */
object ApecProtectionCategoryAuditV2 {
    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val kaliRulesConsidered: Int,
        val kaliIdentitiesResolved: Int,
        val apecCandidatesFound: Int,
        val apecDocumentsRead: Int,
        val approvedRulesFound: Int,
        val savedApprovedRule: Boolean,
        val completed: Boolean,
        val warnings: List<String>
    )

    internal data class IdentityBatch(
        val identities: Map<String, OfficialKaliTextIdentityV2.Identity>,
        val complete: Boolean,
        val warnings: List<String>
    )

    internal data class Evaluation(
        val approvedRulesFound: Int,
        val selectedRule: ConventionProtectionCategoryV2.Rule?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun audit(context: Context, companyId: String, referenceDate: LocalDate): Task<Summary> {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Tasks.forResult(
                Summary("", referenceDate, 0, 0, 0, 0, 0, false, false,
                    listOf("APEC catégorie ANI : entreprise ou profil salarié introuvable."))
            )
        if (profile.idcc.isBlank() || profile.classification.isEmpty() || profile.professionalStatus == null) {
            return Tasks.forResult(
                Summary(profile.idcc, referenceDate, 0, 0, 0, 0, 0, false, false,
                    listOf("APEC catégorie ANI : IDCC, classification exacte et statut cadre/non-cadre sont requis."))
            )
        }

        return KaliProtectionCategoryAuditV2.audit(context, companyId, referenceDate).continueWithTask { kaliTask ->
            if (!kaliTask.isSuccessful) {
                return@continueWithTask Tasks.forResult(
                    Summary(profile.idcc, referenceDate, 0, 0, 0, 0, 0, false, false,
                        listOf("APEC catégorie ANI : audit KALI préalable interrompu ; aucun agrément n'est appliqué."))
                )
            }
            val kali = kaliTask.result
            val rules = exactProfileRules(
                profile,
                referenceDate,
                V2ConventionProtectionCategoryStore.rules(context, profile.idcc)
            )
            if (!kali.kaliTechnicalCoverageComplete || rules.isEmpty()) {
                return@continueWithTask Tasks.forResult(
                    Summary(
                        idcc = profile.idcc,
                        referenceDate = referenceDate,
                        kaliRulesConsidered = rules.size,
                        kaliIdentitiesResolved = 0,
                        apecCandidatesFound = 0,
                        apecDocumentsRead = 0,
                        approvedRulesFound = 0,
                        savedApprovedRule = false,
                        completed = false,
                        warnings = buildList {
                            addAll(kali.warnings)
                            if (!kali.kaliTechnicalCoverageComplete) add("APEC catégorie ANI : couverture KALI incomplète ; APEC non utilisé pour valider la paie.")
                            if (rules.isEmpty()) add("APEC catégorie ANI : aucune preuve KALI scoped exacte n'est disponible pour ce profil ; absence de catégorie non déduite.")
                        }.distinct()
                    )
                )
            }

            val scopes = rules.mapNotNull { it.conventionScopeKey }.distinct()
            fetchIdentities(scopes).continueWithTask { identityTask ->
                val identities = if (identityTask.isSuccessful) {
                    identityTask.result
                } else {
                    IdentityBatch(emptyMap(), false, listOf("APEC catégorie ANI : consultation des identités KALITEXT interrompue."))
                }
                if (!identities.complete || identities.identities.size != scopes.size) {
                    markCoverage(
                        context,
                        profile,
                        referenceDate,
                        ConventionMatterCoverageV2.State.INCOMPLETE,
                        setOf(ConventionMatterCoverageV2.Authority.KALI),
                        "KALI catégorie ANI complet mais identité formelle d'au moins un KALITEXT incomplète"
                    )
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            profile.idcc,
                            referenceDate,
                            rules.size,
                            identities.identities.size,
                            0,
                            0,
                            0,
                            false,
                            false,
                            (kali.warnings + identities.warnings +
                                "APEC catégorie ANI : identité formelle de tous les KALITEXT requise avant consultation APEC.").distinct()
                        )
                    )
                }

                OfficialApecDecisionClientV2.fetch(context, profile.idcc).continueWith { apecTask ->
                    if (!apecTask.isSuccessful) {
                        markCoverage(
                            context,
                            profile,
                            referenceDate,
                            ConventionMatterCoverageV2.State.INCOMPLETE,
                            setOf(ConventionMatterCoverageV2.Authority.KALI),
                            "Collecte APEC interrompue"
                        )
                        return@continueWith Summary(
                            profile.idcc, referenceDate, rules.size, identities.identities.size,
                            0, 0, 0, false, false,
                            (kali.warnings + identities.warnings + "APEC catégorie ANI : collecte officielle impossible.").distinct()
                        )
                    }

                    val apec = apecTask.result
                    val evaluation = evaluate(
                        profile = profile,
                        referenceDate = referenceDate,
                        rules = rules,
                        identities = identities,
                        apec = apec,
                        kaliTechnicalCoverageComplete = kali.kaliTechnicalCoverageComplete
                    )

                    var saved = false
                    var saveWarning: String? = null
                    if (evaluation.reliable && evaluation.selectedRule != null) {
                        saved = runCatching {
                            V2ConventionProtectionCategoryStore.saveVerified(context, evaluation.selectedRule)
                            true
                        }.getOrElse { error ->
                            saveWarning = error.message ?: "stockage impossible"
                            false
                        }
                    }
                    val completed = evaluation.reliable && saved
                    markCoverage(
                        context = context,
                        profile = profile,
                        referenceDate = referenceDate,
                        state = if (completed) ConventionMatterCoverageV2.State.CONFIRMED_RULES else ConventionMatterCoverageV2.State.INCOMPLETE,
                        authorities = setOf(
                            ConventionMatterCoverageV2.Authority.KALI,
                            ConventionMatterCoverageV2.Authority.APEC
                        ),
                        source = if (completed) {
                            "Légifrance KALI + Commission paritaire Apec — catégorie ANI rapprochée et applicable"
                        } else {
                            "Légifrance KALI + Commission paritaire Apec — rapprochement catégorie ANI incomplet"
                        }
                    )

                    Summary(
                        idcc = profile.idcc,
                        referenceDate = referenceDate,
                        kaliRulesConsidered = rules.size,
                        kaliIdentitiesResolved = identities.identities.size,
                        apecCandidatesFound = apec.candidatesFound,
                        apecDocumentsRead = apec.documents.size,
                        approvedRulesFound = evaluation.approvedRulesFound,
                        savedApprovedRule = saved,
                        completed = completed,
                        warnings = buildList {
                            addAll(kali.warnings)
                            addAll(identities.warnings)
                            addAll(apec.warnings)
                            addAll(evaluation.warnings)
                            if (saveWarning != null) add("APEC catégorie ANI : règle approuvée non enregistrée : $saveWarning.")
                            if (completed) add("APEC catégorie ANI : preuve KALI + APEC complète et applicable ; catégorie enregistrée.")
                            else add("APEC catégorie ANI : couverture finale incomplète ; aucun calcul dépendant n'est débloqué par cet audit.")
                        }.distinct()
                    )
                }
            }
        }
    }

    internal fun exactProfileRules(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        rules: List<ConventionProtectionCategoryV2.Rule>
    ): List<ConventionProtectionCategoryV2.Rule> {
        val wantedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        val wantedStatus = profile.professionalStatus?.trim()?.uppercase(Locale.ROOT) ?: return emptyList()
        return rules.filter { rule ->
            rule.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc) == wantedIdcc &&
                rule.activeOn(referenceDate) &&
                profile.classification.matches(rule.classification) &&
                rule.classification.matches(profile.classification) &&
                rule.professionalStatus?.trim()?.uppercase(Locale.ROOT) == wantedStatus &&
                rule.conventionScopeKey?.matches(Regex("^KALITEXT\\d+$")) == true
        }.distinctBy { listOf(it.ruleId, it.conventionScopeKey, it.aniCategory.name).joinToString("|") }
    }

    internal fun evaluate(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        rules: List<ConventionProtectionCategoryV2.Rule>,
        identities: IdentityBatch,
        apec: OfficialApecDecisionClientV2.Result,
        kaliTechnicalCoverageComplete: Boolean
    ): Evaluation {
        if (!kaliTechnicalCoverageComplete) {
            return Evaluation(0, null, false, listOf("APEC catégorie ANI : couverture KALI technique incomplète."))
        }
        if (!identities.complete || rules.any { it.conventionScopeKey !in identities.identities }) {
            return Evaluation(0, null, false, listOf("APEC catégorie ANI : identité KALITEXT incomplète."))
        }
        if (!apec.technicalCoverageComplete) {
            return Evaluation(0, null, false, (apec.warnings +
                "APEC catégorie ANI : tous les agréments de l'IDCC doivent être lisibles avant validation.").distinct())
        }

        val approved = mutableListOf<ConventionProtectionCategoryV2.Rule>()
        val diagnostics = mutableListOf<String>()
        rules.forEach { rule ->
            val scope = rule.conventionScopeKey ?: return@forEach
            val identity = identities.identities[scope] ?: return@forEach
            apec.documents.forEach { fetched ->
                val result = ProtectionCategoryApprovalPipelineV2.resolve(
                    profile = profile,
                    referenceDate = referenceDate,
                    kaliRule = rule,
                    kaliIdentity = identity,
                    apecDocument = fetched.document
                )
                result.approvedRule?.let(approved::add)
                if (result.approvedRule == null) diagnostics += result.warnings
            }
        }

        val distinctApproved = approved.distinctBy(::approvedFingerprint)
        if (distinctApproved.isEmpty()) {
            return Evaluation(
                0,
                null,
                false,
                (diagnostics + "APEC catégorie ANI : aucun agrément ne se rattache exactement aux preuves KALI du profil.").distinct()
            )
        }

        val resolution = ConventionProtectionCategoryV2.resolve(
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            rules = distinctApproved
        )
        if (!resolution.reliable || resolution.selectedRule == null) {
            return Evaluation(
                distinctApproved.size,
                null,
                false,
                (resolution.warnings + "APEC catégorie ANI : les agréments rapprochés ne produisent pas une catégorie unique applicable à cette date.").distinct()
            )
        }

        return Evaluation(
            approvedRulesFound = distinctApproved.size,
            selectedRule = resolution.selectedRule,
            reliable = true,
            warnings = resolution.warnings.distinct()
        )
    }

    private fun fetchIdentities(
        scopes: List<String>,
        index: Int = 0,
        identities: Map<String, OfficialKaliTextIdentityV2.Identity> = emptyMap(),
        complete: Boolean = true,
        warnings: List<String> = emptyList()
    ): Task<IdentityBatch> {
        if (index >= scopes.size) {
            return Tasks.forResult(IdentityBatch(identities, complete, warnings.distinct()))
        }
        val textId = scopes[index]
        return requestWithRetry("/consult/kaliText", mapOf("id" to textId)).continueWithTask { task ->
            if (!task.isSuccessful) {
                fetchIdentities(
                    scopes,
                    index + 1,
                    identities,
                    false,
                    warnings + "KALI identité $textId : consultation officielle impossible après deux tentatives."
                )
            } else {
                val diagnostic = OfficialKaliTextIdentityV2.parse(task.result.data, textId)
                val identity = diagnostic.identity?.takeIf { it.reliableForCrossSourceScope }
                fetchIdentities(
                    scopes = scopes,
                    index = index + 1,
                    identities = if (identity == null) identities else identities + (textId to identity),
                    complete = complete && identity != null,
                    warnings = warnings + diagnostic.reasons
                )
            }
        }
    }

    private fun requestWithRetry(path: String, body: Map<String, Any?>, attempt: Int = 0): Task<HttpsCallableResult> =
        LegifranceFunctionClientV2.request(path, body).continueWithTask { task ->
            if (task.isSuccessful || attempt >= 1) task
            else requestWithRetry(path, body, attempt + 1)
        }

    private fun approvedFingerprint(rule: ConventionProtectionCategoryV2.Rule): String = listOf(
        ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc),
        rule.ruleId,
        rule.conventionScopeKey.orEmpty(),
        rule.aniCategory.name,
        rule.approvalEffectiveFrom?.toString().orEmpty(),
        rule.approvalSource.orEmpty()
    ).joinToString("|")

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
                matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CATEGORY,
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
