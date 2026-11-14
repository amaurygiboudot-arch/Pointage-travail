package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [RecoveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class V2LegacyJournalRecoveryIntegrationTest {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private val files = mutableMapOf<String, FaultPreferences>()
    private fun file(name: String) = files.getOrPut(name) { FaultPreferences() }
    private fun newContext(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = file(name)
    }
    private fun account(id: String?) {
        val user = id?.let { Mockito.mock(FirebaseUser::class.java).also { u -> Mockito.`when`(u.uid).thenReturn(it) } }
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }
    @Before fun setup() {
        files.clear()
        context = newContext()
        auth = Mockito.mock(FirebaseAuth::class.java)
        RecoveryAuthShadow.auth = auth
        account("a")
        listOf("horatrack_v2_test_runtime", "pointage", "horatrack_v2_legacy_recovery_claim",
            V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!,
            V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:b")!!).forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        context.getSharedPreferences("horatrack_v2_test_runtime", 0).edit()
            .putString("history", """[{"id":"old","realEntry":1000,"realExit":2000,"countedEntry":1000,"countedExit":2000,"employerId":null,"pauses":[]}]""").commit()
    }
    @Test fun recoveryPreservesOriginalAndDoesNotLeakIntoAnotherAccount() {
        val original = context.getSharedPreferences("horatrack_v2_test_runtime", 0).all
        val preview = V2LegacyJournalRecovery.preview(context)!!
        assertTrue(preview.eligible)
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, false).success)
        assertTrue(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        assertEquals(original, context.getSharedPreferences("horatrack_v2_test_runtime", 0).all)
        assertFalse(V2RuntimeAccountScope.hasUnassignedHistory(context))
        account("b")
        assertFalse(V2RuntimeAccountScope.hasUnassignedHistory(context))
        assertTrue(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").all.isEmpty())
        assertNull(V2LegacyJournalRecovery.preview(context))
    }

    private fun producedClosed(uid: String, start: Long, gps: Boolean): Map<String, *> {
        account(uid)
        val employer = "synthetic-employer-$uid"
        assertTrue(com.amaury.pointage.SalaryCompanyStore.createOrUpdate(context,
            com.amaury.pointage.SalaryCompanyStore.Company(employer, "Synthetic employer", "")))
        assertTrue(V2ProfileStore.setActiveCompanyId(context, employer))
        assertTrue(V2RuntimeStore.entry(context, start, expectedEndMs = start + 8_000L,
            source = if (gps) com.amaury.pointage.v2.model.EventSourceV2.GPS else com.amaury.pointage.v2.model.EventSourceV2.MANUAL))
        if (!gps) assertEquals(2, V2RuntimeStore.addQualifiedManualPauses(context, listOf(
            QualifiedManualPauseV2(start + 1_000L, start + 2_000L, true),
            QualifiedManualPauseV2(start + 3_000L, start + 4_000L, false))))
        assertTrue(V2RuntimeStore.exit(context, start + 9_000L,
            origin = com.amaury.pointage.ClosureOriginV2.WIDGET, departureObservedAtMs = start + 9_000L))
        return V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").all.toMap()
    }

    private fun seedProducedClosedJournals(): Pair<Map<String, *>, Map<String, *>> {
        val old = producedClosed("b", 10_000L, gps = true)
        val destination = producedClosed("a", 30_000L, gps = false)
        assertTrue(V2PreferenceValues.replace(file("horatrack_v2_test_runtime"), old))
        assertTrue(V2LegacyJournalRecovery.validSource(old))
        assertTrue(V2LegacyJournalRecovery.validSource(destination))
        return old to destination
    }

    @Test fun realProducerClosedCachesRecoverWithoutChangingPausesPresenceOrOriginals() {
        val (old, destination) = seedProducedClosedJournals()
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = files.values.sumOf { it.commits }
        val diagnostic = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        assertTrue(V2LegacyJournalRecovery.diagnose(context, diagnostic, true).contains("Aucun conflit détecté"))
        val preview = V2LegacyJournalRecovery.preview(context)!!
        assertTrue(preview.message, preview.eligible)
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, files.values.sumOf { it.commits })
        assertTrue(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        assertEquals(old, file("horatrack_v2_test_runtime").all)
        val saved = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").all
        assertEquals(destination.filterKeys { it != "history" },
            saved.filterKeys { it != "history" && it != "legacy_recovery_receipt" })
        val history = JSONArray(saved["history"] as String)
        assertEquals(2, history.length())
        assertEquals(JSONArray(destination["history"] as String).getJSONObject(0).toString(), history.getJSONObject(0).toString())
        assertEquals(JSONArray(old["history"] as String).getJSONObject(0).toString(), history.getJSONObject(1).toString())
        assertEquals("TO_CONFIRM", history.getJSONObject(1).getJSONArray("workSegments").getJSONObject(0).getString("kind"))
        files.values.forEach { it.restart() }
        assertEquals(old, file("horatrack_v2_test_runtime").all)
        assertNull(V2LegacyJournalRecovery.preview(context))
        val all = V2RuntimeStore.allSessions(context)
        assertEquals(2, all.size)
        assertEquals(setOf(true, false), all.single { it.id == destination["session_id"] }.pauses.map { it.paid }.toSet())
    }

    @Test fun closedCacheReconciliationRetriesFailedDestinationCommitWithoutDuplicate() {
        val (old, destination) = seedProducedClosedJournals()
        val target = file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!)
        target.failOnCommit = target.commits + 1
        assertFalse(V2LegacyJournalRecovery.confirm(context, V2LegacyJournalRecovery.preview(context)!!, true).success)
        files.values.forEach { it.restart() }
        val retry = V2LegacyJournalRecovery.preview(context)!!
        assertTrue(retry.message, retry.eligible)
        assertTrue(V2LegacyJournalRecovery.confirm(context, retry, true).success)
        files.values.forEach { it.restart() }
        assertEquals(old, file("horatrack_v2_test_runtime").all)
        assertEquals(destination["session_id"], target.all["session_id"])
        assertEquals(2, JSONArray(target.all["history"] as String).length())
        assertNull(V2LegacyJournalRecovery.preview(context))
    }

    private fun unarchivedClosed() = mapOf<String, Any>("session_id" to "retained",
        "real_entry" to 1_000L, "real_exit" to 9_000L, "counted_entry" to 1_000L,
        "counted_exit" to 9_000L, "time_basis" to "REAL_FACTS", "pauses" to "[]")

    private fun seedForNextEntry(values: Map<String, *>): FaultPreferences {
        file("horatrack_v2_test_runtime").edit().clear().commit()
        assertTrue(V2MigrationManager.ensureMigrated(context).reliable)
        val target = file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!)
        assertTrue(V2PreferenceValues.replace(target, values))
        return target
    }

    private fun recreatePreferences() {
        files.values.forEach { it.restart() }
        context = newContext()
        SalaryAccountScope.invalidateSession()
    }

    @Test fun nextEntryArchivesUnknownClosedFactsAndKeepsMetadataInOneCommit() {
        val pauses = """[{"start":2000,"end":3000,"source":"MANUAL","paid":true},{"start":5000,"end":6000,"source":"GPS","paid":false}]"""
        val segments = """[{"start":1000,"end":9000,"kind":"TO_CONFIRM","status":"TO_CONFIRM"}]"""
        val original = (unarchivedClosed() - "counted_entry" - "counted_exit" - "time_basis") + mapOf(
            "company_slot" to 1, "pauses" to pauses, "work_segments" to segments,
            "closureOrigin" to "UNKNOWN", "expected_end" to 8_000L,
            "legacy_recovery_receipt" to "local-proof", "restored_legacy_provenance" to "[\"prior-proof\"]")
        val target = seedForNextEntry(original)
        val commits = target.commits
        assertTrue(V2RuntimeStore.entry(context, 10_000L))
        assertEquals(commits + 1, target.commits)
        val row = JSONArray(target.all["history"] as String).getJSONObject(0)
        assertEquals("retained", row.getString("id"))
        assertEquals(1_000L, row.getLong("realEntry")); assertEquals(9_000L, row.getLong("realExit"))
        assertTrue(row.isNull("countedEntry")); assertTrue(row.isNull("countedExit"))
        assertTrue(row.isNull("employerId")); assertEquals(1, row.getInt("companySlot"))
        assertEquals("LEGACY_UNVERIFIED", row.getString("timeBasis"))
        assertEquals(JSONArray(pauses).toString(), row.getJSONArray("pauses").toString())
        assertEquals(JSONArray(segments).toString(), row.getJSONArray("workSegments").toString())
        assertEquals("UNKNOWN", row.getString("closureOrigin")); assertTrue(row.isNull("departureObservedAtMs"))
        assertEquals("local-proof", target.all["legacy_recovery_receipt"])
        assertEquals("[\"prior-proof\"]", target.all["restored_legacy_provenance"])
        recreatePreferences()
        assertEquals(2, V2RuntimeStore.allSessions(context).size)
        assertEquals(1, V2RuntimeStore.allSessions(context).count { it.id == "retained" })
    }

    @Test fun nextEntryCannotPrecedeTheUnarchivedClosedExitAndDoesNotChangeProfile() {
        val target = seedForNextEntry(unarchivedClosed())
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        assertFalse(V2RuntimeStore.entry(context, 8_000L, companySlot = 2))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(unarchivedClosed(), target.all)
        assertTrue(V2RuntimeStore.entry(context, 9_000L)) // Adjacent ranges remain valid.
        assertEquals(2, V2RuntimeStore.allSessions(context).size)
    }

    @Test fun nextEntryRejectsUnrepresentableRawFactsBeforeAnyProfileOrRuntimeWrite() {
        val cases = listOf(
            unarchivedClosed() + ("unknown_current_field" to "preserve"),
            unarchivedClosed() + ("session_id" to " retained "),
            unarchivedClosed() + ("place_label" to " label "),
            unarchivedClosed() + ("employer_id" to " employer "),
            unarchivedClosed() + ("counted_entry" to "1000"),
            unarchivedClosed() + ("company_slot" to 3),
            unarchivedClosed() + ("legacy_recovery_receipt" to 1),
            unarchivedClosed() + ("restored_legacy_provenance" to "[1]"),
            unarchivedClosed() + ("pauses" to """[{"start":2000,"end":3000,"source":"MANUAL","paid":true,"extra":"preserve"}]"""),
            unarchivedClosed() + ("work_segments" to """[{"start":1000,"end":9000,"kind":"TO_CONFIRM","status":"TO_CONFIRM","extra":"preserve"}]""")
        )
        for ((index, values) in cases.withIndex()) {
            val target = seedForNextEntry(values)
            val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
            val commits = target.commits
            assertFalse("case $index", V2RuntimeStore.entry(context, 10_000L, companySlot = 2))
            assertEquals("case $index", before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
            assertEquals("case $index", commits, target.commits)
        }
    }

    @Test fun nextEntryRejectsConflictingArchiveOrRicherDefaultsWithoutChangingFacts() {
        val baseRow = JSONObject().put("id", "retained").put("realEntry", 1_000L).put("realExit", 9_000L)
            .put("countedEntry", 1_000L).put("countedExit", 9_000L).put("employerId", JSONObject.NULL)
            .put("timeBasis", "REAL_FACTS").put("pauses", JSONArray())
        val histories = listOf(
            JSONArray().put(JSONObject(baseRow.toString()).put("countedExit", 8_999L)),
            JSONArray().put(JSONObject(baseRow.toString()).put("workSegments", JSONArray(
                """[{"start":1000,"end":9000,"kind":"TO_CONFIRM","status":"TO_CONFIRM"}]"""))),
            JSONArray().put(JSONObject(baseRow.toString()).apply { remove("employerId") }),
            JSONArray().put(JSONObject(baseRow.toString()).put("id", "overlap"))
        )
        for (history in histories) {
            val target = seedForNextEntry(unarchivedClosed() + ("history" to history.toString()))
            val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
            val commits = target.commits
            assertFalse(V2RuntimeStore.entry(context, 10_000L, companySlot = 2))
            assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
            assertEquals(commits, target.commits)
        }
    }

    private fun verifyUnknownEmployerStaysArchived(createProfileAfterClose: Boolean) {
        val target = seedForNextEntry(emptyMap<String, Any>())
        assertTrue(V2RuntimeStore.entry(context, 1_000L))
        assertTrue(V2RuntimeStore.exit(context, 9_000L))
        val originalHistory = JSONArray(target.all["history"] as String).toString(2)
        assertTrue(target.edit().putString("history", originalHistory).commit())
        val closed = target.all
        assertFalse(closed.containsKey("employer_id"))
        assertFalse(V2LegacyJournalRecovery.validSource(closed)) // Cross-account recovery is still strict.
        assertTrue(V2LegacyJournalRecovery.validSource(closed,
            validationScope = V2LegacyJournalRecovery.ValidationScope.LOCAL_RUNTIME))
        if (createProfileAfterClose) {
            assertTrue(com.amaury.pointage.SalaryCompanyStore.createOrUpdate(context,
                com.amaury.pointage.SalaryCompanyStore.Company("new-employer", "New employer", "")))
            assertTrue(V2ProfileStore.setActiveCompanyId(context, "new-employer"))
        }
        assertTrue(V2RuntimeStore.entry(context, 10_000L))
        assertEquals(originalHistory, target.all["history"])
        assertTrue(JSONArray(target.all["history"] as String).getJSONObject(0).isNull("employerId"))
        if (createProfileAfterClose) assertEquals("new-employer", target.all["employer_id"])
        assertEquals(2, V2RuntimeStore.allSessions(context).size)
    }

    @Test fun realEntryExitEntryWithoutEmployerKeepsItsExistingUnknownArchive() =
        verifyUnknownEmployerStaysArchived(createProfileAfterClose = false)

    @Test fun profileCreatedAfterClosureDoesNotReassignTheExistingArchive() =
        verifyUnknownEmployerStaysArchived(createProfileAfterClose = true)

    @Test fun missingArchiveCannotInferAnOldEmployerFromANewProfile() {
        val target = seedForNextEntry(unarchivedClosed() + ("company_slot" to 1))
        assertTrue(com.amaury.pointage.SalaryCompanyStore.createOrUpdate(context,
            com.amaury.pointage.SalaryCompanyStore.Company("new-employer", "New employer", "")))
        assertTrue(V2ProfileStore.setActiveCompanyId(context, "new-employer"))
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = target.commits
        assertFalse(V2RuntimeStore.entry(context, 10_000L, companySlot = 2))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, target.commits)
    }

    @Test fun archiveAndNewEntryCommitFalseRemainAtomicAcrossPreferenceRecreation() {
        val original = unarchivedClosed()
        val target = seedForNextEntry(original)
        val commits = target.commits
        target.failOnCommit = commits + 1
        assertFalse(V2RuntimeStore.entry(context, 10_000L))
        assertEquals(commits + 1, target.commits)
        assertEquals(1, JSONArray(target.all["history"] as String).length()) // Complete new RAM image.
        assertEquals(10_000L, target.all["real_entry"])
        recreatePreferences()
        assertEquals(original, target.all) // Complete old disk image, never a half-published archive.
        assertEquals(setOf("retained"), V2RuntimeStore.allSessions(context).map { it.id }.toSet())
        assertTrue(V2RuntimeStore.entry(context, 10_000L))
        recreatePreferences()
        assertEquals(2, V2RuntimeStore.allSessions(context).size)
        assertEquals(1, V2RuntimeStore.allSessions(context).count { it.id == "retained" })
        assertEquals(1, JSONArray(target.all["history"] as String).length())
    }

    @Test fun alreadyOpenCurrentStillRefusesEntryWithoutChangingAnyProfile() {
        seedForNextEntry(unarchivedClosed() - "real_exit" - "counted_exit")
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        assertFalse(V2RuntimeStore.entry(context, 10_000L, companySlot = 2))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
    }

    @Test fun claimedUnarchivedClosedJournalSurvivesNextEntryAndRecreationWithOwnershipIntact() {
        val original = unarchivedClosed()
        assertTrue(V2PreferenceValues.replace(file("horatrack_v2_test_runtime"), original))
        val preview = V2LegacyJournalRecovery.preview(context)!!
        assertTrue(preview.message, preview.eligible)
        assertTrue(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        val target = file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!)
        val receipt = target.all["legacy_recovery_receipt"]
        val claimBefore = file("horatrack_v2_legacy_recovery_claim").all
        assertEquals("COMPLETE", claimBefore["state"])
        assertEquals(setOf("retained"), V2RuntimeStore.allSessions(context).map { it.id }.toSet())
        assertTrue(V2RuntimeStore.entry(context, 10_000L))
        recreatePreferences()
        assertEquals(original, file("horatrack_v2_test_runtime").all)
        assertEquals(receipt, target.all["legacy_recovery_receipt"])
        assertEquals(claimBefore, file("horatrack_v2_legacy_recovery_claim").all)
        assertFalse(V2RuntimeAccountScope.hasUnassignedHistory(context))
        assertNull(V2LegacyJournalRecovery.preview(context))
        val sessions = V2RuntimeStore.allSessions(context)
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.count { it.id == "retained" })
        assertEquals(1, sessions.count { it.realArrivalMs == 10_000L })
        assertEquals(1, JSONArray(target.all["history"] as String).length())
    }

    private fun prepareAccountsForEntrySwitch() {
        account("b")
        for (id in listOf("b-first", "b-second")) assertTrue(com.amaury.pointage.SalaryCompanyStore.createOrUpdate(
            context, com.amaury.pointage.SalaryCompanyStore.Company(id, id, "")))
        V2ProfileStore.setActiveCompanySlot(context, 1)
        assertTrue(V2PreferenceValues.replace(file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:b")!!),
            unarchivedClosed() + ("session_id" to "b-session")))
        account("a")
        seedForNextEntry(unarchivedClosed())
        V2ProfileStore.setActiveCompanySlot(context, 1)
        SalaryAccountScope.capture()
    }

    @Test fun accountSwitchAtProfileSelectionCannotWriteEitherAccount() {
        prepareAccountsForEntrySwitch()
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val profileB = SalaryPreferenceRegistry.fileName("horatrack_v2_integration", "uid:b")!!
        var switched = false
        val a = Mockito.mock(FirebaseUser::class.java).also { Mockito.`when`(it.uid).thenReturn("a") }
        val b = Mockito.mock(FirebaseUser::class.java).also { Mockito.`when`(it.uid).thenReturn("b") }
        Mockito.`when`(auth.currentUser).thenAnswer {
            // Test provider hook: cross the boundary after archive proof, before profile mutation.
            if (!switched && Thread.currentThread().stackTrace.any {
                    it.className == V2ProfileStore::class.java.name && it.methodName == "setActiveCompanySlot"
                }) switched = true
            if (switched) b else a
        }
        assertFalse(V2RuntimeStore.entry(context, 10_000L, companySlot = 2))
        assertTrue("The deterministic profile-selection account switch must occur", switched)
        for ((name, values) in before) if (name != profileB) assertEquals(name, values, file(name).all)
        assertEquals("Account B profile changed despite a refused entry", before[profileB], file(profileB).all)
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
    }

    @Test fun staleBoundEntryContextCannotBeReassignedToTheNewAccount() {
        prepareAccountsForEntrySwitch()
        val oldContext = SalaryAccountScope.bindContext(context)
        account("b")
        SalaryAccountScope.capture()
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        assertFalse(V2RuntimeStore.entry(oldContext, 10_000L, companySlot = 2))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
    }

    @Test fun accountRoundTripAtProfileSelectionCannotReviveTheOriginalEntryLease() {
        prepareAccountsForEntrySwitch()
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val initialLease = SalaryAccountScope.capture()
        var switched = false
        var insideRoundTrip = false
        val a = Mockito.mock(FirebaseUser::class.java).also { Mockito.`when`(it.uid).thenReturn("a") }
        val b = Mockito.mock(FirebaseUser::class.java).also { Mockito.`when`(it.uid).thenReturn("b") }
        Mockito.`when`(auth.currentUser).thenAnswer {
            if (!switched && Thread.currentThread().stackTrace.any {
                    it.className == V2ProfileStore::class.java.name && it.methodName == "setActiveCompanySlot"
                }) {
                switched = true
                insideRoundTrip = true
                assertEquals("uid:b", SalaryAccountScope.capture().scope)
                insideRoundTrip = false
            }
            if (insideRoundTrip) b else a
        }
        assertFalse(V2RuntimeStore.entry(context, 10_000L, companySlot = 2))
        assertTrue("The deterministic account round trip must occur", switched)
        assertEquals("uid:a", SalaryAccountScope.capture().scope)
        assertNotEquals(initialLease, SalaryAccountScope.capture())
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
    }
    private fun recoverAfterFailure(target: String, failAt: Int, restart: Boolean) {
        val preview = V2LegacyJournalRecovery.preview(context)!!
        val faulty = file(target)
        faulty.failOnCommit = faulty.commits + failAt
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        assertTrue(V2RuntimeAccountScope.hasUnassignedHistory(context))
        assertFalse(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
        account("b")
        assertFalse(V2LegacyJournalRecovery.preview(context)!!.eligible)
        assertTrue(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
        account("a")
        if (restart) files.values.forEach { it.restart() }
        val retry = V2LegacyJournalRecovery.preview(context)!!
        assertTrue(retry.message, retry.eligible)
        assertTrue(V2LegacyJournalRecovery.confirm(context, retry, true).success)
        files.values.forEach { it.restart() }
        assertFalse(V2RuntimeAccountScope.hasUnassignedHistory(context))
        val history = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").getString("history", null)!!
        assertEquals(1, org.json.JSONArray(history).length())
        assertEquals("old", org.json.JSONArray(history).getJSONObject(0).getString("id"))
    }

    @Test fun pendingReservationCommitFalseRetriesWithoutLosingOriginal() =
        recoverAfterFailure("horatrack_v2_legacy_recovery_claim", 1, false)

    @Test fun destinationCommitFalseRetriesCachedReceipt() = recoverAfterFailure(
        V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!, 1, false)

    @Test fun destinationCommitFalseThenCrashRestoresPendingAndRetries() = recoverAfterFailure(
        V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!, 1, true)

    @Test fun completeCommitFalseFreezesWritesAndRetries() =
        recoverAfterFailure("horatrack_v2_legacy_recovery_claim", 2, false)

    @Test fun crashBetweenDestinationAndCompletionRetriesWithoutDuplicate() =
        recoverAfterFailure("horatrack_v2_legacy_recovery_claim", 2, true)

    @Test fun pendingReservationFreezesOnlyItsOwnersWrites() {
        context.getSharedPreferences("horatrack_v2_legacy_recovery_claim", 0).edit()
            .putString("owner", "uid:a").putString("state", "PENDING").commit()
        assertFalse(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
        account("b")
        assertTrue(V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
            .edit().putString("history", "[]").commit())
    }

    @Test fun changedAccountDestinationAndGuestCannotUseOldConfirmation() {
        val preview = V2LegacyJournalRecovery.preview(context)!!
        account("b")
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, true).success)
        account(null)
        assertFalse(V2LegacyJournalRecovery.preview(context)!!.eligible)
        account("a")
        V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime").edit().putString("history", "[]").commit()
        assertFalse(V2LegacyJournalRecovery.confirm(context, preview, true).success)
    }

    @Test fun diagnosticExplainsRefusedConversionOnlyAfterDeclarationWithoutAnyWrite() {
        file("pointage").edit().putString("data", """[{"arrivalTime":3000,"exitTime":6000,"pauses":[{"start":4000,"end":5000}]}]""").commit()
        assertFalse(V2LegacyJournalRecovery.preview(context)!!.eligible)
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = files.values.sumOf { it.commits }
        val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        assertFalse(V2LegacyJournalRecovery.diagnose(context, session, false).contains("PAUSE_UNQUALIFIED"))
        repeat(2) {
            val report = V2LegacyJournalRecovery.diagnose(context, session, true)
            assertTrue(report, report.contains("PAUSE_UNQUALIFIED"))
            assertTrue(report.contains("Ancien journal V1 · pointage n° 1 · pause n° 1"))
            assertTrue(report.contains("Champ : paid"))
        }
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, files.values.sumOf { it.commits })
        files.values.forEach { it.restart() }
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
    }

    @Test fun diagnosticLeaseCannotReviveAfterAccountRoundTripOrSessionInvalidation() {
        val first = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        account("b"); SalaryAccountScope.capture()
        account("a"); SalaryAccountScope.capture()
        assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, first))
        assertTrue(V2LegacyJournalRecovery.diagnose(context, first, true).contains("indisponible"))
        val second = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        SalaryAccountScope.invalidateSession()
        assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, second))
        assertTrue(file("horatrack_v2_legacy_recovery_claim").all.isEmpty())
    }

    @Test fun changedDiagnosticSnapshotAndForeignReservationCannotExposeDetails() {
        val first = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        file("pointage").edit().putString("data", "not-json-sensitive@example.test").commit()
        assertTrue(V2LegacyJournalRecovery.diagnose(context, first, true).contains("indisponible"))
        val second = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!).edit().putString("history", "[]").commit()
        assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, second))
        file("horatrack_v2_legacy_recovery_claim").edit().putString("owner", "uid:b").commit()
        assertNull(V2LegacyJournalRecovery.beginDiagnostic(context))
        account(null)
        assertNull(V2LegacyJournalRecovery.beginDiagnostic(context))
    }

    @Test fun diagnosticPreservesOriginalProvenanceAcrossLegacyMergeAndDestinationConflict() {
        file("pointage").edit().putString("data", """[{"id":"v1","arrivalTime":3000,"exitTime":5000,"pauses":[]}]""").commit()
        file(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!).edit()
            .putString("history", """[{"id":"account","realEntry":4000,"realExit":6000,"pauses":[]}]""").commit()
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = files.values.sumOf { it.commits }
        val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        val report = V2LegacyJournalRecovery.diagnose(context, session, true)
        assertTrue(report, report.contains("OVERLAP"))
        assertTrue(report, report.contains("Ancien journal V1 · pointage n° 1"))
        assertTrue(report, report.contains("Journal du compte · pointage n° 1"))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, files.values.sumOf { it.commits })
    }

    @Test fun diagnosticIdentityConflictDoesNotPublishIdentifiersValuesOrUnknownFields() {
        file("pointage").edit().putString("data", """[{"id":"old","arrivalTime":1000,"exitTime":2100,"pauses":[],"placeLabel":"sensitive@example.test 48.123,2.456"}]""").commit()
        val report = V2LegacyJournalRecovery.diagnose(context, V2LegacyJournalRecovery.beginDiagnostic(context)!!, true)
        assertTrue(report, report.contains("IDENTITY_CONFLICT"))
        assertTrue(report.contains("Ancien journal V1 · pointage n° 1"))
        assertTrue(report.contains("Ancien journal V2 · pointage n° 1"))
        assertFalse(report.contains("old")); assertFalse(report.contains("sensitive@")); assertFalse(report.contains("48.123"))
        file("pointage").edit().putString("data", """[{"arrivalTime":1000,"secret@example.test":"48.123,2.456"}]""").commit()
        val unknown = V2LegacyJournalRecovery.diagnose(context, V2LegacyJournalRecovery.beginDiagnostic(context)!!, true)
        assertTrue(unknown.contains("UNSUPPORTED_FIELD"))
        assertFalse(unknown.contains("secret@")); assertFalse(unknown.contains("48.123"))
    }

    @Test fun diagnosticDoesNotRecoverOrDeletePreparedCommittedOrAtomicBackupJournals() {
        val physical = V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:a")!!
        val after = mapOf("history" to "[]")
        val beforeRestore = mapOf("history" to """[{"id":"before","realEntry":4000,"realExit":5000,"pauses":[]}]""")
        file(physical).edit().putString("history", "[]").commit()
        for (state in listOf("PREPARED", "COMMITTED")) for (suffix in listOf("", ".bak", ".new")) {
            val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
            val journal = File(context.noBackupFilesDir, "agkgmg_restore_transaction_v1.json$suffix")
            val record = JSONObject().put("version", 1).put("scope", "uid:a").put("state", state)
                .put("entries", JSONArray().put(JSONObject().put("logical", "horatrack_v2_test_runtime")
                    .put("physical", physical).put("before", V2PreferenceValues.encode(beforeRestore))
                    .put("after", V2PreferenceValues.encode(after))))
            val bytes = record.toString().toByteArray(Charsets.UTF_8)
            journal.writeBytes(bytes)
            try {
                val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
                val commits = files.values.sumOf { it.commits }
                assertNull("$state$suffix", V2LegacyJournalRecovery.beginDiagnostic(context))
                assertFalse(V2LegacyJournalRecovery.diagnosticCurrent(context, session))
                assertTrue(V2LegacyJournalRecovery.diagnose(context, session, true).contains("indisponible"))
                assertArrayEquals(bytes, journal.readBytes())
                assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
                assertEquals(commits, files.values.sumOf { it.commits })
            } finally { journal.delete() }
        }
    }

    @Test fun eligibleDiagnosticDoesNotAssignOrReserveTheJournal() {
        val before = files.mapValues { it.value.all }.filterValues { it.isNotEmpty() }
        val commits = files.values.sumOf { it.commits }
        val session = V2LegacyJournalRecovery.beginDiagnostic(context)!!
        assertTrue(V2LegacyJournalRecovery.diagnose(context, session, true).contains("Aucun conflit détecté"))
        assertTrue(V2RuntimeAccountScope.hasUnassignedHistory(context))
        assertEquals(before, files.mapValues { it.value.all }.filterValues { it.isNotEmpty() })
        assertEquals(commits, files.values.sumOf { it.commits })
    }
    private class FaultPreferences : SharedPreferences {
        private var memory = mutableMapOf<String, Any>()
        private var disk = mapOf<String, Any>()
        var failOnCommit = -1
        var commits = 0
        fun restart() { memory = disk.toMutableMap() }
        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
        override fun contains(key: String): Boolean = memory.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = memory[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            return (memory[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String, defValue: Int) = memory[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = memory[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = memory[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = memory[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            var clear = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) memory.clear()
                changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                commits++
                if (commits == failOnCommit) return false
                disk = memory.toMap()
                return true
            }
            override fun apply() { commit() }
        }
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class RecoveryAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
