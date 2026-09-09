package com.amaury.pointage.v2

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.HttpsCallableResult

/**
 * Réanalyse officielle ACCO d'une entreprise : recherche par SIRET, consultation exacte,
 * puis extraction locale de candidats de paie. Les cotisations, garanties de prévoyance et
 * paniers/indemnités repas passent par leurs parseurs fail-closed dédiés avant stockage local.
 * Leur articulation avec la branche reste ensuite soumise à l'arbitrage juridique applicable.
 */
object CompanyAgreementOfficialAuditV2 {
    private const val PAGE_SIZE = 25

    data class Summary(
        val siret: String,
        val pagesRead: Int,
        val candidates: Int,
        val agreementsConsulted: Int,
        val agreementsVerified: Int,
        val agreementsRejected: Int,
        val extractedCandidates: Int,
        val completed: Boolean,
        val warnings: List<String> = emptyList()
    )

    private data class SearchBatch(
        val candidates: List<CompanyAgreementStoreV2.Agreement>,
        val pagesRead: Int,
        val complete: Boolean,
        val firstPageData: Any?,
        val warnings: List<String>
    )

    private data class ConsultBatch(
        val verifiedAgreements: List<CompanyAgreementStoreV2.Agreement> = emptyList(),
        val consulted: Int = 0,
        val rejected: Int = 0,
        val extractedCandidates: Int = 0,
        val transientFailures: Int = 0,
        val storageFailures: Int = 0,
        val warnings: List<String> = emptyList()
    )

