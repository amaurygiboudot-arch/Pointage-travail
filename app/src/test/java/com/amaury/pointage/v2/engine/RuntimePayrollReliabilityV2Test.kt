package com.amaury.pointage.v2.engine

import android.app.Application
import com.amaury.pointage.GpsDeliveryAuthShadow
import com.google.firebase.auth.FirebaseAuth
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.amaury.pointage.v2.V2RuntimeHistoryGuardV2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class RuntimePayrollReliabilityV2Test {
    @Before
    fun initializeReliableGuestJournal() {
        // The absence engine requires a verified runtime source in the active account.
        // A plain JVM has no Firebase application and must remain fail-closed in production.
        GpsDeliveryAuthShadow.auth = Mockito.mock(FirebaseAuth::class.java)
        V2RuntimeHistoryGuardV2.publishSourceState(true)
    }

    @Test
    fun `authentification indisponible ne reutilise pas une source fiable precedente`() {
        V2RuntimeHistoryGuardV2.publishSourceState(true)
        Mockito.`when`(GpsDeliveryAuthShadow.auth.currentUser)
            .thenThrow(IllegalStateException("Authentication unavailable"))

        val result = AbsencePayrollImpactV2.forMonth(
            absences = emptyList(),
            referenceDate = LocalDate.of(2026, 9, 30),
            acceptedEmployerIds = setOf("company-a"),
            workSessions = emptyList()
        )

        assertTrue(result.requiresPayrollReview)
        assertNull(result.unpaidFullCalendarDays)
        assertTrue(result.warnings.any { it.contains("Compte de pointage indisponible") })
    }

    @After
    fun resetRuntimeState() {
        V2RuntimeHistoryGuardV2.publishSourceState(true)
    }

    @Test
    fun `historique runtime non fiable bloque le calcul absence meme sans absence`() {
        V2RuntimeHistoryGuardV2.publishSourceState(
            reliable = false,
            warnings = listOf("runtime corrompu")
        )

        val result = AbsencePayrollImpactV2.forMonth(
            absences = emptyList(),
            referenceDate = LocalDate.of(2026, 9, 30),
            acceptedEmployerIds = setOf("company-a"),
            workSessions = emptyList()
        )

        assertTrue(result.requiresPayrollReview)
        assertNull(result.unpaidFullCalendarDays)
        assertTrue(result.warnings.any { it.contains("runtime corrompu") })
        assertTrue(result.warnings.any { it.contains("Pointages V2") })
    }

    @Test
    fun `historique runtime fiable ne transforme pas un mois vide en anomalie`() {
        V2RuntimeHistoryGuardV2.publishSourceState(true)

        val result = AbsencePayrollImpactV2.forMonth(
            absences = emptyList(),
            referenceDate = LocalDate.of(2026, 9, 30),
            acceptedEmployerIds = setOf("company-a"),
            workSessions = emptyList()
        )

        assertFalse(result.requiresPayrollReview)
        assertEquals(0, result.unpaidFullCalendarDays)
        assertTrue(result.warnings.isEmpty())
    }
}
