package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/** Vérifie les publications BOCC retenues via les métadonnées PDF officielles Légifrance. */
object OfficialBoccVerifierV2 {
    data class VerifiedReference(
        val title: String,
        val fileName: String,
        val pathToFile: String,
        val publicationDate: String?,
        val textDate: String?,
        val bulletinNumber: String?,
        val idccs: List<String>,
        val checkedAtMs: Long
    )

    data class Result(
        val verified: List<VerifiedReference>,
        val rejectedOrSkippedCount: Int,
        val warnings: List<String> = emptyList()
    )

    fun verify(
        candidates: List<OfficialBoccSourceV2.Candidate>,
        maxCandidates: Int = 12
    ): Task<Result> {
        val distinct = candidates.distinctBy { it.fileName }
        val limited = distinct.take(maxCandidates.coerceIn(1, 20))
        if (limited.isEmpty()) return Tasks.forResult(Result(emptyList(), distinct.size))

        val checks = limited.map { candidate ->
            LegifranceFunctionClientV2.request(
                "/consult/getBoccTextPdfMetadata",
                mapOf("id" to candidate.fileName, "forGlobalBocc" to false)
            ).continueWith { task ->
                if (!task.isSuccessful) return@continueWith null
                val metadata = OfficialBoccSourceV2.parsePdfMetadata(task.result?.data)
                    ?: return@continueWith null
                validate(candidate, metadata, System.currentTimeMillis())
            }
        }

        return Tasks.whenAllComplete(checks).continueWith {
            val verified = checks.mapNotNull { check ->
                if (check.isSuccessful) check.result else null
            }.distinctBy { it.fileName }
            val skippedByLimit = (distinct.size - limited.size).coerceAtLeast(0)
            val rejectedInChecks = limited.size - verified.size
            Result(
                verified = verified,
                rejectedOrSkippedCount = skippedByLimit + rejectedInChecks,
                warnings = buildList {
                    if (skippedByLimit > 0) add("BOCC : $skippedByLimit publication(s) non consultée(s) car la vérification est bornée.")
                    if (rejectedInChecks > 0) add("BOCC : $rejectedInChecks référence(s) n'ont pas été confirmées par les métadonnées PDF officielles.")
                }
            )
        }
    }

    internal fun validate(
        candidate: OfficialBoccSourceV2.Candidate,
        metadata: OfficialBoccSourceV2.PdfMetadata,
        checkedAtMs: Long
    ): VerifiedReference? {
        if (!metadata.fileName.equals(candidate.fileName, ignoreCase = false)) return null
        if (metadata.pathToFile.isBlank()) return null
        if (checkedAtMs <= 0L) return null
        return VerifiedReference(
            title = candidate.title,
            fileName = candidate.fileName,
            pathToFile = metadata.pathToFile,
            publicationDate = metadata.publicationDate ?: candidate.publicationDate,
            textDate = candidate.textDate,
            bulletinNumber = metadata.bulletinNumber ?: candidate.bulletinNumber,
            idccs = candidate.idccs,
            checkedAtMs = checkedAtMs
        )
    }
}
