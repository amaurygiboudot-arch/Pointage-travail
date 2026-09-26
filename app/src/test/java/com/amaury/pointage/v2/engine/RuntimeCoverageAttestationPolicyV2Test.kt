package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RuntimeCoverageAttestationPolicyV2Test {
    private val zone = "Europe/Paris"
    private val start = LocalDate.of(2026, 9, 21).toEpochDay()
    private val end = start + 6
    private val checkedAt = LocalDate.ofEpochDay(end + 1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli() + 1

    @Test fun onlyExplicitUserReviewMayIssueCoverage() {
        assertTrue(RuntimeCoverageClaimPolicyV2.mayIssue(
            RuntimeCoverageClaimOriginV2.USER_REVIEWED_CLOSED_PERIOD))
        assertFalse(RuntimeCoverageClaimPolicyV2.mayIssue(RuntimeCoverageClaimOriginV2.LOCAL_BACKUP_RESTORE))
        assertFalse(RuntimeCoverageClaimPolicyV2.mayIssue(RuntimeCoverageClaimOriginV2.CLOUD_BACKUP_RESTORE))
        assertFalse(RuntimeCoverageClaimPolicyV2.mayIssue(RuntimeCoverageClaimOriginV2.LEGACY_MIGRATION))
        assertFalse(RuntimeCoverageClaimPolicyV2.mayIssue(RuntimeCoverageClaimOriginV2.STORAGE_READ))
    }

    @Test fun explicitEmptyCoverageCanBeCertified() {
        val a = create(emptyList())
        assertNotNull(a)
        assertTrue(RuntimeCoverageAttestationPolicyV2.validate(a!!, emptyList(), checkedAt + 1))
    }

    @Test fun reliableJournalAloneDoesNotCreateAttestation() {
        assertNull(RuntimeCoverageAttestationPolicyV2.create(
            emptyList(), "", start, end, checkedAt, zone, checkedAt + 1))
    }

    @Test fun changedPauseInvalidatesAttestation() {
        val initial = listOf(session("s1"))
        val a = create(initial)!!
        val changed = listOf(initial.single().copy(pauses = listOf(
            PauseV2(ms(start, 12), ms(start, 12, 30), false, EventSourceV2.MANUAL))))
        assertFalse(RuntimeCoverageAttestationPolicyV2.validate(a, changed, checkedAt + 1))
    }

    @Test fun addedSessionInsideCoverageInvalidatesAttestation() {
        val initial = listOf(session("s1"))
        val a = create(initial)!!
        assertFalse(RuntimeCoverageAttestationPolicyV2.validate(
            a, initial + session("s2", start + 1), checkedAt + 1))
    }

    @Test fun sessionOutsideCoverageDoesNotInvalidatePastCoverage() {
        val initial = listOf(session("s1"))
        val a = create(initial)!!
        val future = session("future", end + 2)
        assertTrue(RuntimeCoverageAttestationPolicyV2.validate(
            a, initial + future, checkedAt + 86_400_000L * 3))
    }

    @Test fun openSessionInsideCoverageCannotBeCertified() {
        val open = session("open").copy(realExitMs = null, countedExitMs = null, status = SessionStatusV2.OPEN)
        assertNull(create(listOf(open)))
    }

    @Test fun unfinishedCoverageCannotBeCertified() {
        val tooEarly = ms(end, 12)
        assertNull(RuntimeCoverageAttestationPolicyV2.create(
            emptyList(), "explicit-check", start, end, tooEarly, zone, tooEarly))
    }

    @Test fun otherTimezoneInvalidatesCertificateSemantics() {
        val a = create(emptyList())!!
        assertFalse(RuntimeCoverageAttestationPolicyV2.validate(
            a.copy(timeZoneId = "Not/AZone"), emptyList(), checkedAt + 1))
    }

    private fun create(sessions: List<WorkSessionV2>) =
        RuntimeCoverageAttestationPolicyV2.create(
            sessions, "explicit-check", start, end, checkedAt, zone, checkedAt + 1)

    private fun session(id: String, day: Long = start) = WorkSessionV2(
        id = id, employerId = "company", realArrivalMs = ms(day, 8), countedEntryMs = ms(day, 8),
        countedExitMs = ms(day, 16), realExitMs = ms(day, 16), status = SessionStatusV2.CLOSED)

    private fun ms(day: Long, hour: Int, minute: Int = 0) =
        LocalDate.ofEpochDay(day).atTime(hour, minute).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
}
