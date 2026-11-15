package com.amaury.pointage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailyDiagnosticModelV2Test {
    private fun event(time: Long, observed: Long = time) = DailyDiagnosticEventV2(time, observed, DailyDiagnosticKindV2.GPS_EVENT)
    @Test fun retentionKeepsMostRecentTwoThousandAndRejectsFutureAndExpired() {
        val now = DailyDiagnosticModelV2.RETENTION_MS + 10_000L
        val events = listOf(event(1L)) + (0..2100).map { event(now - 2100 + it) } + event(now + 1)
        val kept = DailyDiagnosticModelV2.retain(events, now)
        assertEquals(2000, kept.size)
        assertEquals(now, kept.last().recordedAtMs)
        assertEquals(now - 1999, kept.first().recordedAtMs)
    }
    @Test fun dateUsesRealDayBoundariesAcrossSpringDst() {
        val zone = ZoneId.of("Europe/Paris")
        val date = LocalDate.of(2026, 3, 29)
        val before = Instant.parse("2026-03-28T22:59:59Z").toEpochMilli()
        val first = before + 1000
        val last = Instant.parse("2026-03-29T21:59:59Z").toEpochMilli()
        val after = last + 1000
        assertEquals(listOf(event(first), event(last)), DailyDiagnosticModelV2.forDay(
            listOf(event(before), event(first), event(last), event(after)), date, zone))
    }
    @Test fun repeatedAutumnHourPreservesBothInstants() {
        val events = listOf(event(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()), event(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()))
        assertEquals(2, DailyDiagnosticModelV2.forDay(events, LocalDate.of(2026, 10, 25), ZoneId.of("Europe/Paris")).size)
        assertNotEquals(DailyDiagnosticModelV2.timestamp(events[0].recordedAtMs, ZoneId.of("Europe/Paris")), DailyDiagnosticModelV2.timestamp(events[1].recordedAtMs, ZoneId.of("Europe/Paris")))
    }
    @Test fun delayedConfirmationAppearsOnObservationAndProcessingDaysWithoutMovingObservation() {
        val observed = Instant.parse("2026-10-14T21:59:00Z").toEpochMilli()
        val processed = Instant.parse("2026-10-14T22:07:00Z").toEpochMilli()
        val event = event(processed, observed)
        for (day in listOf(14, 15)) assertEquals(listOf(event), DailyDiagnosticModelV2.forDay(listOf(event), LocalDate.of(2026, 10, day), ZoneId.of("Europe/Paris")))
        assertEquals(observed, event.observedAtMs)
        assertEquals(processed, event.recordedAtMs)
    }
    @Test fun reasonMappingDoesNotExposeUnexpectedMessages() {
        assertEquals("RUNTIME_UNRELIABLE", DailyDiagnosticModelV2.reasonCode("Runtime V2 non fiable : transition GPS bloquée"))
        assertEquals("OTHER_REASON", DailyDiagnosticModelV2.reasonCode("Private address or secret"))
    }
    @Test fun freeTextAndSecretsCannotEnterCodes() {
        for (input in listOf("Bearer abc", "48.123,-1.25", "user@example.fr", "SITE_A", "SUCCESS\nsecret")) assertEquals("", DailyDiagnosticModelV2.safeCode(input))
        assertEquals("SUCCESS_PAID", DailyDiagnosticModelV2.safeCode("SUCCESS_PAID"))
    }
}
