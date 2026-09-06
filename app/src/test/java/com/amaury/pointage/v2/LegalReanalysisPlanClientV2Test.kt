package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalReanalysisPlanClientV2Test {
    @Test
    fun `parse uniquement le plan de reanalyse sur et normalise les familles`() {
        val plan = LegalReanalysisPlanClientV2.parse(
            mapOf(
                "schemaVersion" to 1,
                "generatedAtMs" to 1234L,
                "jobs" to listOf(
                    mapOf(
                        "jobId" to "job_1",
                        "revisionKey" to "job_1:900",
                        "sourceFamily" to "bocc",
                        "scopeType" to "idcc",
                        "scopeValue" to "0292",
                        "matterHints" to listOf("overtime", "night_work"),
                        "targetSourceFamilies" to listOf("kali"),
                        "completedSourceFamilies" to listOf("kali"),
                        "analysisKinds" to listOf("kali_overtime"),
                        "lastQueuedAtMs" to 900L,
                        "revalidationCompletedAtMs" to 1000L,
                        "payloadJson" to "ne-doit-pas-etre-utilise"
                    )
                )
            )
        )

        assertEquals(1, plan.schemaVersion)
        assertEquals(1234L, plan.generatedAtMs)
        assertEquals(1, plan.jobs.size)
        val job = plan.jobs.single()
        assertEquals("BOCC", job.sourceFamily)
        assertEquals("IDCC", job.scopeType)
        assertEquals(setOf("OVERTIME", "NIGHT_WORK"), job.matterHints)
        assertEquals(setOf("KALI_OVERTIME"), job.analysisKinds)
    }

    @Test
    fun `coordinateur separe KALI LEGI et ACCO sans auto valider ACCO`() {
        fun job(id: String, kind: String) = LegalReanalysisPlanClientV2.Job(
            jobId = id,
            revisionKey = "$id:1",
            sourceFamily = when (kind) {
                "KALI_OVERTIME" -> "BOCC"
                "LEGI_ALL" -> "JORF"
                else -> "ACCO"
            },
            scopeType = if (kind.startsWith("ACCO")) "SIRET" else "IDCC",
            scopeValue = if (kind.startsWith("ACCO")) "12345678901234" else "0292",
            matterHints = emptySet(),
            targetSourceFamilies = emptySet(),
            completedSourceFamilies = emptySet(),
            analysisKinds = setOf(kind),
            lastQueuedAtMs = 1L,
            revalidationCompletedAtMs = 2L
        )

        val (kali, legi, acco) = LegalAutoUpdateCoordinatorV2.selectKinds(
            listOf(job("k", "KALI_OVERTIME"), job("l", "LEGI_ALL"), job("a", "ACCO_EXTRACT_CANDIDATES"))
        )

        assertEquals(listOf("k"), kali.map { it.jobId })
        assertEquals(listOf("l"), legi.map { it.jobId })
        assertEquals(listOf("a"), acco.map { it.jobId })
        assertTrue(acco.single().analysisKinds.contains("ACCO_EXTRACT_CANDIDATES"))
    }

    @Test
    fun `coordinateur reconnait encore temporairement un ancien job ACCO`() {
        val legacy = LegalReanalysisPlanClientV2.Job(
            jobId = "legacy",
            revisionKey = "legacy:1",
            sourceFamily = "ACCO",
            scopeType = "SIRET",
            scopeValue = "12345678901234",
            matterHints = emptySet(),
            targetSourceFamilies = emptySet(),
            completedSourceFamilies = emptySet(),
            analysisKinds = setOf("ACCO_PENDING_PARSER"),
            lastQueuedAtMs = 1L,
            revalidationCompletedAtMs = 2L
        )

        val (_, _, acco) = LegalAutoUpdateCoordinatorV2.selectKinds(listOf(legacy))
        assertEquals(listOf("legacy"), acco.map { it.jobId })
    }
}
