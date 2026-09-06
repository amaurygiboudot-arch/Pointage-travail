package com.amaury.pointage.v2

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/**
 * Réanalyse officielle ACCO d'une entreprise : recherche par SIRET, consultation exacte,
 * puis extraction locale de candidats de paie. Aucune règle ni valeur n'est validée ici.
 */
object CompanyAgreementOfficialAuditV2 {
    private const val PAGE_SIZE = 25
    private const val MAX_PAGES = 20

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
                    val completed = auditCompleted(
                        searchComplete = search.complete,
                        searchStored = searchStored,
                        transientFailures = consult.transientFailures,
                        candidateStorageFailures = consult.storageFailures,
                        agreementStoreSaved = agreementStoreSaved
                    )
                    val warnings = buildList {
                        addAll(search.warnings)
                        addAll(consult.warnings)
                        if (!searchStored) add("ACCO : résultat de recherche reçu mais stockage local impossible.")
                        if (!agreementStoreSaved) add("ACCO : accords vérifiés reçus mais stockage local impossible.")
                        if (search.candidates.isEmpty() && search.complete) {
                            add("ACCO : aucun accord candidat exploitable trouvé pour ce SIRET ; cela ne constitue pas une preuve d'absence d'accord interne.")
                        }
                        if (consult.verifiedAgreements.isNotEmpty()) {
                            add("ACCO : les passages de paie extraits restent des candidats à valider ; aucune valeur n'est appliquée automatiquement.")
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
        val safePage = pageNumber.coerceIn(1, MAX_PAGES)
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
        return LegifranceFunctionClientV2.request("/search", searchBody(siret, pageNumber))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            candidates = accumulated,
                            pagesRead = pagesRead,
                            complete = false,
                            firstPageData = firstPageData,
                            warnings = warnings + "ACCO : page $pageNumber de la recherche officielle indisponible."
                        )
                    )
                }

                val data = task.result?.data
                val parsed = OfficialAgreementSearchParserV2.parseCandidates(data)
                val merged = (accumulated + parsed).distinctBy { it.id }
                val rawCount = rawResultCount(data)
                val newPagesRead = pagesRead + 1
                val initialData = firstPageData ?: data

                if (rawCount < PAGE_SIZE) {
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(merged, newPagesRead, true, initialData, warnings)
                    )
                }
                if (pageNumber >= MAX_PAGES) {
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            merged,
                            newPagesRead,
                            false,
                            initialData,
                            warnings + "ACCO : limite de pagination atteinte ; la recherche reste incomplète."
                        )
                    )
                }
                if (merged.size == accumulated.size) {
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            merged,
                            newPagesRead,
                            false,
                            initialData,
                            warnings + "ACCO : page pleine sans nouvel identifiant ACCOTEXT ; arrêt prudent de la pagination."
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

        return LegifranceFunctionClientV2.request("/consult/acco", mapOf("id" to candidate.id))
            .continueWithTask { task ->
                val next = if (!task.isSuccessful) {
                    accumulated.copy(
                        transientFailures = accumulated.transientFailures + 1,
                        warnings = accumulated.warnings + "ACCO : ${candidate.id} n'a pas pu être consulté ; nouvelle tentative nécessaire."
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
                        accumulated.copy(
                            verifiedAgreements = accumulated.verifiedAgreements + candidate.copy(
                                status = CompanyAgreementStoreV2.Status.UNKNOWN,
                                notes = "SIRET et contenu vérifiés dans la consultation officielle. Les règles extraites et leur période restent à valider."
                            ),
                            consulted = accumulated.consulted + 1,
                            extractedCandidates = accumulated.extractedCandidates + ingestion.extractedCount,
                            storageFailures = accumulated.storageFailures + if (ingestion.saved) 0 else 1,
                            warnings = if (ingestion.saved) accumulated.warnings else accumulated.warnings +
                                "ACCO : candidats extraits de ${candidate.id} mais stockage local impossible."
                        )
                    }
                }

                consultSequential(context, companyId, siret, candidates, index + 1, next)
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
