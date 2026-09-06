package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions

/** Lecture seule du plan de réanalyse préparé par Firebase. */
object LegalReanalysisPlanClientV2 {
    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    data class Job(
        val jobId: String,
        val revisionKey: String,
        val sourceFamily: String,
        val scopeType: String,
        val scopeValue: String,
        val matterHints: Set<String>,
        val targetSourceFamilies: Set<String>,
        val completedSourceFamilies: Set<String>,
        val analysisKinds: Set<String>,
        val lastQueuedAtMs: Long,
        val revalidationCompletedAtMs: Long
    )

    data class Plan(
        val schemaVersion: Int,
        val generatedAtMs: Long,
        val jobs: List<Job>
    )

    fun fetch(idcc: String, siret: String): Task<Plan> {
        val payload = hashMapOf<String, Any?>(
            "idcc" to idcc.filter(Char::isDigit),
            "siret" to siret.filter(Char::isDigit)
        )
        return functions
            .getHttpsCallable("legalReanalysisPlan")
            .call(payload)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    throw task.exception ?: IllegalStateException("Plan juridique indisponible")
                }
                parse(task.result?.data)
            }
    }

    internal fun parse(data: Any?): Plan {
        val root = data as? Map<*, *> ?: return Plan(0, 0L, emptyList())
        val jobs = (root["jobs"] as? List<*>)
            .orEmpty()
            .mapNotNull(::parseJob)
            .distinctBy { it.revisionKey }
        return Plan(
            schemaVersion = number(root["schemaVersion"]).toInt(),
            generatedAtMs = number(root["generatedAtMs"]),
            jobs = jobs
        )
    }

    private fun parseJob(value: Any?): Job? {
        val map = value as? Map<*, *> ?: return null
        val jobId = text(map["jobId"])
        val revisionKey = text(map["revisionKey"])
        val sourceFamily = text(map["sourceFamily"]).uppercase()
        if (jobId.isBlank() || revisionKey.isBlank() || sourceFamily.isBlank()) return null
        return Job(
            jobId = jobId,
            revisionKey = revisionKey,
            sourceFamily = sourceFamily,
            scopeType = text(map["scopeType"]).uppercase(),
            scopeValue = text(map["scopeValue"]),
            matterHints = strings(map["matterHints"]),
            targetSourceFamilies = strings(map["targetSourceFamilies"]),
            completedSourceFamilies = strings(map["completedSourceFamilies"]),
            analysisKinds = strings(map["analysisKinds"]),
            lastQueuedAtMs = number(map["lastQueuedAtMs"]),
            revalidationCompletedAtMs = number(map["revalidationCompletedAtMs"])
        )
    }

    private fun strings(value: Any?): Set<String> = (value as? List<*>)
        .orEmpty()
        .map { text(it).uppercase() }
        .filter { it.isNotBlank() }
        .toSet()

    private fun text(value: Any?): String = value?.toString()?.trim().orEmpty()

    private fun number(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }
}