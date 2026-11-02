package com.amaury.pointage.v2

import org.junit.Assert.*
import org.junit.Test

class V2LegacyJournalRecoveryTest {
    private fun history(id: String, start: Long, end: Long) = mapOf("history" to
        """[{"id":"$id","realEntry":$start,"countedEntry":$start,"realExit":$end,"countedExit":$end,"employerId":null,"pauses":[]}]""")

    @Test fun disjointHistoryMergesAndIdenticalIdentityDeduplicates() {
        val first = history("a", 1000, 2000)
        val second = history("b", 3000, 4000)
        val merged = V2LegacyJournalRecovery.merge(first, second)!!
        assertEquals(2, org.json.JSONArray(merged["history"] as String).length())
        assertEquals(1, org.json.JSONArray(V2LegacyJournalRecovery.merge(first, first)!!["history"] as String).length())
        assertNull(V2LegacyJournalRecovery.merge(first, history("a", 1000, 2100)))
        assertNull(V2LegacyJournalRecovery.merge(first, history("b", 1500, 2500)))
        assertNull(V2LegacyJournalRecovery.merge(current(), current() + ("session_id" to "two")))
    }

    @Test fun strictLegacyConversionNeverInfersPauseOrEmployer() {
        val raw = """[{"arrivalTime":1000,"countedEntryTime":1000,"entry":1000,"exitTime":3000,"countedExitTime":3000,"exit":3000,"companySlot":1,"pauses":[]}]"""
        val converted = V2LegacyJournalRecovery.convertLegacy(raw)!!
        assertEquals(converted, V2LegacyJournalRecovery.convertLegacy(raw))
        val row = org.json.JSONArray(converted["history"] as String).getJSONObject(0)
        assertTrue(row.isNull("employerId"))
        assertEquals("LEGACY_UNVERIFIED", row.getString("timeBasis"))
        assertNull(V2LegacyJournalRecovery.convertLegacy("""[{"entry":1000,"exit":3000,"pauses":[]}]"""))
        assertNull(V2LegacyJournalRecovery.convertLegacy(raw.replace("[]", "[{\"start\":1500,\"end\":2000}]")))
    }

    private fun current() = mapOf<String, Any>("session_id" to "one", "real_entry" to 1000L,
        "counted_entry" to 1000L, "pauses" to "[]", "time_basis" to "REAL_FACTS")

    @Test fun emptyDestinationNeverIncludesCurrentOrUnknownFacts() {
        assertTrue(V2LegacyJournalRecovery.emptyDestination(emptyMap<String, Any>()))
        assertTrue(V2LegacyJournalRecovery.emptyDestination(mapOf("history" to "[]")))
        assertFalse(V2LegacyJournalRecovery.emptyDestination(current()))
        assertFalse(V2LegacyJournalRecovery.emptyDestination(mapOf("history" to "broken")))
        assertFalse(V2LegacyJournalRecovery.emptyDestination(mapOf("unknown" to "value")))
    }
    @Test fun malformedCurrentCannotBeClaimedAsValidHistory() {
        assertTrue(V2LegacyJournalRecovery.validSource(current()))
        assertFalse(V2LegacyJournalRecovery.validSource(current() - "real_entry"))
        assertFalse(V2LegacyJournalRecovery.validSource(current() + ("expected_end" to 999L)))
        assertFalse(V2LegacyJournalRecovery.validSource(current() + ("real_exit" to 900L)))
        assertFalse(V2LegacyJournalRecovery.validSource(current() + ("new_unsupported_field" to 1L)))
    }
    @Test fun activePauseMustBeCompatibleAndPreservesUnknownPaidQualification() {
        val paused = current() + mapOf("pause_start" to 2000L, "pause_source" to "GPS")
        assertTrue(V2LegacyJournalRecovery.validSource(paused))
        assertFalse(V2LegacyJournalRecovery.validSource(paused + ("pause_start" to 900L)))
        assertFalse(V2LegacyJournalRecovery.validSource(paused + ("pause_paid" to "false")))
        assertFalse(V2LegacyJournalRecovery.validSource(paused + ("real_exit" to 3000L)))
    }
    @Test fun snapshotFingerprintDetectsFactsTypesAndIsOrderIndependent() {
        assertEquals(V2LegacyJournalRecovery.fingerprint(current()), V2LegacyJournalRecovery.fingerprint(current().toSortedMap()))
        assertNotEquals(V2LegacyJournalRecovery.fingerprint(current()), V2LegacyJournalRecovery.fingerprint(current() + ("real_entry" to "1000")))
        assertNotEquals(V2LegacyJournalRecovery.fingerprint(current()), V2LegacyJournalRecovery.fingerprint(current() + ("pause_start" to 2000L)))
    }

    @Test fun openGpsPresenceQualificationSurvivesBackupMergeWithoutBeingPromotedToWork() {
        val segments = """[{"start":1000,"end":null,"kind":"TO_CONFIRM","status":"TO_CONFIRM"}]"""
        val source = current() + ("work_segments" to segments)
        assertTrue(V2LegacyJournalRecovery.validSource(source))
        assertEquals(source, V2BackupManager.mergeRuntimeValues(emptyMap<String, Any>(), source).values)
        val recovered = V2LegacyJournalRecovery.merge(emptyMap<String, Any>(), source)!!
        assertEquals(segments, recovered["work_segments"])
        assertFalse(V2LegacyJournalRecovery.validSource(source + ("work_segments" to segments.replace("TO_CONFIRM", "WORK"))))
        assertFalse(V2LegacyJournalRecovery.validSource(source + ("work_segments" to segments.replace("1000", "500"))))
        assertFalse(V2LegacyJournalRecovery.validSource(source + ("work_segments" to "broken")))
    }

    @Test fun closedGpsPresenceAndProvenanceSurviveBackupMerge() {
        val segments = """[{"start":1000,"end":3000,"kind":"TO_CONFIRM","status":"TO_CONFIRM"}]"""
        val row = """{"id":"one","realEntry":1000,"realExit":3000,"countedEntry":1000,"countedExit":3000,"timeBasis":"REAL_FACTS","pauses":[],"workSegments":$segments,"closureOrigin":"AUTOMATIC","departureObservedAtMs":3000}"""
        val source = current() + mapOf("real_exit" to 3000L, "counted_exit" to 3000L,
            "work_segments" to segments, "closureOrigin" to "AUTOMATIC", "departureObservedAtMs" to 3000L,
            "history" to "[$row]")
        assertTrue(V2LegacyJournalRecovery.validSource(source))
        assertEquals(source, V2BackupManager.mergeRuntimeValues(emptyMap<String, Any>(), source).values)
        assertFalse(V2LegacyJournalRecovery.validSource(source + ("departureObservedAtMs" to 3100L)))
        assertFalse(V2LegacyJournalRecovery.validSource(source + ("work_segments" to segments.replace("3000", "null"))))
    }

    @Test fun manualAndHistoricalSourcesKeepTheirOriginalQualificationDuringRestore() {
        val manual = current() + ("work_segments" to "[]")
        assertEquals(manual, V2BackupManager.mergeRuntimeValues(emptyMap<String, Any>(), manual).values)
        val historical = current() + ("time_basis" to "LEGACY_UNVERIFIED")
        assertEquals(historical, V2BackupManager.mergeRuntimeValues(emptyMap<String, Any>(), historical).values)
        assertFalse(V2LegacyJournalRecovery.validSource(manual + ("unrecognized_metadata" to true)))
    }
}
