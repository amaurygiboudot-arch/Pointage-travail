package com.amaury.pointage

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2SegmentedSalaryCanonicalBridge
import android.net.Uri
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.SalaryPreferenceRegistry
import com.amaury.pointage.v2.SalaryLegacyRecovery
import android.view.ContextThemeWrapper
import com.amaury.pointage.v2.CompanyIncomeTaxRateStoreV2
import com.amaury.pointage.v2.CompanyPremiumStoreV2
import com.amaury.pointage.v2.ConfirmedMonthlySicknessCashV2
import com.amaury.pointage.v2.DatedWorkRuleStoreV2
import com.amaury.pointage.v2.PayslipObservedValuesStoreV2
import com.amaury.pointage.v2.V2BackupManager
import com.amaury.pointage.v2.V2EmploymentContractHistoryStore
import com.amaury.pointage.v2.V2PayslipStore
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.CompanyIncomeTaxRateResolverV2
import com.amaury.pointage.v2.engine.CompanyPremiumResolverV2
import com.amaury.pointage.v2.engine.DatedWorkRuleV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
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
import org.robolectric.annotation.Config
import java.io.File
import java.time.YearMonth

/** Real stores and backup boundary; only Firebase identity is simulated. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class],
    instrumentedPackages = ["com.google.firebase.auth"]
)
class SalaryAccountIsolationV2Test {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private val period = YearMonth.of(2026, 11)
    private val company = SalaryCompanyStore.Company(
        id = "salary-audit-company-a",
        name = "Synthetic employer for account A",
        siret = "",
        address = "Synthetic address A"
    )
    private val companyPrefs = "salary_company_salary-audit-company-a"
    private val accountA = "salary-audit-A"
    private val accountB = "salary-audit-B"
    private val rateA = CompanyIncomeTaxRateResolverV2.Record(
        id = "synthetic-personal-tax-a", ratePercent = 7.5,
        effectiveFrom = period, source = "Synthetic tax notice A"
    )

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account(accountA)
        val names = mutableListOf(
            "salary_companies_v2", companyPrefs, "salary_settings",
            "horatrack_v2_payslips", "horatrack_v2_test_runtime", "pointage",
            "horatrack_v2_legacy_recovery_claim", "salary_confirmed_sickness_cash_v2",
            "horatrack_v2_employment_contract_history", "dated_work_applicability_v2"
        )
        names += "salary_legacy_recovery_owner"
        listOf("uid:$accountA", "uid:$accountB", "guest").forEach { scope ->
            (SalaryPreferenceRegistry.personalFiles + companyPrefs).forEach { base ->
                names += requireNotNull(SalaryPreferenceRegistry.fileName(base, scope))
            }
            listOf("horatrack_v2_test_runtime", "horatrack_v2_migration").forEach { base ->
                names += requireNotNull(V2RuntimeAccountScope.fileName(base, scope))
            }
        }
        names.forEach { assertTrue(context.getSharedPreferences(it, 0).edit().clear().commit()) }
    }

    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        assertEquals("uid:$uid", V2RuntimeAccountScope.currentScope())
        SalaryAccountScope.capture()
    }

    private fun seedSalaryForA(): V2PayslipStore.Record {
        assertEquals("uid:$accountA", V2RuntimeAccountScope.currentScope())
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company))
        assertTrue(CompanyIncomeTaxRateStoreV2.save(context, company.id, rateA))
        assertTrue(CompanyPremiumStoreV2.save(context, company.id,
            CompanyPremiumResolverV2.Record(
                id = "synthetic-personal-premium-a", label = "Synthetic personal premium A",
                grossAmount = 123.45, kind = CompanyPremiumResolverV2.Kind.MONTHLY,
                effectiveFrom = period
            )))
        assertTrue(CompanyPremiumStoreV2.confirmMonth(
            context, company.id, period, "Synthetic exhaustive declaration A"
        ))
        // These are the actual contract preference keys used by SalaryCompanyStore migration.
        assertTrue(SalaryCompanyStore.prefs(context, company.id).edit()
            .putString("hourly_rate", "19.25")
            .putString("contract_weekly_hours", "35")
            .putString("contract_type", "CDI")
            .commit())
        val payslip = requireNotNull(V2PayslipStore.add(
            context = context, year = 2026, month = 10,
            uri = Uri.parse("content://salary-audit.invalid/account-a/payslip-2026-11.pdf"),
            mime = "application/pdf", gross = 3012.34, net = 2312.34,
            confirmedByUser = true, companyId = company.id
        ))
        assertTrue(PayslipObservedValuesStoreV2.put(context, payslip.id,
            mapOf(PayslipDocumentParserV2.KEY_NET_TAXABLE to 2456.78),
            mapOf(PayslipDocumentParserV2.KEY_NET_TAXABLE to "Synthetic excerpt A: net taxable 2456.78")))
        return payslip
    }

    private data class DetailedSources(
        val contract: EmploymentContractSnapshotV2,
        val sickness: ConfirmedMonthlySicknessCashV2.Record,
        val rule: DatedWorkRuleV2
    )

    private fun seedDetailedSourcesForA(): DetailedSources {
        assertEquals("uid:$accountA", V2RuntimeAccountScope.currentScope())
        val firstDay = period.atDay(1).toEpochDay()
        val contract = EmploymentContractSnapshotV2(
            versionId = "synthetic-contract-a-v1", sourceId = "Synthetic signed contract A",
            effectiveFromEpochDay = firstDay,
            contract = ContractV2(
                id = "synthetic-contract-a", employerId = company.id,
                type = ContractTypeV2.FULL_TIME, contractualWeeklyMinutes = 2100,
                grossHourlyRate = 19.25, hireDateEpochDay = firstDay
            ), checkedAtMs = 123456L
        )
        assertTrue(V2EmploymentContractHistoryStore.saveConfirmed(context, contract))
        val sickness = ConfirmedMonthlySicknessCashV2.Record(
            company.id, period.toString(), 82.34, 19.56,
            "Synthetic observed IJSS transfer A", 123456L
        )
        assertTrue(ConfirmedMonthlySicknessCashV2.save(context, sickness, confirmed = true))
        val rule = DatedWorkRuleV2(
            id = "synthetic-dated-rule-a", owner = WorkRuleOwnerV2(accountA, accountA, company.id, contract.versionId),
            topic = WorkRuleTopicV2.TIME_ACCOUNTING, effectiveFromEpochDay = firstDay,
            effectiveToEpochDay = null, sourceId = contract.sourceId,
            ruleReference = "contract:${contract.versionId}", checkedAtMs = contract.checkedAtMs,
            confirmation = WorkRuleConfirmationV2.CONFIRMED
        )
        assertTrue(DatedWorkRuleStoreV2.append(context, rule.owner, rule))
        val rules = DatedWorkRuleStoreV2.read(context, rule.owner)
        assertTrue(rules.reliable)
        assertEquals(listOf(rule), rules.records)
        return DetailedSources(contract, sickness, rule)
    }

    @Test fun initialAuthListenerDeliveryDoesNotExpireTheNewSession() {
        auth = Mockito.mock(FirebaseAuth::class.java)
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(accountA)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<FirebaseAuth.AuthStateListener>(0).onAuthStateChanged(auth)
            null
        }.`when`(auth).addAuthStateListener(Mockito.any(FirebaseAuth.AuthStateListener::class.java))
        GpsDeliveryAuthShadow.auth = auth
        val lease = SalaryAccountScope.capture()
        assertTrue(SalaryAccountScope.isCurrent(lease))
        assertTrue(SalaryAccountScope.isCurrent(lease))
    }

    @Test fun boundProfileExpiresAndANewOperationBindsTheNewAccountWithoutRevivingOldForms() {
        seedSalaryForA()
        assertTrue(SalaryCompanyStore.prefs(context, company.id).edit().putString("contract_type", "FULL_TIME").commit())
        val oldForm = SalaryAccountScope.bindContext(context)
        V2ProfileStore.bind(context)
        assertEquals(19.25, requireNotNull(V2ProfileStore.loadBound()?.contract?.grossHourlyRate), 0.0)
        account(accountB)
        assertNull(V2ProfileStore.loadBound())
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company.copy(name = "New B company")))
        assertTrue(SalaryCompanyStore.prefs(context, company.id).edit().putString("contract_type", "FULL_TIME")
            .putString("contract_weekly_hours", "35").putString("hourly_rate", "99").commit())
        assertEquals(99.0, requireNotNull(V2ProfileStore.load(context).contract?.grossHourlyRate), 0.0)
        assertNull(V2ProfileStore.load(oldForm).contract)
        assertEquals(99.0, requireNotNull(V2ProfileStore.loadBound()?.contract?.grossHourlyRate), 0.0)
    }

    private fun switchingContractContext(onSwitch: () -> Unit): Context = object : ContextWrapper(context) {
        private var fired = false
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            if (!fired && name == SalaryPreferenceRegistry.fileName("horatrack_v2_employment_contract_history", "uid:$accountA")) {
                fired = true
                account(accountB)
                onSwitch()
            }
            return preferences
        }
    }

    @Test fun directMonthlyAndNetPipelinesDiscardAllAmountsWhenAccountChangesBetweenSourceReads() {
        seedSalaryForA()
        seedDetailedSourcesForA()
        val convention = ConventionCatalog.Convention("0292", "Synthetic", "Synthetic legal fixture")
        var switched = false
        val gross = V2SalaryAdapter.calculateForCompany(switchingContractContext { switched = true }, company, 2026, 10, convention)
        assertTrue(switched)
        assertFalse(gross.monthlyGrossReliable)
        assertFalse(gross.paidTimeReliable)
        assertEquals(0.0, gross.monthlyEstimatedGross, 0.0)
        assertTrue(gross.warnings.contains(SalaryAccountScope.AUTH_WARNING))
        account(accountA)
        switched = false
        val net = V2SalaryNetBridgeV2.calculateForCompany(switchingContractContext { switched = true }, company, 2026, 10, convention)
        assertTrue(switched)
        assertNull(net.netBeforeIncomeTax)
        assertNull(net.netAfterIncomeTax)
        assertNull(net.mutualEmployeeAmount)
        assertNull(net.confirmedSicknessCash)
        assertTrue(net.warnings.contains(SalaryAccountScope.AUTH_WARNING))
    }

    @Test fun directSegmentedPipelineDiscardsOutputAfterMidReadAccountSwitch() {
        seedSalaryForA()
        seedDetailedSourcesForA()
        val scopedCompany = company.copy(idcc = "0292")
        assertTrue(SalaryCompanyStore.createOrUpdate(context, scopedCompany))
        var switched = false
        val result = V2SegmentedSalaryCanonicalBridge.calculateForCompany(
            switchingContractContext { switched = true }, scopedCompany, 2026, 10, "Europe/Paris")
        assertTrue(switched)
        assertNull(result.output)
        assertTrue(result.warnings.contains(SalaryAccountScope.AUTH_WARNING))
    }

    @Test fun sameCompanyAndRecordIdsRemainSeparateAcrossAccounts() {
        val payslipA = seedSalaryForA()
        val sourcesA = seedDetailedSourcesForA()
        account(accountB)
        assertTrue(SalaryCompanyStore.readConfirmed(context).companies.isEmpty())
        assertTrue(V2PayslipStore.forCompany(context, company.id).isEmpty())
        assertTrue(PayslipObservedValuesStoreV2.getAll(context, payslipA.id).isEmpty())
        assertTrue(PayslipObservedValuesStoreV2.evidence(context, payslipA.id).isEmpty())
        assertTrue(V2EmploymentContractHistoryStore.readConfirmed(context).snapshots.isEmpty())
        assertNull(ConfirmedMonthlySicknessCashV2.read(context, company.id, period.toString()).record)
        assertFalse(DatedWorkRuleStoreV2.read(context, sourcesA.rule.owner).reliable)
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company.copy(name = "Independent B")))
        val rateB = rateA.copy(ratePercent = 12.0, source = "B own tax")
        assertTrue(CompanyIncomeTaxRateStoreV2.save(context, company.id, rateB))
        val contractB = sourcesA.contract.copy(contract = sourcesA.contract.contract.copy(grossHourlyRate = 99.0))
        assertTrue(V2EmploymentContractHistoryStore.saveConfirmed(context, contractB))
        val sicknessB = sourcesA.sickness.copy(directEmployeeNetBeforeTax = 999.0)
        assertTrue(ConfirmedMonthlySicknessCashV2.save(context, sicknessB, true))
        account(accountA)
        assertEquals(company, SalaryCompanyStore.readConfirmed(context).companies.single())
        assertEquals(rateA, CompanyIncomeTaxRateStoreV2.read(context, company.id).records.single())
        assertEquals(sourcesA.contract, V2EmploymentContractHistoryStore.readConfirmed(context).snapshots.single())
        assertEquals(sourcesA.sickness, ConfirmedMonthlySicknessCashV2.read(context, company.id, period.toString()).record)
        assertEquals(payslipA, V2PayslipStore.forCompany(context, company.id).single())
        assertEquals(sourcesA.rule, DatedWorkRuleStoreV2.read(context, sourcesA.rule.owner).records.single())
    }

    @Test fun guestUsesSeparateDataAndCannotClaimLegacy() {
        seedSalaryForA()
        val sourcesA = seedDetailedSourcesForA()
        Mockito.`when`(auth.currentUser).thenReturn(null)
        SalaryAccountScope.capture()
        assertTrue(SalaryCompanyStore.readConfirmed(context).companies.isEmpty())
        assertTrue(V2EmploymentContractHistoryStore.readConfirmed(context).snapshots.isEmpty())
        assertNull(ConfirmedMonthlySicknessCashV2.read(context, company.id, period.toString()).record)
        assertFalse(SalaryLegacyRecovery.claim(context, "missing", true))
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company.copy(name = "Guest")))
        assertTrue(ConfirmedMonthlySicknessCashV2.save(context, sourcesA.sickness.copy(directEmployeeNetBeforeTax = 8.0), true))
        account(accountA)
        assertEquals(company, SalaryCompanyStore.readConfirmed(context).companies.single())
        assertEquals(sourcesA.sickness, ConfirmedMonthlySicknessCashV2.read(context, company.id, period.toString()).record)
    }

    @Test fun editorsAndBoundFormsCannotCommitAfterSwitchOrReturnToSameUid() {
        seedSalaryForA()
        val formContext = ContextThemeWrapper(SalaryAccountScope.bindContext(context), 0)
        val editor = SalaryCompanyStore.prefs(formContext, company.id).edit().putString("hourly_rate", "88")
        account(accountB)
        assertFalse(editor.commit())
        assertFalse(SalaryCompanyStore.readConfirmed(formContext).reliable)
        assertTrue(SalaryCompanyStore.readConfirmed(formContext).companies.isEmpty())
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company.copy(name = "B")))
        assertFalse(SalaryCompanyStore.prefs(formContext, company.id).edit().putString("hourly_rate", "99").commit())
        account(accountA)
        assertFalse(editor.commit())
        assertFalse(CompanyIncomeTaxRateStoreV2.save(formContext, company.id, rateA.copy(ratePercent = 77.0)))
        assertEquals("19.25", SalaryCompanyStore.prefs(context, company.id).getString("hourly_rate", null))
        assertEquals(rateA, CompanyIncomeTaxRateStoreV2.read(context, company.id).records.single())
    }

    @Test fun unavailableAuthenticationIsUnreliableAndPreservesExistingData() {
        seedSalaryForA()
        seedDetailedSourcesForA()
        Mockito.doThrow(IllegalStateException("Synthetic auth unavailable")).`when`(auth).currentUser
        assertFalse(SalaryCompanyStore.readConfirmed(context).reliable)
        assertFalse(V2EmploymentContractHistoryStore.readConfirmed(context).reliable)
        assertFalse(ConfirmedMonthlySicknessCashV2.read(context, company.id, period.toString()).reliable)
        assertFalse(V2PayslipStore.readResult(context).reliable)
        assertFalse(PayslipObservedValuesStoreV2.readResult(context).reliable)
        assertFalse(SalaryCompanyStore.prefs(context, company.id).edit().clear().commit())
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(accountA)
        Mockito.doReturn(user).`when`(auth).currentUser
        assertEquals(rateA, CompanyIncomeTaxRateStoreV2.read(context, company.id).records.single())
    }

    @Test fun snapshotOnlyEnumeratesCurrentOwnerFiles() {
        seedSalaryForA()
        account(accountB)
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company.copy(name = "B private marker")))
        assertTrue(SalaryCompanyStore.prefs(context, "only_B").edit().putString("private", "B secret marker").commit())
        account(accountA)
        val json = V2BackupManager.snapshot(context).toString()
        assertFalse(json.contains("B private marker"))
        assertFalse(json.contains("B secret marker"))
        assertFalse(json.contains("salary_company_only_B"))
        assertTrue(json.contains("Synthetic employer for account A"))
    }

    private fun seedLegacy(): Map<String, Any?> {
        val old = context.getSharedPreferences("salary_settings", 0)
        assertTrue(old.edit().putString("company_name", "Legacy declared employer")
            .putString("hourly_rate", "17.5").putString("contract_weekly_hours", "35")
            .putString("contract_type", "FULL_TIME").commit())
        return old.all.toMap()
    }

    @Test fun legacyAttributionRequiresDeclarationAndPreservesOriginalsOnceOnly() {
        val originals = seedLegacy()
        assertFalse(SalaryLegacyRecovery.ownsOriginalHistory(context))
        assertFalse(SalaryCompanyStore.readConfirmed(context).reliable)
        assertTrue(SalaryCompanyStore.readConfirmed(context).companies.isEmpty())
        val preview = requireNotNull(SalaryLegacyRecovery.preview(context))
        assertFalse(preview.alreadyClaimed)
        assertTrue(preview.entryCount > 0)
        assertFalse(SalaryLegacyRecovery.claim(context, preview.digest, false))
        assertTrue(SalaryLegacyRecovery.claim(context, preview.digest, true))
        assertTrue(SalaryLegacyRecovery.claim(context, preview.digest, true))
        assertEquals(originals, context.getSharedPreferences("salary_settings", 0).all)
        assertTrue(SalaryLegacyRecovery.ownsOriginalHistory(context))
        assertEquals("Legacy declared employer", SalaryCompanyStore.readConfirmed(context).companies.single().name)
        account(accountB)
        assertTrue(SalaryCompanyStore.readConfirmed(context).reliable)
        assertTrue(SalaryCompanyStore.readConfirmed(context).companies.isEmpty())
        assertNull(SalaryLegacyRecovery.preview(context))
        assertFalse(SalaryLegacyRecovery.ownsOriginalHistory(context))
        assertFalse(SalaryLegacyRecovery.claim(context, preview.digest, true))
    }

    @Test fun staleLegacyPreviewAndExistingDestinationRefuseCopy() {
        seedLegacy()
        val first = requireNotNull(SalaryLegacyRecovery.preview(context))
        assertTrue(context.getSharedPreferences("salary_settings", 0).edit().putString("hourly_rate", "18.5").commit())
        assertFalse(SalaryLegacyRecovery.claim(context, first.digest, true))
        val current = requireNotNull(SalaryLegacyRecovery.preview(context))
        assertTrue(SalaryLegacyRecovery.decline(context, current.digest, true))
        assertFalse(SalaryLegacyRecovery.ownsOriginalHistory(context))
        assertTrue(SalaryCompanyStore.readConfirmed(context).reliable)
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company))
        assertFalse(SalaryLegacyRecovery.claim(context, current.digest, true))
        assertEquals(company, SalaryCompanyStore.readConfirmed(context).companies.single())
        assertEquals("18.5", context.getSharedPreferences("salary_settings", 0).getString("hourly_rate", null))
    }

    @Test fun alreadyOwnedDatedLegacyOnlyMigratesMatchingOwnerAndRetainsSource() {
        seedSalaryForA()
        val source = seedDetailedSourcesForA()
        val logical = "dated_work_applicability_v2"
        val scoped = context.getSharedPreferences(requireNotNull(SalaryPreferenceRegistry.fileName(logical, "uid:$accountA")), 0)
        val values = scoped.all.toMap()
        val legacy = context.getSharedPreferences(logical, 0)
        values.forEach { (key, value) -> assertTrue(legacy.edit().putString(key, value as String).commit()) }
        assertTrue(scoped.edit().clear().commit())
        account(accountB)
        assertFalse(DatedWorkRuleStoreV2.read(context, source.rule.owner).reliable)
        assertTrue(context.getSharedPreferences(requireNotNull(SalaryPreferenceRegistry.fileName(logical, "uid:$accountB")), 0).all.isEmpty())
        account(accountA)
        assertEquals(source.rule, DatedWorkRuleStoreV2.read(context, source.rule.owner).records.single())
        assertEquals(values, legacy.all)
    }
}
