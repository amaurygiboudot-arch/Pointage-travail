package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import com.amaury.pointage.GpsDeliveryAuthShadow
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class V2RuntimeAccountIntegrationTest {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account("A")
    }
    private fun account(uid: String?) {
        val user = uid?.let { Mockito.mock(FirebaseUser::class.java).also { user -> Mockito.`when`(user.uid).thenReturn(uid) } }
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }
    @Test fun departurePolicyIsOffByDefaultAndIsolatedByAccountAndEmployer() {
        assertFalse(V2DeparturePolicyStore.enabled(context, "company-a"))
        assertTrue(V2DeparturePolicyStore.setEnabled(context, "company-a", true))
        assertTrue(V2DeparturePolicyStore.enabled(context, "company-a"))
        assertFalse(V2DeparturePolicyStore.enabled(context, "company-b"))
        account("B")
        assertFalse(V2DeparturePolicyStore.enabled(context, "company-a"))
        account("A")
        assertTrue(V2DeparturePolicyStore.enabled(context, "company-a"))
    }

    @Test fun diagnosticExportsCanonicalClosureWithoutRequiringTraceCollection() {
        val now = System.currentTimeMillis()
        val observed = now - 10_000
        assertTrue(V2RuntimeStore.entry(context, now - 60_000))
        assertTrue(V2RuntimeStore.exit(context, observed, origin = com.amaury.pointage.ClosureOriginV2.WIDGET,
            departureObservedAtMs = observed))
        val export = com.amaury.pointage.DailyDiagnosticExportV2.render(context, "uid:A", java.time.LocalDate.now())
        assertTrue(export.contains("Origine canonique : WIDGET"))
        assertTrue(export.contains(com.amaury.pointage.DailyDiagnosticModelV2.timestamp(observed, java.time.ZoneId.systemDefault())))
        assertEquals("UNKNOWN", com.amaury.pointage.DailyDiagnosticExportV2.canonicalOrigin(null))
        assertEquals("UNKNOWN", com.amaury.pointage.DailyDiagnosticExportV2.canonicalOrigin("private arbitrary text"))
        assertEquals("AUTOMATIC", com.amaury.pointage.DailyDiagnosticExportV2.canonicalOrigin("AUTOMATIC"))
        context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
            .edit().putString("history", "unassigned").commit()
        val incomplete = com.amaury.pointage.DailyDiagnosticExportV2.render(context, "uid:A", java.time.LocalDate.now())
        assertTrue(incomplete.contains("SESSION COURANTE DU COMPTE"))
        assertTrue(incomplete.contains("Origine canonique : WIDGET"))
    }

    @Test fun accountSwitchAndGuestNeverExposeOrCloseOtherSession() {
        val arrival = System.currentTimeMillis() - 60_000
        assertTrue(V2RuntimeStore.entry(context, arrival))
        val a = V2RuntimeReader.current(context).snapshot.session
        assertNotNull(a)
        account("B")
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        assertFalse(V2RuntimeStore.exit(context))
        assertTrue(V2RuntimeStore.entry(context, arrival))
        val b = V2RuntimeReader.current(context).snapshot.session
        assertNotEquals(a?.id, b?.id)
        account(null)
        assertNull(V2RuntimeReader.current(context).snapshot.session)
        account("A")
        assertEquals(a?.id, V2RuntimeReader.current(context).snapshot.session?.id)
    }
    @Test fun anotherWorkerCannotTurnIncompleteHistoryIntoReliableZero() {
        context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
            .edit().putString("history", "unassigned").commit()
        assertFalse(V2RuntimeReader.allSessions(context).reliable)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> { V2RuntimeReader.current(context).reliable }.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(V2RuntimeHistoryGuardV2.sourceState().reliable)
            assertFalse(V2RuntimeReader.allSessions(context).reliable)
        } finally { executor.shutdownNow() }
    }

    @Test fun legacyJournalIsPreservedAndBlocksTotalsButNotNewScopedFacts() {
        val global = context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        global.edit().putString("history", "unassigned historical bytes").commit()
        assertTrue(V2RuntimeStore.entry(context, System.currentTimeMillis() - 60_000))
        assertTrue(V2RuntimeReader.current(context).reliable)
        assertNotNull(V2RuntimeReader.current(context).snapshot.session)
        assertFalse(V2RuntimeReader.allSessions(context).reliable)
        assertEquals("unassigned historical bytes", global.getString("history", null))
    }

    @Test fun diagnosticShowsCurrentWithoutCertifyingUnassignedHistoryOrChangingFacts() {
        val global = context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        global.edit().putString("history", "unassigned historical bytes").commit()
        assertTrue(V2RuntimeStore.entry(context, System.currentTimeMillis() - 60_000))
        val scoped = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
        val before = scoped.all.toMap()
        val result = V2RuntimeReader.diagnostic(context)
        assertFalse(result.history.reliable)
        assertTrue(result.history.warnings.contains(V2RuntimeAccountScope.UNASSIGNED_WARNING))
        assertTrue(result.current.reliable)
        assertNotNull(result.current.snapshot.session)
        assertFalse(V2RuntimeHistoryGuardV2.sourceState().reliable)
        val export = com.amaury.pointage.DailyDiagnosticExportV2.render(context, "uid:A", java.time.LocalDate.now())
        assertTrue(export.contains("Source complète et fiable : false"))
        assertTrue(export.contains("SESSION COURANTE DU COMPTE"))
        assertTrue(export.contains(V2RuntimeAccountScope.UNASSIGNED_WARNING))
        assertEquals(before, scoped.all)
        assertEquals("unassigned historical bytes", global.getString("history", null))
        account("B")
        assertNull(V2RuntimeReader.diagnostic(context).current.snapshot.session)
        account("A")
        assertEquals(result.current.snapshot.session?.id, V2RuntimeReader.diagnostic(context).current.snapshot.session?.id)
    }

    @Test fun unassignedHistoryMustNotMaskCorruptAccountJournalInDiagnostic() {
        assertTrue(V2RuntimeStore.entry(context, System.currentTimeMillis() - 60_000))
        context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
            .edit().putString("history", "unassigned").commit()
        val scoped = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
        scoped.edit().putString("history", "broken").commit()
        val before = scoped.all.toMap()
        val result = V2RuntimeReader.diagnostic(context)
        assertFalse(result.history.reliable)
        assertFalse(result.current.reliable)
        assertNull(result.current.snapshot.session)
        assertTrue(result.current.warnings.any { it.contains("illisible ou incohérent") })
        val export = com.amaury.pointage.DailyDiagnosticExportV2.render(context, "uid:A", java.time.LocalDate.now())
        assertTrue(export.contains("Journal du compte courant lisible et cohérent : false"))
        assertFalse(export.contains("SESSION COURANTE DU COMPTE"))
        assertEquals(before, scoped.all)
    }

    @Test fun orphanedCurrentFactsAreNotShownAsReliableEmptyDiagnostic() {
        assertTrue(V2RuntimeStore.entry(context, System.currentTimeMillis() - 60_000))
        val scoped = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
        scoped.edit().remove("real_entry").commit()
        val result = V2RuntimeReader.diagnostic(context)
        assertFalse(result.current.reliable)
        assertNull(result.current.snapshot.session)
    }

    @Test fun diagnosticDoesNotMigrateOldScopedJournalWithUnassignedLegacy() {
        assertDiagnosticLeavesUnmigratedStorageUntouched(unassigned = true)
    }

    @Test fun diagnosticDoesNotMigrateOldScopedJournalWithoutUnassignedLegacy() {
        assertDiagnosticLeavesUnmigratedStorageUntouched(unassigned = false)
    }

    @Test fun diagnosticRejectsUnreadableMigrationVersionWithoutWriting() {
        val meta = V2RuntimeAccountScope.preferences(context, "horatrack_v2_migration")
        assertTrue(meta.edit().putString("version", "invalid").commit())
        val before = meta.all.toMap()
        val read = V2RuntimeReader.diagnostic(context)
        assertFalse(read.current.reliable)
        assertTrue(read.current.warnings.contains(V2RuntimeReader.DIAGNOSTIC_MIGRATION_WARNING))
        assertEquals(before, meta.all)
    }

    @Test fun diagnosticExportRejectsUnreadableCompletedMigrationCountWithoutWriting() {
        val meta = V2RuntimeAccountScope.preferences(context, "horatrack_v2_migration")
        val scoped = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
        val global = context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences("pointage", Context.MODE_PRIVATE)
        val globalMeta = context.getSharedPreferences("horatrack_v2_migration", Context.MODE_PRIVATE)
        assertTrue(meta.edit().putInt("version", 5).putString("legacy_count", "invalid").commit())
        assertTrue(scoped.edit().putString("history", "[]").commit())
        val stores = listOf(meta, scoped, global, legacy, globalMeta)
        val before = stores.map { it.all.toMap() }
        val read = V2RuntimeReader.diagnostic(context)
        assertFalse(read.current.reliable)
        assertTrue(read.current.warnings.contains(V2RuntimeReader.DIAGNOSTIC_MIGRATION_WARNING))
        val export = com.amaury.pointage.DailyDiagnosticExportV2.render(context, "uid:A", java.time.LocalDate.now())
        assertTrue(export.contains(V2RuntimeReader.DIAGNOSTIC_MIGRATION_WARNING))
        assertEquals(before, stores.map { it.all.toMap() })
    }

    private fun assertDiagnosticLeavesUnmigratedStorageUntouched(unassigned: Boolean) {
        // No entry() or normal runtime reader: those would migrate the fixture first.
        val scoped = V2RuntimeAccountScope.preferences(context, "horatrack_v2_test_runtime")
        val meta = V2RuntimeAccountScope.preferences(context, "horatrack_v2_migration")
        val global = context.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences("pointage", Context.MODE_PRIVATE)
        val globalMeta = context.getSharedPreferences("horatrack_v2_migration", Context.MODE_PRIVATE)
        // Missing ID deliberately exercises the former ensureStableHistoryIds write path.
        val raw = """[{"realEntry":1000000,"countedEntry":1000000,"realExit":2000000,"countedExit":2000000,"pauses":[]}]"""
        assertTrue(scoped.edit().putString("history", raw).commit())
        assertTrue(meta.edit().putInt("version", 4).putInt("legacy_count", 7).commit())
        if (unassigned) {
            assertTrue(global.edit().putString("history", "unassigned raw bytes").commit())
            assertTrue(legacy.edit().putString("data", "legacy raw bytes").commit())
        }
        val stores = listOf(scoped, meta, global, legacy, globalMeta)
        val before = stores.map { it.all.toMap() }
        val read = V2RuntimeReader.diagnostic(context)
        assertFalse(read.history.reliable)
        assertFalse(read.current.reliable)
        assertNull(read.current.snapshot.session)
        assertTrue(read.current.warnings.contains(V2RuntimeReader.DIAGNOSTIC_MIGRATION_WARNING))
        assertEquals(unassigned, read.history.warnings.contains(V2RuntimeAccountScope.UNASSIGNED_WARNING))
        val export = com.amaury.pointage.DailyDiagnosticExportV2.render(context, "uid:A", java.time.LocalDate.now())
        assertTrue(export.contains(V2RuntimeReader.DIAGNOSTIC_MIGRATION_WARNING))
        assertFalse(export.contains("SESSION COURANTE DU COMPTE"))
        assertEquals(before, stores.map { it.all.toMap() })
        assertEquals(raw, scoped.getString("history", null))
    }
}
