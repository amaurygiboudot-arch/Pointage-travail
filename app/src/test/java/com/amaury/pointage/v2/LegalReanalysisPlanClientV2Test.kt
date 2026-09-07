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
                        "matterHints" to listOf("overtime", "night_work", "saturday", "sunday", "public_holidays", "minimum_pay", "seniority", "sickness_maintenance"),
                        "targetSourceFamilies" to listOf("kali"),
                        "completedSourceFamilies" to listOf("kali"),
                        "analysisKinds" to listOf("kali_overtime", "kali_night", "kali_saturday", "kali_sunday", "kali_public_holidays", "kali_minimum_pay", "kali_seniority", "kali_sickness_maintenance"),
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
        assertEquals(
            setOf("OVERTIME", "NIGHT_WORK", "SATURDAY", "SUNDAY", "PUBLIC_HOLIDAYS", "MINIMUM_PAY", "SENIORITY", "SICKNESS_MAINTENANCE"),
            job.matterHints
        )
        assertEquals(
            setOf("KALI_OVERTIME", "KALI_NIGHT", "KALI_SATURDAY", "KALI_SUNDAY", "KALI_PUBLIC_HOLIDAYS", "KALI_MINIMUM_PAY", "KALI_SENIORITY", "KALI_SICKNESS_MAINTENANCE"),
            job.analysisKinds
        )
    }

    @Test
    fun `coordinateur autorise maladie mais garde prevoyance bloquee`() {
        fun job(id: String, kinds: Set<String>, sourceFamily: String = "BOCC") = LegalReanalysisPlanClientV2.Job(
            jobId = id,
            revisionKey = "$id:1",
            sourceFamily = sourceFamily,
            scopeType = if (sourceFamily == "ACCO") "SIRET" else "IDCC",
            scopeValue = if (sourceFamily == "ACCO") "12345678901234" else "0292",
            matterHints = emptySet(),
            targetSourceFamilies = emptySet(),
            completedSourceFamilies = emptySet(),
            analysisKinds = kinds,
            lastQueuedAtMs = 1L,
            revalidationCompletedAtMs = 2L
        )

        val (kali, legi, acco) = LegalAutoUpdateCoordinatorV2.selectKinds(
            listOf(
                job("k", setOf("KALI_OVERTIME", "KALI_NIGHT", "KALI_SATURDAY", "KALI_SUNDAY", "KALI_PUBLIC_HOLIDAYS", "KALI_MINIMUM_PAY", "KALI_SENIORITY", "KALI_SICKNESS_MAINTENANCE")),
                job("blocked", setOf("KALI_PROVIDENT")),
                job("l", setOf("LEGI_ALL"), "JORF"),
                job("a", setOf("ACCO_EXTRACT_CANDIDATES"), "ACCO")
            )
        )

        assertEquals(listOf("k"), kali.map { it.jobId })
        assertEquals(listOf("l"), legi.map { it.jobId })
        assertEquals(listOf("a"), acco.map { it.jobId })
        assertTrue(kali.single().analysisKinds.contains("KALI_NIGHT"))
        assertTrue(kali.single().analysisKinds.contains("KALI_MINIMUM_PAY"))
        assertTrue(kali.single().analysisKinds.contains("KALI_SENIORITY"))
        assertTrue(kali.single().analysisKinds.contains("KALI_SICKNESS_MAINTENANCE"))
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
