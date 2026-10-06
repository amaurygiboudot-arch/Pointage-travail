package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LegalAutoUpdateReferenceScopeV2Test {
    private val job = LegalReanalysisPlanClientV2.Job(
        "job", "revision", "LEGI", "GLOBAL", "code_travail", emptySet(), emptySet(),
        emptySet(), setOf("LEGI_ALL"), 1L, 1L)
    private val month = LocalDate.of(2026, 10, 31)
    private val scope = LegalAutoUpdateCoordinatorV2.AuditScope("company-a", month)

    @Test fun automaticReferenceMatchesPdfMidnightAcrossZonesAndSeasons() {
        listOf("Europe/Paris", "UTC", "America/New_York").forEach { name ->
            val zone = ZoneId.of(name)
            listOf(month, LocalDate.of(2026, 7, 31)).forEach { date ->
                val consumerReference = date.atStartOfDay(zone).toInstant().toEpochMilli()
                assertEquals(consumerReference,
                    LegalAutoUpdateCoordinatorV2.payrollReferenceAtMs(date, zone))
            }
        }
        val paris = ZoneId.of("Europe/Paris")
        val oldAutomatic = month.atTime(12, 0).atZone(paris).toInstant().toEpochMilli()
        val corrected = LegalAutoUpdateCoordinatorV2.payrollReferenceAtMs(month, paris)
        assertNotEquals(oldAutomatic / 86_400_000L, corrected / 86_400_000L)
    }

    @Test fun completionOnlyAppliesToSameCompanyDateAndRevision() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("completion-scope-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean(LegalAutoUpdateCoordinatorV2.doneKey(job, "LEGI_ALL", scope), true).commit()
        val now = 1_000_000L
        assertFalse(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job, "LEGI_ALL", now, scope))
        assertTrue(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job, "LEGI_ALL", now,
            scope.copy(referenceDate = month.plusMonths(1))))
        assertTrue(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job, "LEGI_ALL", now,
            scope.copy(companyId = "company-b")))
        assertTrue(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job.copy(revisionKey = "new"), "LEGI_ALL", now, scope))
    }

    @Test fun retryDelayIsScopedAndLegacyCompletionDoesNotBlockFreshProof() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("attempt-scope-test", Context.MODE_PRIVATE)
        val now = 30_000_000L
        prefs.edit().clear().putBoolean("done_LEGI_ALL_revision", true)
            .putLong(LegalAutoUpdateCoordinatorV2.attemptKey(job, "LEGI_ALL", scope), now - 1).commit()
        assertFalse(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job, "LEGI_ALL", now, scope))
        assertTrue(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job, "LEGI_ALL", now,
            scope.copy(referenceDate = month.minusMonths(1))))
        assertTrue(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job, "LEGI_ALL", now + 6 * 60 * 60 * 1000, scope))
        prefs.edit().remove(LegalAutoUpdateCoordinatorV2.attemptKey(job, "LEGI_ALL", scope)).commit()
        assertTrue(LegalAutoUpdateCoordinatorV2.canAttempt(prefs, job, "LEGI_ALL", now, scope))
    }
}
