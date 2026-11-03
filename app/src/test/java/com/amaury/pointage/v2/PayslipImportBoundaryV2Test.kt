package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.net.Uri
import com.amaury.pointage.BackupPreferenceKeyPolicy
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
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

/** Real import and backup stores; only Firebase identities are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [RecoveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class PayslipImportBoundaryV2Test {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private val accountA = "ht016-owner-a"
    private val accountB = "ht016-owner-b"
    private val companyA = "ht016-company-a"
    private val companyB = "ht016-company-b"
    private val sourceUri = "content://ht016.invalid/private/payslip.pdf"
    private val rawFields = mapOf(
        PayslipDocumentParserV2.KEY_GROSS to "2000,00",
        PayslipDocumentParserV2.KEY_NET_BEFORE_TAX to "1550,00",
        PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to "25,50"
    )

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        RecoveryAuthShadow.auth = auth
        account(accountA)
        val logicalNames = SalaryPreferenceRegistry.personalFiles +
            setOf("salary_company_$companyA", "salary_company_$companyB")
        val names = logicalNames.toMutableSet()
        names += setOf("salary_legacy_recovery_owner", "pointage", "horatrack_v2_legacy_recovery_claim")
        for (scope in listOf("uid:$accountA", "uid:$accountB", "guest")) {
            logicalNames.forEach { names += requireNotNull(SalaryPreferenceRegistry.fileName(it, scope)) }
            listOf("horatrack_v2_test_runtime", "horatrack_v2_migration").forEach {
                names += requireNotNull(V2RuntimeAccountScope.fileName(it, scope))
            }
        }
        names.forEach { assertTrue(context.getSharedPreferences(it, 0).edit().clear().commit()) }
        seedCompanies()
    }

    private fun account(uid: String?) {
        val user = uid?.let { id ->
            Mockito.mock(FirebaseUser::class.java).also { Mockito.`when`(it.uid).thenReturn(id) }
        }
        Mockito.`when`(auth.currentUser).thenReturn(user)
        SalaryAccountScope.capture()
    }

    private fun seedCompanies() {
        for (id in listOf(companyA, companyB)) {
            assertTrue(SalaryCompanyStore.createOrUpdate(context,
                SalaryCompanyStore.Company(id, "Synthetic employer $id", "")))
        }
    }

    private fun confirmedDraft(existingRecordId: String? = null): PayslipImportStoreV2.Draft {
        val draft = requireNotNull(PayslipImportStoreV2.create(
            context, companyA, sourceUri, "application/pdf", existingRecordId
        ))
        return requireNotNull(PayslipImportStoreV2.save(context, draft.copy(
            year = 2026, month = 10, periodConfirmed = true, rawFields = rawFields,
            proposedValues = mapOf(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX to 1550.0),
            proposedEvidence = mapOf(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX to "Synthetic net before tax 1550.00")
        )))
    }

    private fun savedDraftBytes(): String? = SalaryAccountScope.preferences(
        context, PayslipImportStoreV2.PREFS
    ).getString(PayslipImportStoreV2.DRAFTS_KEY, null)

    private fun backup(payload: JSONObject): String = JSONObject()
        .put("formatVersion", 6)
        .put("accountScope", "uid:$accountA")
        .put("salaryOwner", "uid:$accountA")
        .put("salaryOwnership", "account-scoped-v1")
        .put("preferences", JSONObject().put(PayslipImportStoreV2.PREFS, payload))
        .toString()

    private fun typed(value: String): JSONObject = JSONObject().put("t", "s").put("v", value)

    @Test fun oldDraftAndContextStayInvalidAfterAccountRoundTripButFreshOwnerCanResume() {
        val draft = confirmedDraft()
        val oldContext = SalaryAccountScope.bindContext(context)
        val ownerBytes = savedDraftBytes()
        account(accountB)
        seedCompanies() // Identical company IDs must not make a foreign draft accessible.
        val otherBefore = SalaryAccountScope.preferences(context, PayslipImportStoreV2.PREFS).all.toMap()
        assertTrue(PayslipImportStoreV2.read(context, companyA).drafts.isEmpty())
        assertNull(PayslipImportStoreV2.load(context, companyA, draft.id))
        assertNull(PayslipImportStoreV2.save(context, draft))
        assertEquals(PayslipImportStoreV2.Status.UNAVAILABLE, PayslipImportStoreV2.commit(context, draft).status)
        assertFalse(PayslipImportStoreV2.discard(oldContext, draft))
        assertEquals(otherBefore, SalaryAccountScope.preferences(context, PayslipImportStoreV2.PREFS).all)

        account(accountA)
        assertNull(PayslipImportStoreV2.save(oldContext, draft))
        assertEquals(PayslipImportStoreV2.Status.UNAVAILABLE, PayslipImportStoreV2.commit(context, draft).status)
        assertEquals(ownerBytes, savedDraftBytes())
        val resumed = requireNotNull(PayslipImportStoreV2.load(context, companyA, draft.id))
        assertEquals(rawFields, resumed.rawFields)
        assertFalse("A fresh session must not restore the old approval", resumed.periodConfirmed)
        assertEquals(PayslipImportStoreV2.Status.INVALID_INPUT, PayslipImportStoreV2.commit(context, resumed).status)
        val reconfirmed = requireNotNull(PayslipImportStoreV2.save(context, resumed.copy(periodConfirmed = true)))
        val result = PayslipImportStoreV2.commit(context, reconfirmed)
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, result.status)
        assertEquals(1, V2PayslipStore.forCompany(context, companyA).size)
        assertEquals(1550.0, PayslipObservedValuesStoreV2.getAll(context, requireNotNull(result.record).id)
            [PayslipDocumentParserV2.KEY_NET_BEFORE_TAX]!!, 0.0)
    }

    @Test fun guestCannotReadOrPublishAnAuthenticatedOwnersPendingImport() {
        val draft = confirmedDraft()
        val before = savedDraftBytes()
        account(null)
        seedCompanies()
        assertTrue(PayslipImportStoreV2.read(context, companyA).drafts.isEmpty())
        assertNull(PayslipImportStoreV2.load(context, companyA, draft.id))
        assertEquals(PayslipImportStoreV2.Status.UNAVAILABLE, PayslipImportStoreV2.commit(context, draft).status)
        assertTrue(V2PayslipStore.forCompany(context, companyA).isEmpty())
        account(accountA)
        assertEquals(before, savedDraftBytes())
    }

    @Test fun companyBoundaryCannotBeChangedByCopyingAnExistingDraft() {
        val draft = confirmedDraft()
        val before = savedDraftBytes()
        assertTrue(PayslipImportStoreV2.read(context, companyB).drafts.isEmpty())
        assertNull(PayslipImportStoreV2.load(context, companyB, draft.id))
        val forged = draft.copy(companyId = companyB)
        assertNull(PayslipImportStoreV2.save(context, forged))
        assertNotEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(context, forged).status)
        assertFalse(PayslipImportStoreV2.discard(context, forged))
        assertEquals(before, savedDraftBytes())
        assertTrue(V2PayslipStore.all(context).isEmpty())
    }

    @Test fun snapshotExcludesPrivateDraftButRetainsConfirmedPayslipAndBoundedEvidence() {
        val complete = PayslipImportStoreV2.commit(context, confirmedDraft())
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, complete.status)
        val pending = confirmedDraft()
        val before = savedDraftBytes()
        assertFalse(BackupPreferenceKeyPolicy.canTransfer(PayslipImportStoreV2.PREFS, PayslipImportStoreV2.DRAFTS_KEY))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer(PayslipImportStoreV2.PREFS, "items"))
        val snapshot = V2BackupManager.snapshot(context)
        val payload = snapshot.getJSONObject("preferences").getJSONObject(PayslipImportStoreV2.PREFS)
        assertFalse(payload.has(PayslipImportStoreV2.DRAFTS_KEY))
        val records = V2PayslipStore.decodeRecords(payload.getJSONObject("items").getString("v"))
        assertTrue(records.reliable)
        assertEquals(listOf(requireNotNull(complete.record).id), records.records.map { it.id })
        assertTrue(payload.has("observed_evidence_v2"))
        assertEquals(before, savedDraftBytes())
        assertNotNull(PayslipImportStoreV2.load(context, companyA, pending.id))
    }

    @Test fun restoreIgnoresIncomingPrivateDraftAndPreservesLocalDraftWithoutRevivingItsLease() {
        val local = confirmedDraft()
        val before = savedDraftBytes()
        val hostilePayload = JSONObject().put(PayslipImportStoreV2.DRAFTS_KEY, typed("{\"foreign\":\"untrusted draft\"}"))
        val restored = V2BackupManager.restoreFromJson(context, backup(hostilePayload))
        assertTrue(restored.exceptionOrNull()?.stackTraceToString(), restored.isSuccess)
        assertEquals(before, savedDraftBytes())
        assertEquals(PayslipImportStoreV2.Status.UNAVAILABLE, PayslipImportStoreV2.commit(context, local).status)
        val fresh = requireNotNull(PayslipImportStoreV2.load(context, companyA, local.id))
        assertEquals(rawFields, fresh.rawFields)
        assertFalse(fresh.periodConfirmed)
    }

    @Test fun restoreCannotCreateAnImportDraftOnADeviceWithoutOne() {
        val payload = JSONObject().put(PayslipImportStoreV2.DRAFTS_KEY, typed("{\"foreign\":\"untrusted draft\"}"))
        val restored = V2BackupManager.restoreFromJson(context, backup(payload))
        assertTrue(restored.exceptionOrNull()?.stackTraceToString(), restored.isSuccess)
        assertNull(savedDraftBytes())
        val read = PayslipImportStoreV2.read(context, companyA)
        assertTrue(read.reliable)
        assertTrue(read.drafts.isEmpty())
    }

    @Test fun restoredChangeToOriginalRecordBlocksPartialImportResumeWithoutOverwritingEitherSide() {
        val original = requireNotNull(V2PayslipStore.add(context, 2026, 10, Uri.parse(sourceUri),
            "application/pdf", 2000.0, 1550.0, true, companyA))
        val pending = confirmedDraft(original.id)
        val before = savedDraftBytes()
        val rawItems = SalaryAccountScope.preferences(context, PayslipImportStoreV2.PREFS).getString("items", null)
        val restoredItems = JSONArray(requireNotNull(rawItems))
        restoredItems.getJSONObject(0).put("gross", 9999.0)
        val restored = V2BackupManager.restoreFromJson(context, backup(JSONObject().put("items", typed(restoredItems.toString()))))
        assertTrue(restored.exceptionOrNull()?.stackTraceToString(), restored.isSuccess)
        assertEquals(before, savedDraftBytes())
        val fresh = requireNotNull(PayslipImportStoreV2.load(context, companyA, pending.id))
        val reconfirmed = requireNotNull(PayslipImportStoreV2.save(context, fresh.copy(periodConfirmed = true)))
        val result = PayslipImportStoreV2.commit(context, reconfirmed)
        assertEquals(PayslipImportStoreV2.Status.CONFLICT, result.status)
        assertEquals(1, V2PayslipStore.all(context).size)
        assertEquals(9999.0, V2PayslipStore.all(context).single().gross!!, 0.0)
        assertTrue(PayslipObservedValuesStoreV2.getAll(context, original.id).isEmpty())
        assertNotNull(PayslipImportStoreV2.load(context, companyA, pending.id))
    }
}
