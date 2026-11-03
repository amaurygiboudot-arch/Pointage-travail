package com.amaury.pointage.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V2LegacyCurrentReconciliationTest {
    private fun closed(id: String, start: Long, end: Long) = mapOf<String, Any>(
        "session_id" to id, "real_entry" to start, "counted_entry" to start,
        "real_exit" to end, "counted_exit" to end, "time_basis" to "REAL_FACTS", "pauses" to "[]",
        "history" to """[{"id":"$id","realEntry":$start,"countedEntry":$start,"realExit":$end,"countedExit":$end,"employerId":null,"timeBasis":"REAL_FACTS","pauses":[]}]"""
    )
    private fun open(id: String = "open", start: Long = 10_000L) = mapOf<String, Any>(
        "session_id" to id, "real_entry" to start, "counted_entry" to start,
        "time_basis" to "REAL_FACTS", "pauses" to "[]", "expected_end" to start + 90_000L,
        "pause_start" to start + 1_000L, "pause_source" to "GPS", "history" to "[]"
    )
    private fun changeArchive(values: Map<String, Any>, change: (JSONObject) -> Unit): Map<String, Any> {
        val history = JSONArray(values["history"] as String)
        change(history.getJSONObject(0))
        return values + ("history" to history.toString())
    }

    @Test fun disjointArchivedClosedCurrentsKeepAccountCurrentAndBothFacts() {
        val account = closed("account-synthetic", 3_000L, 4_000L)
        val old = closed("legacy-synthetic", 1_000L, 2_000L)
        assertTrue(V2LegacyJournalRecovery.validSource(account))
        assertTrue(V2LegacyJournalRecovery.validSource(old))
        val trace = LegacyJournalDiagnosticV2.Trace()
        trace.register(account, LegacyJournalDiagnosticV2.Source.ACCOUNT)
        trace.register(old, LegacyJournalDiagnosticV2.Source.V2)
        trace.stage = LegacyJournalDiagnosticV2.Stage.ACCOUNT_MERGE
        val merged = V2LegacyJournalRecovery.merge(account, old, trace)
        assertNotNull(trace.issue?.render(), merged)
        assertEquals(account - "history", merged!!.filterKeys { it != "history" })
        val history = JSONArray(merged["history"] as String)
        assertEquals(2, history.length())
        assertEquals("account-synthetic", history.getJSONObject(0).getString("id"))
        assertEquals("legacy-synthetic", history.getJSONObject(1).getString("id"))
        assertTrue(V2LegacyJournalRecovery.validSource(merged))
        assertEquals(merged, V2LegacyJournalRecovery.merge(merged, old))
        assertNull(trace.issue)
    }

    @Test fun closedPlanningValuesExpireWithoutCrossingIntoAnotherCurrent() {
        val left = closed("account", 3_000L, 4_000L) + ("expected_end" to 5_000L)
        val right = closed("old", 1_000L, 2_000L) + ("expected_end" to 9_000L)
        val merged = V2LegacyJournalRecovery.merge(left, right)!!
        assertEquals(left - "history", merged.filterKeys { it != "history" })
        assertEquals(9_000L, right["expected_end"])
        val active = open()
        for ((first, second) in listOf(left to active, active to left)) {
            val result = V2LegacyJournalRecovery.merge(first, second)!!
            assertEquals(active - "history", result.filterKeys { it != "history" })
            assertFalse(result.containsKey("pause_paid")) // Unknown qualification stays unknown.
            assertEquals(JSONArray(left["history"] as String).toString(), result["history"])
        }
    }

    @Test fun missingArchiveCannotBeReconstructedToReplaceAConflictingCurrent() {
        val unarchived = closed("old", 1_000L, 2_000L) - "history"
        assertTrue(V2LegacyJournalRecovery.validSource(unarchived))
        assertNull(V2LegacyJournalRecovery.merge(unarchived, open()))
        assertNull(V2LegacyJournalRecovery.merge(open(), unarchived))
        val otherUnarchived = closed("other", 3_000L, 4_000L) - "history"
        assertNull(V2LegacyJournalRecovery.merge(unarchived, otherUnarchived))
    }

    @Test fun unarchivedClosedFactsAreRetainedWhenOnlyTheOtherCacheIsRedundant() {
        val unarchived = closed("unarchived", 3_000L, 4_000L) - "history"
        val archived = closed("archived", 1_000L, 2_000L)
        for ((left, right) in listOf(unarchived to archived, archived to unarchived)) {
            val result = V2LegacyJournalRecovery.merge(left, right)!!
            assertEquals(unarchived, result.filterKeys { it != "history" })
            assertEquals(JSONArray(archived["history"] as String).toString(), result["history"])
            assertEquals(1, JSONArray(result["history"] as String).length())
        }
    }

    @Test fun sourceValidityDoesNotProveEqualityWithRicherArchiveFacts() {
        val source = closed("old", 1_000L, 2_000L)
        val cases = mapOf(
            "timeBasis" to (source - "time_basis"),
            "countedEntry" to (source - "counted_entry"),
            "countedExit" to (source - "counted_exit"),
            "employerId" to changeArchive(source) { it.put("employerId", "another-employer") },
            "legacy employer fallback" to changeArchive(source) { it.remove("employerId") },
            "companySlot" to changeArchive(source) { it.put("companySlot", 1) },
            "placeId" to changeArchive(source) { it.put("placeId", "place") },
            "placeLabel" to changeArchive(source) { it.put("placeLabel", "label") },
            "closureOrigin" to changeArchive(source) { it.put("closureOrigin", "UNKNOWN") },
            "departureObservedAtMs" to changeArchive(source) { it.put("departureObservedAtMs", 2_000L) },
            "legacyFixedUnpaidPauseMs" to changeArchive(source) { it.put("legacyFixedUnpaidPauseMs", 1L) },
            "workSegments" to changeArchive(source) { it.put("workSegments", JSONArray(
                """[{"start":1000,"end":2000,"kind":"TO_CONFIRM","status":"TO_CONFIRM"}]""")) }
        )
        for ((field, values) in cases) {
            assertTrue(field, V2LegacyJournalRecovery.validSource(values))
            assertNull(field, V2LegacyJournalRecovery.merge(values, open()))
            assertNull(field, V2LegacyJournalRecovery.merge(open(), values))
        }
    }

    @Test fun explicitSchemaDefaultsMatchWithoutRemovingUnknownArchiveFields() {
        val source = mapOf<String, Any>("session_id" to "old", "real_entry" to 1_000L,
            "real_exit" to 2_000L, "pauses" to "[]", "history" to """[{"id":"old",
                "realEntry":1000,"realExit":2000,"employerId":null,"countedEntry":null,
                "countedExit":null,"placeId":null,"placeLabel":null,"closureOrigin":null,
                "departureObservedAtMs":null,"timeBasis":"LEGACY_UNVERIFIED","workSegments":[],
                "legacyFixedUnpaidPauseMs":0,"pauses":[],"extra":"preserve-verbatim-value"}]""")
        assertTrue(V2LegacyJournalRecovery.validSource(source))
        val result = V2LegacyJournalRecovery.merge(source, open())!!
        val saved = JSONArray(result["history"] as String).getJSONObject(0)
        assertEquals("preserve-verbatim-value", saved.getString("extra"))
        assertEquals("LEGACY_UNVERIFIED", saved.getString("timeBasis"))
        assertEquals(open() - "history", result.filterKeys { it != "history" })
    }

    @Test fun paidPausesUnqualifiedPresenceAndClosureEvidenceRemainExact() {
        val pauses = """[{"start":1200,"end":1300,"source":"MANUAL","paid":true},{"start":1500,"end":1600,"source":"GPS","paid":false}]"""
        val segments = """[{"start":1000,"end":2000,"kind":"TO_CONFIRM","status":"TO_CONFIRM"}]"""
        val source = changeArchive(closed("old", 1_000L, 2_000L)) {
            it.put("pauses", JSONArray(pauses)).put("workSegments", JSONArray(segments))
                .put("closureOrigin", "AUTOMATIC").put("departureObservedAtMs", 2_000L)
                .put("employerId", "employer").put("companySlot", 2)
                .put("placeId", "place").put("placeLabel", "label")
        } + mapOf("pauses" to pauses, "work_segments" to segments, "closureOrigin" to "AUTOMATIC",
            "departureObservedAtMs" to 2_000L, "employer_id" to "employer", "company_slot" to 2,
            "place_id" to "place", "place_label" to "label")
        val before = V2LegacyJournalRecovery.fingerprint(source)
        val result = V2LegacyJournalRecovery.merge(open(), source)!!
        assertEquals(source["history"], result["history"])
        assertEquals(before, V2LegacyJournalRecovery.fingerprint(source))
        for ((field, changed) in mapOf("pauses" to pauses.replace("true", "false"),
            "work_segments" to segments.replace("TO_CONFIRM", "WORK"), "closureOrigin" to "MANUAL",
            "departureObservedAtMs" to 1_999L, "employer_id" to "other", "company_slot" to 1,
            "place_id" to "other", "place_label" to "other", "time_basis" to "LEGACY_UNVERIFIED")) {
            assertNull(field, V2LegacyJournalRecovery.merge(open(), source + (field to changed)))
        }
    }

    @Test fun twoOpenStatesAndDivergentSameIdentityRemainConflicts() {
        assertNull(V2LegacyJournalRecovery.merge(open("a"), open("b")))
        assertNotNull(V2LegacyJournalRecovery.merge(open(), open()))
        val source = closed("same", 1_000L, 2_000L)
        assertNull(V2LegacyJournalRecovery.merge(source, source + ("expected_end" to 3_000L)))
        val changed = changeArchive(source + ("counted_exit" to 2_100L)) { it.put("countedExit", 2_100L) }
        assertTrue(V2LegacyJournalRecovery.validSource(changed))
        assertNull(V2LegacyJournalRecovery.merge(source, changed))
        assertNull(V2LegacyJournalRecovery.merge(source, open("same")))
    }

    @Test fun archiveIdentityConflictsAndOverlapsStillRejectAfterCurrentSelection() {
        val left = closed("account", 3_000L, 4_000L)
        val right = closed("old", 1_000L, 2_000L)
        val conflictingRow = JSONArray(left["history"] as String).getJSONObject(0).put("extra", "different")
        val withConflict = right + ("history" to JSONArray(right["history"] as String).put(conflictingRow).toString())
        val trace = LegacyJournalDiagnosticV2.Trace()
        trace.register(left, LegacyJournalDiagnosticV2.Source.ACCOUNT)
        trace.register(withConflict, LegacyJournalDiagnosticV2.Source.V2)
        assertNull(V2LegacyJournalRecovery.merge(left, withConflict, trace))
        assertEquals(LegacyJournalDiagnosticV2.Code.IDENTITY_CONFLICT, trace.issue!!.code)
        assertNull(V2LegacyJournalRecovery.merge(left, closed("overlap", 3_500L, 4_500L)))
        assertNull(V2LegacyJournalRecovery.merge(left, open(start = 3_500L)))
        assertNotNull(V2LegacyJournalRecovery.merge(left, closed("adjacent", 4_000L, 5_000L)))
    }

    @Test fun selectedCurrentKeepsItsOriginalReferenceThroughTwoMerges() {
        val old = closed("old", 1_000L, 2_000L)
        val active = open(start = 3_000L)
        val account = closed("account", 4_000L, 5_000L).filterKeys { it == "history" }
        val trace = LegacyJournalDiagnosticV2.Trace()
        trace.register(old, LegacyJournalDiagnosticV2.Source.V2)
        trace.register(active, LegacyJournalDiagnosticV2.Source.V1)
        trace.register(account, LegacyJournalDiagnosticV2.Source.ACCOUNT)
        val combined = V2LegacyJournalRecovery.merge(old, active, trace)!!
        assertEquals(LegacyJournalDiagnosticV2.Source.V1, trace.ref(combined).source)
        trace.stage = LegacyJournalDiagnosticV2.Stage.ACCOUNT_MERGE
        assertNull(V2LegacyJournalRecovery.merge(account, combined, trace))
        val issue = trace.issue!!
        assertEquals(LegacyJournalDiagnosticV2.Code.OVERLAP, issue.code)
        assertEquals(LegacyJournalDiagnosticV2.Stage.ACCOUNT_MERGE, issue.stage)
        assertEquals(setOf(LegacyJournalDiagnosticV2.Reference(LegacyJournalDiagnosticV2.Source.V1, current = true),
            LegacyJournalDiagnosticV2.Reference(LegacyJournalDiagnosticV2.Source.ACCOUNT, row = 1)),
            setOf(issue.first, issue.second))
    }
}
