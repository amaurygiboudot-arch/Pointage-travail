package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPauseQualificationV2Test {
    @Test
    fun `une pause sans statut paye est refusee`() {
        assertNull(
            ManualPauseQualificationV2.qualify(
                listOf(ManualPauseDraftV2(10_000L, 20_000L, null))
            )
        )
    }

    @Test
    fun `les statuts paye et non paye sont preserves`() {
        val result = ManualPauseQualificationV2.qualify(
            listOf(
                ManualPauseDraftV2(30_000L, 40_000L, false),
                ManualPauseDraftV2(10_000L, 20_000L, true)
            )
        )

        requireNotNull(result)
        assertEquals(2, result.size)
        assertEquals(QualifiedManualPauseV2(10_000L, 20_000L, true), result[0])
        assertEquals(QualifiedManualPauseV2(30_000L, 40_000L, false), result[1])
    }

    @Test
    fun `des pauses qui se chevauchent sont refusees`() {
        assertNull(
            ManualPauseQualificationV2.qualify(
                listOf(
                    ManualPauseDraftV2(10_000L, 20_000L, true),
                    ManualPauseDraftV2(19_000L, 30_000L, false)
                )
            )
        )
    }

    @Test
    fun `deux pauses contigues restent valides`() {
        val result = ManualPauseQualificationV2.qualify(
            listOf(
                ManualPauseDraftV2(10_000L, 20_000L, true),
                ManualPauseDraftV2(20_000L, 30_000L, false)
            )
        )

        requireNotNull(result)
        assertTrue(result[0].paid)
        assertTrue(!result[1].paid)
    }
}
