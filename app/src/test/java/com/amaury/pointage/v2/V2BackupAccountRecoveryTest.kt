package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Looper
import com.amaury.pointage.GpsPresenceStateKeysV2
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyIncomeTaxRateResolverV2
import com.amaury.pointage.v2.engine.DatedWorkRuleV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.engine.WorkRuleConfirmationV2
import com.amaury.pointage.v2.engine.WorkRuleOwnerV2
import com.amaury.pointage.v2.engine.WorkRuleTopicV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.YearMonth

/** Acceptance tests on real stores; only account/provider and disk-commit failures are simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RecoveryAuthShadow::class],
    instrumentedPackages = ["com.google.firebase.auth"])
class V2BackupAccountRecoveryTest {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private var uid: String? = "recovery-owner-a"
    private val scope get() = "uid:$uid"
    private val companyA = "restore_a"
    private val companyB = "restore_b"
    private val fileA = "salary_company_restore_a"
    private val fileB = "salary_company_restore_b"
    private val period = YearMonth.of(2026, 11)

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        val users = mutableMapOf<String, FirebaseUser>()
        Mockito.`when`(auth.currentUser).thenAnswer {
            uid?.let { id -> users.getOrPut(id) {
                Mockito.mock(FirebaseUser::class.java).also { Mockito.`when`(it.uid).thenReturn(id) }
            } }
        }
        RecoveryAuthShadow.auth = auth
        SalaryAccountScope.capture()
        assertTrue(SalaryCompanyStore.createOrUpdate(context, SalaryCompanyStore.Company(companyA, "Synthetic A", "")))
        assertTrue(SalaryCompanyStore.createOrUpdate(context, SalaryCompanyStore.Company(companyB, "Synthetic B", "")))
        assertTrue(CompanyIncomeTaxRateStoreV2.save(context, companyA, rate(2.0)))
        assertTrue(CompanyIncomeTaxRateStoreV2.save(context, companyB, rate(3.0)))
        assertTrue(context.getSharedPreferences("gps_settings", 0).edit()
            .putString("zones", "[]").putBoolean("enabled", false)
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, setOf("synthetic-site"))
            .putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, true).commit())
    }

    @Test fun lateFailureRollsBackSalaryAndGpsIncludingACacheChangedByFailedCommit() {
        val before = beforeMaps()
        val faulty = FaultContext(context)
        var failed = false
        faulty.onCommit = { name, commit ->
            val written = commit()
            if (name == physical(fileB) && !failed) { failed = true; false } else written
        }
        val result = V2BackupManager.restoreFromJson(faulty, backup())
        assertTrue(result.isFailure)
        assertTrue(failed)
        assertEquals(before, beforeMaps())
        assertRate(companyA, 2.0)
        assertRate(companyB, 3.0)
        assertFalse(journal().exists())
        faulty.onCommit = { _, commit -> commit() }
        val retry = V2BackupManager.restoreFromJson(faulty, backup())
        assertTrue(retry.exceptionOrNull()?.stackTraceToString(), retry.isSuccess)
        assertRate(companyA, 9.0)
        assertRate(companyB, 12.0)
        val after = beforeMaps()
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        assertEquals(after, beforeMaps())
    }

    @Test fun unrepairableRollbackBlocksSalaryAndRuntimeUntilDurableRecoverySucceeds() {
        val before = beforeMaps()
        val faulty = FaultContext(context)
        var count = 0
        faulty.onCommit = { _, commit -> if (++count >= 2) false else commit() }
        val result = V2BackupManager.restoreFromJson(faulty, backup())
        assertTrue(result.isFailure)
        assertTrue(journal().exists())
        assertFalse(SalaryCompanyStore.readConfirmed(faulty).reliable)
        assertFalse(V2RuntimeReader.allSessions(faulty).reliable)
        assertFalse(SalaryAccountScope.preferences(faulty, fileA).edit().putString("late", "write").commit())
        faulty.onCommit = { _, commit -> commit() }
        assertTrue(V2RestoreTransaction.recover(faulty))
        assertEquals(before, beforeMaps())
        assertTrue(SalaryCompanyStore.readConfirmed(context).reliable)
        val resumed = V2RuntimeReader.allSessions(context)
        assertTrue("Runtime after recovery: ${resumed.warnings}; runtime=" +
            context.getSharedPreferences(V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", scope)!!, 0).all +
            "; metadata=" + context.getSharedPreferences(V2RuntimeAccountScope.fileName("horatrack_v2_migration", scope)!!, 0).all,
            resumed.reliable)
    }

    @Test fun runtimePreflightUsesItsSuppliedContextAfterAnOlderContextFailsRecovery() {
        val otherData = File(context.cacheDir, "other-storage-context").apply { mkdirs() }
        val other = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(otherData, "no_backup").apply { mkdirs() }
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(baseContext.applicationInfo).apply {
                dataDir = otherData.absolutePath
            }
        }
        File(other.noBackupFilesDir, "agkgmg_restore_transaction_v1.json").writeText("{invalid prior context}")
        V2RuntimeAccountScope.preferences(other, "horatrack_v2_test_runtime")
        assertFalse(V2RuntimeAccountScope.available(other))
        val migration = V2MigrationManager.ensureMigrated(context)
        assertTrue("Current context must not inherit another context's recovery failure: ${migration.warnings}", migration.reliable)
        assertTrue(V2RuntimeReader.allSessions(context).reliable)
        assertTrue(File(other.noBackupFilesDir, "agkgmg_restore_transaction_v1.json").exists())
    }

    @Test fun abruptInterruptionAfterEachFileIsRecoveredFromTheDurableJournal() {
        val initial = beforeMaps()
        val names = listOf(fileA, fileB, "gps_settings")
        for (stopAfter in 1..names.size) {
            val lease = SalaryAccountScope.capture()
            val faulty = FaultContext(context)
            var count = 0
            faulty.onCommit = { _, commit ->
                commit().also { if (++count == stopAfter) throw SimulatedProcessDeath() }
            }
            var interrupted = false
            try {
                V2RestoreTransaction.execute(faulty, scope, { SalaryAccountScope.isCurrent(lease) }) {
                    linkedMapOf(fileA to mapOf("replacement" to "A"), fileB to mapOf("replacement" to "B"),
                        "gps_settings" to mapOf("zones" to "[]"))
                }
            } catch (_: SimulatedProcessDeath) { interrupted = true }
            assertTrue(interrupted)
            assertTrue(journal().exists())
            assertNotEquals(initial, beforeMaps())
            // New context, no call-stack compensation: startup recovery uses only the on-disk journal.
            assertTrue(V2RestoreTransaction.recover(ContextWrapper(context)))
            assertEquals(initial, beforeMaps())
            assertFalse(journal().exists())
        }
    }

    @Test fun accountSwitchAfterFirstWriteRollsBackOnlyTheOriginalOwner() {
        val ownerA = scope
        val before = beforeMaps()
        uid = "recovery-owner-b"
        SalaryAccountScope.capture()
        val bPrefs = SalaryAccountScope.preferences(context, fileA)
        assertTrue(bPrefs.edit().putString("private-b", "untouched").commit())
        val bPhysical = physical(fileA)
        val bBefore = context.getSharedPreferences(bPhysical, 0).all.toMap()
        uid = "recovery-owner-a"
        SalaryAccountScope.capture()
        val faulty = FaultContext(context)
        var switched = false
        faulty.onCommit = { name, commit -> commit().also {
            if (name == SalaryPreferenceRegistry.fileName(fileA, ownerA) && !switched) {
                switched = true; uid = "recovery-owner-b"; SalaryAccountScope.capture()
            }
        } }
        assertTrue(V2BackupManager.restoreFromJson(faulty, backup(ownerA)).isFailure)
        assertEquals(bBefore, context.getSharedPreferences(bPhysical, 0).all)
        assertEquals(before, beforeMaps(ownerA))
        assertFalse(journal().exists())
        assertEquals("untouched", SalaryAccountScope.preferences(context, fileA).getString("private-b", null))
    }

    @Test fun anIndependentSettingsEditIsPreservedAndLeavesRestoreExplicitlyBlocked() {
        val faulty = FaultContext(context)
        var edited = false
        faulty.onCommit = { name, commit -> commit().also {
            if (name == physical(fileA) && !edited) {
                edited = true
                context.getSharedPreferences("gps_settings", 0).edit().putString("concurrent_setting", "new user choice").commit()
            }
        } }
        val result = V2BackupManager.restoreFromJson(faulty, backup())
        assertTrue(result.isFailure)
        assertEquals("new user choice", context.getSharedPreferences("gps_settings", 0).getString("concurrent_setting", null))
        assertTrue(journal().exists())
        assertFalse(SalaryCompanyStore.readConfirmed(context).reliable)
        assertFalse(V2RuntimeReader.allSessions(context).reliable)
    }

    @Test fun successfulPublicationInvalidatesCapturedEditorsAndReadContexts() {
        val oldContext = SalaryAccountScope.bindContext(context)
        val oldEditor = SalaryAccountScope.preferences(oldContext, fileA).edit().putString("late", "A draft")
        val runtimeEditor = V2RuntimeAccountScope.preferences(oldContext, "horatrack_v2_test_runtime").edit().putString("late", "runtime")
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        assertFalse(SalaryAccountScope.available(oldContext))
        assertFalse(SalaryCompanyStore.readConfirmed(oldContext).reliable)
        assertFalse(oldEditor.commit())
        assertFalse(runtimeEditor.commit())
        assertRate(companyA, 9.0)
    }

    @Test fun runtimeListenersCannotReceiveAForeignDelegateAndFreshListenersWorkAfterPublication() {
        val name = "horatrack_v2_test_runtime"
        val old = V2RuntimeAccountScope.preferences(context, name)
        val received = mutableListOf<String?>()
        val oldListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "private_fixture") received += p.getString(key, null)
        }
        old.registerOnSharedPreferenceChangeListener(oldListener)
        val oldScope = scope
        uid = "recovery-owner-b"
        SalaryAccountScope.capture()
        context.getSharedPreferences(V2RuntimeAccountScope.fileName(name, oldScope)!!, 0).edit()
            .putString("private_fixture", "A private value").commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(received.isEmpty())
        uid = "recovery-owner-a"
        SalaryAccountScope.capture()
        assertTrue(V2BackupManager.restoreFromJson(context, backup()).isSuccess)
        val fresh = V2RuntimeAccountScope.preferences(context, name)
        val newListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "private_fixture") received += p.getString(key, null)
        }
        fresh.registerOnSharedPreferenceChangeListener(newListener)
        assertTrue(fresh.edit().putString("private_fixture", "fresh A value").commit())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("fresh A value"), received)
        old.unregisterOnSharedPreferenceChangeListener(oldListener)
        fresh.unregisterOnSharedPreferenceChangeListener(newListener)
    }

    @Test fun wrongOwnerMalformedPayloadAndCorruptRecoveryJournalFailClosed() {
        val before = beforeMaps()
        val observed = FaultContext(context)
        var attempts = 0
        observed.onCommit = { _, commit -> attempts++; commit() }
        assertTrue(V2BackupManager.restoreFromJson(observed, backup("uid:foreign")).isFailure)
        val malformed = JSONObject(backup()).apply {
            getJSONObject("preferences").getJSONObject(fileB).put("broken", JSONObject().put("t", "l").put("v", "bad"))
        }
        assertTrue(V2BackupManager.restoreFromJson(observed, malformed.toString()).isFailure)
        assertEquals(0, attempts)
        assertEquals(before, beforeMaps())
        journal().writeText("{interrupted-or-corrupt")
        assertFalse(V2RestoreTransaction.recover(context))
        assertFalse(SalaryCompanyStore.readConfirmed(context).reliable)
        assertFalse(V2RuntimeReader.allSessions(context).reliable)
        assertTrue(journal().exists())
    }

    @Test fun snapshotRestoresSicknessContractsAndDatedRulesOnAnEmptyOwnedTarget() {
        val (cash, contract, rule) = seedEvidence()
        val snapshot = V2BackupManager.snapshot(context)
        val files = snapshot.getJSONObject("preferences")
        listOf(SalaryBackupPayloadsV2.SICKNESS, SalaryBackupPayloadsV2.CONTRACTS, SalaryBackupPayloadsV2.DATED_RULES)
            .forEach { assertTrue(it, files.getJSONObject(it).length() > 0) }
        assertFalse(files.has("horatrack_v2_meal_fact_journal"))
        assertFalse(files.has("horatrack_local_provident_documents"))
        SalaryPreferenceRegistry.personalNames(context, scope).forEach { name ->
            assertTrue(SalaryAccountScope.preferences(context, name).edit().clear().commit())
        }
        val restored = V2BackupManager.restoreFromJson(context, snapshot.toString())
        assertTrue(restored.exceptionOrNull()?.stackTraceToString(), restored.isSuccess)
        assertEquals(cash, ConfirmedMonthlySicknessCashV2.read(context, companyA, period.toString()).record)
        assertEquals(listOf(contract), V2EmploymentContractHistoryStore.readConfirmed(context).snapshots)
        assertEquals(listOf(rule), DatedWorkRuleStoreV2.read(context, rule.owner).records)
        assertRate(companyA, 2.0)
        assertTrue(V2BackupManager.restoreFromJson(context, snapshot.toString()).isSuccess)
    }

    @Test fun snapshotNeverEnumeratesAnotherOwnersCompanyFilesOrDatedEnvelopes() {
        val (_, _, ruleA) = seedEvidence()
        val ownerA = scope
        uid = "recovery-owner-b"
        SalaryAccountScope.capture()
        val (_, _, ruleB) = seedEvidence()
        assertTrue(SalaryCompanyStore.createOrUpdate(context, SalaryCompanyStore.Company("only_b", "Private B", "")))
        assertTrue(CompanyIncomeTaxRateStoreV2.save(context, "only_b", rate(28.0)))
        uid = "recovery-owner-a"
        SalaryAccountScope.capture()
        val raw = V2BackupManager.snapshot(context).toString()
        val files = JSONObject(raw).getJSONObject("preferences")
        assertEquals(ownerA, JSONObject(raw).getString("salaryOwner"))
        assertFalse(files.has("salary_company_only_b"))
        val rules = V2PreferenceValues.decode(files.getJSONObject(SalaryBackupPayloadsV2.DATED_RULES))
        assertTrue(rules.containsKey(DatedWorkRuleStoreV2.ownerStorageKey(ruleA.owner)))
        assertFalse(rules.containsKey(DatedWorkRuleStoreV2.ownerStorageKey(ruleB.owner)))
        assertFalse(raw.contains("Private B"))
    }

    @Test fun conflictingContractAndForeignDatedOwnerAreRejectedBeforePublication() {
        val (_, contract, rule) = seedEvidence()
        val snapshot = V2BackupManager.snapshot(context)
        assertTrue(V2EmploymentContractHistoryStore.saveConfirmed(context,
            contract.copy(contract = contract.contract.copy(grossHourlyRate = 21.0))))
        val before = SalaryAccountScope.preferences(context, SalaryBackupPayloadsV2.CONTRACTS).all.toMap()
        assertTrue(V2BackupManager.restoreFromJson(context, snapshot.toString()).isFailure)
        assertEquals(before, SalaryAccountScope.preferences(context, SalaryBackupPayloadsV2.CONTRACTS).all)
        assertFalse(journal().exists())
        val foreign = rule.owner.copy(accountId = "foreign", employeeId = "foreign")
        val injected = JSONObject(backup()).apply {
            getJSONObject("preferences").put(SalaryBackupPayloadsV2.DATED_RULES, V2PreferenceValues.encode(mapOf(
                DatedWorkRuleStoreV2.ownerStorageKey(foreign) to DatedWorkRuleStoreV2.encode(foreign, listOf(rule.copy(owner = foreign))))))
        }
        assertTrue(V2BackupManager.restoreFromJson(context, injected.toString()).isFailure)
        assertRate(companyA, 2.0)
        assertFalse(journal().exists())
    }

    @Test fun oldOwnedDatedRulesAreIncludedImmediatelyAfterExplicitLegacyClaim() {
        val (_, _, ruleA) = seedEvidence()
        val ownerA = scope
        val sources = SalaryPreferenceRegistry.transferableNames(context, ownerA).associateWith {
            SalaryAccountScope.preferences(context, it).all.toMap()
        }
        val legacyDated = context.getSharedPreferences(SalaryBackupPayloadsV2.DATED_RULES, 0)
        val foreignOwner = ruleA.owner.copy(accountId = "other-owner", employeeId = "other-owner")
        val foreignRule = ruleA.copy(owner = foreignOwner, id = "other-rule")
        assertTrue(legacyDated.edit()
            .putString(DatedWorkRuleStoreV2.ownerStorageKey(ruleA.owner), DatedWorkRuleStoreV2.encode(ruleA.owner, listOf(ruleA)))
            .putString(DatedWorkRuleStoreV2.ownerStorageKey(foreignOwner), DatedWorkRuleStoreV2.encode(foreignOwner, listOf(foreignRule))).commit())
        sources.filterKeys { it != SalaryBackupPayloadsV2.DATED_RULES }.forEach { (name, values) ->
            assertTrue(V2PreferenceValues.replace(context.getSharedPreferences(name, 0), values))
        }
        SalaryPreferenceRegistry.personalNames(context, ownerA).forEach {
            assertTrue(SalaryAccountScope.preferences(context, it).edit().clear().commit())
        }
        val original = legacyDated.all.toMap()
        val preview = requireNotNull(SalaryLegacyRecovery.preview(context))
        assertTrue(SalaryLegacyRecovery.claim(context, preview.digest, true))
        // No individual rule has been opened/read since claim.
        val snapshot = V2BackupManager.snapshot(context)
        val rules = V2PreferenceValues.decode(snapshot.getJSONObject("preferences").getJSONObject(SalaryBackupPayloadsV2.DATED_RULES))
        assertTrue(rules.containsKey(DatedWorkRuleStoreV2.ownerStorageKey(ruleA.owner)))
        assertFalse(rules.containsKey(DatedWorkRuleStoreV2.ownerStorageKey(foreignOwner)))
        assertEquals(original, legacyDated.all)
    }

    @Test fun legacyBackupRequiresExplicitAttributionAndCannotBeClaimedByAnotherAccount() {
        val legacy = JSONObject(backup()).put("formatVersion", 5).apply {
            remove("salaryOwnership"); remove("salaryOwner")
            getJSONObject("preferences").put("salary_companies_v2",
                V2PreferenceValues.encode(SalaryAccountScope.preferences(context, "salary_companies_v2").all))
        }.toString(2)
        val before = beforeMaps()
        val result = V2BackupManager.restoreFromJson(context, legacy).getOrThrow()
        assertEquals(3, result.quarantinedSalaryFiles)
        assertEquals(before, beforeMaps())
        val preview = V2BackupManager.legacySalaryRecoveries(context).single()
        val original = File(context.noBackupFilesDir, "agkgmg_salary_recovery/${preview.id}.json")
        assertEquals(legacy, original.readText())
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(context, preview.id, preview.fingerprint, true).isFailure)
        SalaryPreferenceRegistry.personalNames(context, scope).forEach { SalaryAccountScope.preferences(context, it).edit().clear().commit() }
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(context, preview.id, preview.fingerprint, false).isFailure)
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(context, preview.id, preview.fingerprint, true).isSuccess)
        assertEquals(legacy, original.readText())
        val claimed = beforeMaps()
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(context, preview.id, preview.fingerprint, true).isSuccess)
        assertEquals(claimed, beforeMaps())
        uid = "recovery-owner-b"
        SalaryAccountScope.capture()
        assertTrue(V2BackupManager.legacySalaryRecoveries(context).isEmpty())
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(context, preview.id, preview.fingerprint, true).isFailure)
    }

    @Test fun legacyCopiesArePreservedBeforeDriveOrCloudReplacementAndIoFailureBlocksReplacement() {
        val first = JSONObject(backup()).put("formatVersion", 5).toString(3)
        val second = JSONObject(first).put("createdAtMs", 17L).toString(1)
        val firstId = requireNotNull(V2BackupManager.preservePriorBackup(context, first, scope))
        val secondId = requireNotNull(V2BackupManager.preservePriorBackup(context, second, scope))
        assertNotEquals(firstId, secondId)
        assertEquals(firstId, V2BackupManager.preservePriorBackup(context, first, scope))
        val directory = File(context.noBackupFilesDir, "agkgmg_salary_recovery")
        assertEquals(first, File(directory, "$firstId.json").readText())
        assertEquals(second, File(directory, "$secondId.json").readText())
        File(directory, "$firstId.json").writeText("changed")
        assertTrue(runCatching { V2BackupManager.preservePriorBackup(context, first, scope) }.isFailure)
        assertEquals("changed", File(directory, "$firstId.json").readText())
        assertEquals(second, File(directory, "$secondId.json").readText())
    }

    private fun seedEvidence(): Triple<ConfirmedMonthlySicknessCashV2.Record, EmploymentContractSnapshotV2, DatedWorkRuleV2> {
        if (SalaryCompanyStore.readConfirmed(context).companies.none { it.id == companyA })
            assertTrue(SalaryCompanyStore.createOrUpdate(context, SalaryCompanyStore.Company(companyA, "Synthetic A", "")))
        val cash = ConfirmedMonthlySicknessCashV2.Record(companyA, period.toString(), 123.45, null, "Synthetic IJSS proof", 1_700_000_000_000L)
        assertTrue(ConfirmedMonthlySicknessCashV2.save(context, cash, true))
        val contract = EmploymentContractSnapshotV2("synthetic-version", "synthetic-contract", 20_000L, null,
            ContractV2("synthetic-contract", companyA, ContractTypeV2.FULL_TIME, 2100, 20.0, 20_000L), 1_700_000_000_000L)
        assertTrue(V2EmploymentContractHistoryStore.saveConfirmed(context, contract))
        val owner = WorkRuleOwnerV2(requireNotNull(uid), requireNotNull(uid), companyA, contract.versionId)
        val rule = DatedWorkRuleV2("synthetic-rule", owner, WorkRuleTopicV2.TIME_ACCOUNTING, 20_000L, null,
            contract.sourceId, "contract:${contract.versionId}", contract.checkedAtMs, WorkRuleConfirmationV2.CONFIRMED)
        assertTrue(DatedWorkRuleStoreV2.append(context, owner, rule))
        return Triple(cash, contract, rule)
    }

    private fun rate(percent: Double) = CompanyIncomeTaxRateResolverV2.Record("synthetic-rate", percent, period, source = "Synthetic fixture")
    private fun assertRate(company: String, expected: Double) {
        val resolved = CompanyIncomeTaxRateStoreV2.resolve(context, company, period)
        assertTrue(resolved.reliable)
        assertEquals(expected, requireNotNull(resolved.ratePercent), 0.0)
    }
    private fun physical(name: String, owner: String = scope) = if (SalaryPreferenceRegistry.isPersonal(name))
        requireNotNull(SalaryPreferenceRegistry.fileName(name, owner)) else name
    private fun beforeMaps(owner: String = scope): Map<String, Map<String, *>> =
        listOf(fileA, fileB, "gps_settings", "salary_companies_v2").associateWith { context.getSharedPreferences(physical(it, owner), 0).all.toMap() }
    private fun journal() = File(context.noBackupFilesDir, "agkgmg_restore_transaction_v1.json")
    private fun backup(owner: String = scope): String {
        fun payload(percent: Double) = JSONObject().put("income_tax_rates_v2", JSONObject().put("t", "s").put("v",
            JSONArray().put(JSONObject().put("id", "synthetic-rate").put("ratePercent", percent)
                .put("effectiveFrom", period.toString()).put("effectiveTo", JSONObject.NULL).put("source", "Synthetic fixture")).toString()))
        return JSONObject().put("formatVersion", 6).put("accountScope", owner).put("salaryOwner", owner)
            .put("salaryOwnership", "account-scoped-v1")
            .put("preferences", JSONObject().put(fileA, payload(9.0)).put(fileB, payload(12.0))).toString()
    }
    private class SimulatedProcessDeath : Error("synthetic abrupt process stop")
    private class FaultContext(base: Context) : ContextWrapper(base) {
        var onCommit: (String, () -> Boolean) -> Boolean = { _, commit -> commit() }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit() = onCommit(name) { editor.commit() }
                    }
                }
            }
        }
    }
}
