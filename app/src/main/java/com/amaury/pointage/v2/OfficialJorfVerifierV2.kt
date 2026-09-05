package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Vérifie les textes JORF retenus en consultant leur contenu officiel Légifrance. */
object OfficialJorfVerifierV2 {
    data class VerifiedReference(
        val textCid: String,
        val title: String,
        val nature: String?,
        val legalState: String?,
        val nor: String?,
        val publicationDate: String,
        val publicationNumber: String?,
        val containerId: String,
        val checkedAtMs: Long
    )

    data class Result(
        val verified: List<VerifiedReference>,
        val rejectedOrSkippedCount: Int,
        val rejectionReasons: List<String> = emptyList()
    )

    fun verify(
        candidates: List<OfficialJorfSourceV2.Candidate>,
        referenceAtMs: Long,
        maxCandidates: Int = 12
    ): Task<Result> {
        require(referenceAtMs > 0L) { "Date JORF invalide" }
        val distinct = candidates.distinctBy { it.textCid }
        val limited = distinct.take(maxCandidates.coerceIn(1, 20))
        if (limited.isEmpty()) return Tasks.forResult(Result(emptyList(), distinct.size))

        data class Checked(val verified: VerifiedReference?, val reason: String?)

        val checks = limited.map { candidate ->
            LegifranceFunctionClientV2.request(
                "/consult/jorf",
                OfficialJorfSourceV2.documentBody(candidate.textCid)
            ).continueWith { task ->
                if (!task.isSuccessful) {
                    return@continueWith Checked(null, "${candidate.textCid} : consultation impossible")
                }
                val document = OfficialJorfSourceV2.parseDocument(task.result?.data)
                    ?: return@continueWith Checked(null, "${candidate.textCid} : réponse illisible")
                val verified = validate(candidate, document, referenceAtMs, System.currentTimeMillis())
                if (verified != null) Checked(verified, null)
                else Checked(null, rejectionReason(candidate, document, referenceAtMs))
            }
        }

        return Tasks.whenAllComplete(checks).continueWith {
            val checked = checks.mapNotNull { task -> if (task.isSuccessful) task.result else null }
            val verified = checked.mapNotNull { it.verified }.distinctBy { it.textCid }
            val reasons = checked.mapNotNull { it.reason }.distinct().take(12)
            val skippedByLimit = (distinct.size - limited.size).coerceAtLeast(0)
            Result(
                verified = verified,
                rejectedOrSkippedCount = skippedByLimit + (limited.size - verified.size),
                rejectionReasons = buildList {
                    addAll(reasons)
                    if (skippedByLimit > 0) add("$skippedByLimit texte(s) non consulté(s) car la vérification est bornée")
                }
            )
        }
    }

    internal fun validate(
        candidate: OfficialJorfSourceV2.Candidate,
        document: OfficialJorfSourceV2.Document,
        referenceAtMs: Long,
        checkedAtMs: Long
    ): VerifiedReference? {
        if (checkedAtMs <= 0L || referenceAtMs <= 0L) return null
        val matchesId = document.textCid == candidate.textCid || document.technicalId == candidate.textCid
        if (!matchesId) return null
        if (!document.hasContent) return null
        val publication = document.publicationDate ?: candidate.publicationDate ?: return null
        val publicationAtMs = parseDateAtMs(publication) ?: return null
        val endOfReferenceDay = Instant.ofEpochMilli(referenceAtMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() - 1L
        if (publicationAtMs > endOfReferenceDay) return null

        return VerifiedReference(
            textCid = candidate.textCid,
            title = document.title.ifBlank { candidate.title },
            nature = document.nature ?: candidate.nature,
            legalState = document.legalState ?: candidate.legalState,
            nor = document.nor,
            publicationDate = publication,
            publicationNumber = document.publicationNumber,
            containerId = candidate.containerId,
            checkedAtMs = checkedAtMs
        )
    }

    private fun rejectionReason(
        candidate: OfficialJorfSourceV2.Candidate,
        document: OfficialJorfSourceV2.Document,
        referenceAtMs: Long
    ): String {
        val matchesId = document.textCid == candidate.textCid || document.technicalId == candidate.textCid
        if (!matchesId) return "${candidate.textCid} : identifiant JORF différent"
        if (!document.hasContent) return "${candidate.textCid} : contenu officiel vide"
        val publication = document.publicationDate ?: candidate.publicationDate
            ?: return "${candidate.textCid} : date de publication absente"
        val publicationAtMs = parseDateAtMs(publication)
            ?: return "${candidate.textCid} : date de publication invalide"
        val endOfReferenceDay = Instant.ofEpochMilli(referenceAtMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() - 1L
        if (publicationAtMs > endOfReferenceDay) return "${candidate.textCid} : publication postérieure à la paie"
        return "${candidate.textCid} : document rejeté"
    }

    internal fun parseDateAtMs(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        value.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching {
            LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }
}