    fun audit(
        context: Context,
        companyId: String,
        siret: String
    ): Task<Summary> {
        val normalizedSiret = normalizeSiret(siret)
            ?: return Tasks.forResult(
                Summary(siret.filter(Char::isDigit), 0, 0, 0, 0, 0, 0, false, listOf("ACCO : SIRET invalide."))
            )
        val app = context.applicationContext
        val mealProfile = ConventionLegalProfileV2.load(app, companyId)?.takeIf { profile ->
            profile.siret.filter(Char::isDigit) == normalizedSiret &&
                !profile.classification.isEmpty() &&
                profile.professionalStatus?.trim()?.uppercase() in setOf("CADRE", "NON_CADRE")
        }
        if (mealProfile != null && !MealBasketAuditTrustStoreV2.markAcco(
                context = app,
                companyId = companyId,
                profile = mealProfile,
                state = MealBasketAuditTrustStoreV2.State.INCOMPLETE
            )) {
            return Tasks.forResult(
                Summary(
                    normalizedSiret, 0, 0, 0, 0, 0, 0, false,
                    listOf("ACCO : impossible de verrouiller localement le début de l'audit repas ; aucun cache ACCO précédent n'est revalidé.")
                )
            )
        }

        return fetchPages(normalizedSiret)
            .continueWithTask { searchTask ->
                if (!searchTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Summary(normalizedSiret, 0, 0, 0, 0, 0, 0, false, listOf("ACCO : recherche officielle impossible."))
                    )
                }
                val search = searchTask.result
                val searchStored = search.firstPageData?.let {
                    OfficialAgreementResultStoreV2.save(app, companyId, normalizedSiret, it)
                } == true

                consultSequential(
                    context = app,
                    companyId = companyId,
                    siret = normalizedSiret,
                    candidates = search.candidates,
                    index = 0,
                    accumulated = ConsultBatch()
                ).continueWith { consultTask ->
                    val consult = if (consultTask.isSuccessful) {
                        consultTask.result ?: ConsultBatch(
                            transientFailures = 1,
                            warnings = listOf("ACCO : consultation officielle interrompue.")
                        )
                    } else {
                        ConsultBatch(
                            transientFailures = 1,
                            warnings = listOf("ACCO : consultation officielle interrompue.")
                        )
                    }

                    val existing = CompanyAgreementStoreV2.list(app, companyId)
                    val merged = mergePreservingExisting(existing, consult.verifiedAgreements)
                    val agreementStoreSaved = merged == existing || CompanyAgreementStoreV2.save(app, companyId, merged)
                    val technicalCompleted = auditCompleted(
                        searchComplete = search.complete,
                        searchStored = searchStored,
                        transientFailures = consult.transientFailures,
                        candidateStorageFailures = consult.storageFailures,
                        agreementStoreSaved = agreementStoreSaved
                    ) && consult.rejected == 0

                    val mealTrustStored = if (mealProfile != null) {
                        val status = mealProfile.professionalStatus!!.trim().uppercase()
                        val verifiedAgreementIds = consult.verifiedAgreements.mapTo(linkedSetOf()) { it.id.trim().uppercase() }
                        val completeAgreementIds = V2CompanyMealBasketAuditStateStore.completeAgreementIdsFor(
                            context = app,
                            companyId = companyId,
                            expectedSiret = normalizedSiret,
                            classification = mealProfile.classification,
                            professionalStatus = status
                        )
                        val trustedRules = V2CompanyMealBasketStore.rules(app, companyId, normalizedSiret)
                            .filter { it.agreementId in verifiedAgreementIds && it.agreementId in completeAgreementIds }
                        MealBasketAuditTrustStoreV2.markAcco(
                            context = app,
                            companyId = companyId,
                            profile = mealProfile,
                            state = if (technicalCompleted) {
                                MealBasketAuditTrustStoreV2.State.COMPLETE
                            } else {
                                MealBasketAuditTrustStoreV2.State.INCOMPLETE
                            },
                            verifiedAgreementIds = if (technicalCompleted) verifiedAgreementIds else emptySet(),
                            fingerprints = if (technicalCompleted) {
                                trustedRules.mapTo(linkedSetOf(), MealBasketAuditTrustStoreV2::accoFingerprint)
                            } else emptySet()
                        )
                    } else true
                    val completed = technicalCompleted && mealTrustStored

                    val warnings = buildList {
                        addAll(search.warnings)
                        addAll(consult.warnings)
                        if (!searchStored) add("ACCO : résultat de recherche reçu mais stockage local impossible.")
                        if (!agreementStoreSaved) add("ACCO : accords vérifiés reçus mais stockage local impossible.")
                        if (consult.rejected > 0) {
                            add("ACCO : ${consult.rejected} accord(s) candidat(s) n'ont pas pu être reliés de façon certaine au SIRET après consultation ; audit repas global incomplet.")
                        }
                        if (mealProfile != null && !mealTrustStored) {
                            add("ACCO : audit technique terminé mais paquet de confiance repas non persisté ; calcul repas bloqué.")
                        }
                        if (search.candidates.isEmpty() && search.complete) {
                            add("ACCO : recherche officielle parcourue jusqu'à son terme sans accord candidat exploitable pour ce SIRET ; cela ne constitue pas une preuve d'absence d'accord interne.")
                        }
                        if (consult.verifiedAgreements.isNotEmpty()) {
                            add("ACCO : les passages de paie génériques extraits restent des candidats à valider ; aucune valeur générique n'est appliquée automatiquement.")
                        }
                    }.distinct()

                    Summary(
                        siret = normalizedSiret,
                        pagesRead = search.pagesRead,
                        candidates = search.candidates.size,
                        agreementsConsulted = consult.consulted,
                        agreementsVerified = consult.verifiedAgreements.distinctBy { it.id }.size,
                        agreementsRejected = consult.rejected,
                        extractedCandidates = consult.extractedCandidates,
                        completed = completed,
                        warnings = warnings
                    )
                }
            }
    }

    internal fun searchBody(siret: String, pageNumber: Int, pageSize: Int = PAGE_SIZE): Map<String, Any> {
        val normalized = normalizeSiret(siret) ?: return emptyMap()
        val safePage = pageNumber.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, PAGE_SIZE)
        return mapOf(
            "fond" to "ACCO",
            "recherche" to mapOf(
                "filtres" to listOf(
                    mapOf(
                        "valeurs" to listOf(normalized),
                        "facette" to "SIRET_RAISON_SOCIALE"
                    )
                ),
                "champs" to listOf(
                    mapOf(
                        "typeChamp" to "ALL",
                        "criteres" to listOf(
                            mapOf(
                                "typeRecherche" to "EXACTE",
                                "valeur" to normalized,
                                "operateur" to "ET"
                            )
                        ),
                        "operateur" to "ET"
                    )
                ),
                "pageNumber" to safePage,
                "pageSize" to safeSize,
                "operateur" to "ET",
                "sort" to "DATE_DESC",
                "fromAdvancedRecherche" to false,
                "secondSort" to "ID",
                "typePagination" to "DEFAUT"
            )
        )
    }

    private fun fetchPages(
        siret: String,
        pageNumber: Int = 1,
        accumulated: List<CompanyAgreementStoreV2.Agreement> = emptyList(),
        pagesRead: Int = 0,
        firstPageData: Any? = null,
        warnings: List<String> = emptyList()
    ): Task<SearchBatch> {
        return requestWithRetry("/search", searchBody(siret, pageNumber))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            candidates = accumulated,
                            pagesRead = pagesRead,
                            complete = false,
                            firstPageData = firstPageData,
                            warnings = warnings +
                                "ACCO : page $pageNumber de la recherche officielle indisponible après deux tentatives."
                        )
                    )
                }

                val data = task.result?.data
                val parsed = OfficialAgreementSearchParserV2.parseCandidates(data)
                val merged = (accumulated + parsed).distinctBy { it.id }
                val rawCount = rawResultCount(data)
                val newPagesRead = pagesRead + 1
                val initialData = firstPageData ?: data
                val noProgress = rawCount > 0 && merged.size == accumulated.size

                if (rawCount < PAGE_SIZE) {
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(merged, newPagesRead, true, initialData, warnings)
                    )
                }
                if (noProgress) {
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            merged,
                            newPagesRead,
                            false,
                            initialData,
                            warnings +
                                "ACCO : page pleine sans nouvel identifiant ACCOTEXT ; arrêt prudent pour éviter une boucle de pagination."
                        )
                    )
                }

                fetchPages(
                    siret = siret,
                    pageNumber = pageNumber + 1,
                    accumulated = merged,
                    pagesRead = newPagesRead,
                    firstPageData = initialData,
                    warnings = warnings
                )
            }
    }

    private fun consultSequential(
        context: Context,
        companyId: String,
        siret: String,
        candidates: List<CompanyAgreementStoreV2.Agreement>,
        index: Int,
        accumulated: ConsultBatch
    ): Task<ConsultBatch> {
        if (index >= candidates.size) return Tasks.forResult(accumulated)
        val candidate = candidates[index]

        return requestWithRetry("/consult/acco", mapOf("id" to candidate.id))
            .continueWithTask { task ->
                val next = if (!task.isSuccessful) {
                    accumulated.copy(
                        transientFailures = accumulated.transientFailures + 1,
                        warnings = accumulated.warnings +
                            "ACCO : ${candidate.id} n'a pas pu être consulté après deux tentatives ; nouvelle analyse nécessaire."
                    )
                } else {
                    val officialContent = OfficialAgreementContentParserV2.extractVerified(task.result?.data, siret)
                    if (officialContent == null) {
                        accumulated.copy(
                            consulted = accumulated.consulted + 1,
                            rejected = accumulated.rejected + 1
                        )
                    } else {
                        val ingestion = CompanyAgreementIngestionV2.ingest(
                            context = context,
                            companyId = companyId,
                            agreementId = candidate.id,
                            officialText = officialContent.text
                        )
                        val provident = CompanyAgreementProvidentContributionIngestionV2.ingestVerified(
                            context = context,
                            companyId = companyId,
                            agreementId = candidate.id,
                            verifiedContent = officialContent
                        )
                        val benefits = CompanyAgreementProvidentBenefitIngestionV2.ingestVerified(
                            context = context,
                            companyId = companyId,
                            agreementId = candidate.id,
                            verifiedContent = officialContent
                        )
                        val meals = CompanyAgreementMealBasketIngestionV2.ingestVerified(
                            context = context,
                            companyId = companyId,
                            agreementId = candidate.id,
                            verifiedContent = officialContent
                        )
                        val localWarnings = buildList {
                            if (!ingestion.saved) {
                                add("ACCO : candidats extraits de ${candidate.id} mais stockage local impossible.")
                            }
                            when {
                                provident.storageFailure ->
                                    add("ACCO : cotisation de prévoyance structurée dans ${candidate.id} mais stockage local dédié impossible.")
                                provident.detected && !provident.structured -> {
                                    add("ACCO : cotisation de prévoyance détectée dans ${candidate.id}, mais règle exacte non démontrée ; aucun taux d'entreprise n'est retenu.")
                                    addAll(provident.warnings.take(2))
                                }
                                provident.saved ->
                                    add("ACCO : cotisation de prévoyance de ${candidate.id} structurée et stockée localement ; son application reste soumise à l'arbitrage L2253-1.")
                            }
                            when {
                                benefits.storageFailure -> {
                                    add("ACCO : garanties de prévoyance structurées dans ${candidate.id}, mais stockage local dédié incomplet.")
                                    addAll(benefits.warnings.take(2))
                                }
                                benefits.detected && !benefits.structured -> {
                                    add("ACCO : garanties de prévoyance détectées dans ${candidate.id}, mais aucune prestation complète n'est démontrée.")
                                    addAll(benefits.warnings.take(2))
                                }
                                benefits.structured && !benefits.packageComplete -> {
                                    add("ACCO : garanties structurées partiellement dans ${candidate.id}, mais paquet incomplet ; aucune équivalence L2253-1 n'est retenue.")
                                    addAll(benefits.warnings.take(2))
                                }
                                benefits.packageComplete ->
                                    add("ACCO : paquet de garanties de prévoyance de ${candidate.id} structuré et stocké localement ; l'équivalence reste à comparer au paquet KALI.")
                            }
                            when {
                                meals.storageFailure -> {
                                    add("ACCO : panier/indemnité repas juridiquement structuré dans ${candidate.id}, mais stockage local dédié incomplet.")
                                    addAll(meals.warnings.take(2))
                                }
                                meals.detected && !meals.legalPackageComplete -> {
                                    add("ACCO : panier/indemnité repas détecté dans ${candidate.id}, mais paquet juridique incomplet ; aucune règle partielle n'alimente le calcul.")
                                    addAll(meals.warnings.take(2))
                                }
                                meals.packageComplete ->
                                    add("ACCO : panier/indemnité repas de ${candidate.id} structuré et stocké localement ; l'arbitrage L2253-3 reste à effectuer par objet.")
                            }
                        }
                        accumulated.copy(
                            verifiedAgreements = accumulated.verifiedAgreements + candidate.copy(
                                status = CompanyAgreementStoreV2.Status.UNKNOWN,
                                notes = "SIRET et contenu vérifiés dans la consultation officielle. Les règles génériques extraites restent à valider ; prévoyance et paniers/indemnités repas ne sont structurés que par leurs chaînes fail-closed dédiées."
                            ),
                            consulted = accumulated.consulted + 1,
                            extractedCandidates = accumulated.extractedCandidates + ingestion.extractedCount,
                            storageFailures = accumulated.storageFailures +
                                (if (ingestion.saved) 0 else 1) +
                                (if (provident.storageFailure) 1 else 0) +
                                (if (benefits.storageFailure) 1 else 0) +
                                (if (meals.storageFailure) 1 else 0),
                            warnings = accumulated.warnings + localWarnings
                        )
                    }
                }

                consultSequential(context, companyId, siret, candidates, index + 1, next)
            }
    }

    private fun requestWithRetry(
        path: String,
        body: Map<String, Any?>
    ): Task<HttpsCallableResult> =
        LegifranceFunctionClientV2.request(path, body)
            .continueWithTask { first ->
                if (first.isSuccessful) {
                    Tasks.forResult(first.result)
                } else {
                    LegifranceFunctionClientV2.request(path, body)
                }
            }

    internal fun rawResultCount(data: Any?): Int =
        (((data as? Map<*, *>)?.get("results")) as? List<*>)?.size ?: 0

    internal fun mergePreservingExisting(
        existing: List<CompanyAgreementStoreV2.Agreement>,
        verified: List<CompanyAgreementStoreV2.Agreement>
    ): List<CompanyAgreementStoreV2.Agreement> {
        val existingIds = existing.map { it.id }.toSet()
        return existing + verified.distinctBy { it.id }.filterNot { it.id in existingIds }
    }

    internal fun auditCompleted(
        searchComplete: Boolean,
        searchStored: Boolean,
        transientFailures: Int,
        candidateStorageFailures: Int,
        agreementStoreSaved: Boolean
    ): Boolean = searchComplete && searchStored && transientFailures == 0 &&
        candidateStorageFailures == 0 && agreementStoreSaved

    private fun normalizeSiret(value: String): String? =
        value.filter(Char::isDigit).takeIf { it.length == 14 }
}
