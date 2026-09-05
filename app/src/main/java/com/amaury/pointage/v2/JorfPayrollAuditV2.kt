package com.amaury.pointage.v2

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Chaîne JORF : derniers JO -> sommaires -> textes paie -> consultation officielle -> stockage. */
object JorfPayrollAuditV2 {
    private const val LAST_JO_COUNT = 100
    private const val MAX_CONTAINERS = 25
    private const val MAX_RELEVANT_CANDIDATES = 20

    data class Summary(
        val referenceAtMs: Long,
        val containersAvailable: Int,
        val containersInspected: Int,
        val textsSeen: Int,
        val payrollRelevant: Int,
        val verified: Int,
        val rejectedOrSkipped: Int,
        val saved: Boolean,
        val warnings: List<String> = emptyList()
    )

    private data class ScanBatch(
        val inspected: Int,
        val textsSeen: Int,
        val candidates: List<OfficialJorfSourceV2.Candidate>,
        val warnings: List<String>
    )

    fun audit(context: Context, atMs: Long): Task<Summary> {
        require(atMs > 0L) { "Date JORF invalide" }
        val zone = ZoneId.systemDefault()
        val referenceDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val publicationEnd = minOf(referenceDate, today)
        val app = context.applicationContext

        return LegifranceFunctionClientV2.request(
            "/consult/lastNJo",
            OfficialJorfSourceV2.lastJoBody(LAST_JO_COUNT)
        ).continueWithTask { lastTask ->
            if (!lastTask.isSuccessful) {
                return@continueWithTask Tasks.forResult(
                    Summary(
                        referenceAtMs = atMs,
                        containersAvailable = 0,
                        containersInspected = 0,
                        textsSeen = 0,
                        payrollRelevant = 0,
                        verified = 0,
                        rejectedOrSkipped = 0,
                        saved = false,
                        warnings = listOf("JORF : ${lastTask.exception?.message ?: "liste des journaux officiels inaccessible"}")
                    )
                )
            }

            val allContainers = OfficialJorfSourceV2.parseLastContainers(lastTask.result?.data)
            val usableContainers = allContainers.filter { container ->
                val raw = container.publicationDate ?: return@filter true
                val at = OfficialJorfVerifierV2.parseDateAtMs(raw) ?: return@filter true
                val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                !date.isAfter(publicationEnd)
            }

            if (usableContainers.isEmpty()) {
                val saved = JorfPayrollSourceStoreV2.replaceSnapshot(app, atMs, emptyList())
                return@continueWithTask Tasks.forResult(
                    Summary(
                        referenceAtMs = atMs,
                        containersAvailable = allContainers.size,
                        containersInspected = 0,
                        textsSeen = 0,
                        payrollRelevant = 0,
                        verified = 0,
                        rejectedOrSkipped = 0,
                        saved = saved,
                        warnings = listOf(
                            if (allContainers.isEmpty())
                                "JORF : aucun journal officiel récent n'a été retourné."
                            else
                                "JORF : les derniers journaux disponibles sont postérieurs à la date de paie contrôlée."
                        )
                    )
                )
            }

            scanContainers(usableContainers.take(MAX_CONTAINERS)).continueWithTask { scanTask ->
                if (!scanTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            atMs, allContainers.size, 0, 0, 0, 0, 0, false,
                            listOf("JORF : lecture des sommaires impossible.")
                        )
                    )
                }
                val batch = scanTask.result
                if (batch.candidates.isEmpty()) {
                    val saved = JorfPayrollSourceStoreV2.replaceSnapshot(app, atMs, emptyList())
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            referenceAtMs = atMs,
                            containersAvailable = allContainers.size,
                            containersInspected = batch.inspected,
                            textsSeen = batch.textsSeen,
                            payrollRelevant = 0,
                            verified = 0,
                            rejectedOrSkipped = 0,
                            saved = saved,
                            warnings = batch.warnings + "JORF : aucun titre récent ne touche directement les thèmes de paie suivis."
                        )
                    )
                }

                OfficialJorfVerifierV2.verify(batch.candidates, atMs).continueWith { verifyTask ->
                    if (!verifyTask.isSuccessful) {
                        return@continueWith Summary(
                            atMs,
                            allContainers.size,
                            batch.inspected,
                            batch.textsSeen,
                            batch.candidates.size,
                            0,
                            batch.candidates.size,
                            false,
                            batch.warnings + "JORF : consultation des textes officiels impossible."
                        )
                    }
                    val result = verifyTask.result
                    val saved = JorfPayrollSourceStoreV2.replaceSnapshot(app, atMs, result.verified)
                    Summary(
                        referenceAtMs = atMs,
                        containersAvailable = allContainers.size,
                        containersInspected = batch.inspected,
                        textsSeen = batch.textsSeen,
                        payrollRelevant = batch.candidates.size,
                        verified = result.verified.size,
                        rejectedOrSkipped = result.rejectedOrSkippedCount,
                        saved = saved,
                        warnings = buildList {
                            addAll(batch.warnings)
                            addAll(result.rejectionReasons)
                            if (result.verified.isEmpty()) add("JORF : aucun texte paie n'a été confirmé après consultation officielle.")
                            if (!saved) add("JORF : résultat vérifié mais stockage local impossible.")
                            if (usableContainers.size > MAX_CONTAINERS) {
                                add("JORF : lecture bornée aux $MAX_CONTAINERS derniers journaux compatibles avec la date de paie.")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun scanContainers(
        containers: List<OfficialJorfSourceV2.Container>,
        index: Int = 0,
        inspected: Int = 0,
        textsSeen: Int = 0,
        accumulated: List<OfficialJorfSourceV2.Candidate> = emptyList(),
        warnings: List<String> = emptyList()
    ): Task<ScanBatch> {
        if (index >= containers.size || accumulated.size >= MAX_RELEVANT_CANDIDATES) {
            return Tasks.forResult(
                ScanBatch(
                    inspected = inspected,
                    textsSeen = textsSeen,
                    candidates = accumulated.distinctBy { it.textCid }.take(MAX_RELEVANT_CANDIDATES),
                    warnings = warnings
                )
            )
        }

        val container = containers[index]
        return LegifranceFunctionClientV2.request(
            "/consult/jorfCont",
            OfficialJorfSourceV2.containerBody(container.containerId)
        ).continueWithTask { task ->
            if (!task.isSuccessful) {
                return@continueWithTask scanContainers(
                    containers = containers,
                    index = index + 1,
                    inspected = inspected + 1,
                    textsSeen = textsSeen,
                    accumulated = accumulated,
                    warnings = warnings + "JORF : sommaire ${container.containerId} inaccessible."
                )
            }
            val allTexts = OfficialJorfSourceV2.parseContainerCandidates(
                task.result?.data,
                fallbackContainerId = container.containerId,
                fallbackPublicationDate = container.publicationDate
            )
            val relevant = allTexts.filter(OfficialJorfSourceV2::isPayrollRelevant)
            scanContainers(
                containers = containers,
                index = index + 1,
                inspected = inspected + 1,
                textsSeen = textsSeen + allTexts.size,
                accumulated = (accumulated + relevant).distinctBy { it.textCid },
                warnings = warnings
            )
        }
    }
}
