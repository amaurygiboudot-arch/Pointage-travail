package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import com.amaury.pointage.GpsDeliveryAuthShadow
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real store APIs; synthetic identities, drafts and injected preference failures only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [GpsDeliveryAuthShadow::class],
    instrumentedPackages = ["com.google.firebase.auth"])
class PayslipImportStoreV2Test {
    private lateinit var context: Context
    private val company = "ht016-synthetic-company"
    private val gross = PayslipDocumentParserV2.KEY_GROSS
    private val net = PayslipDocumentParserV2.KEY_NET_BEFORE_TAX
    private val mutual = PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE
    private val scope = "uid:ht016-storage-owner"
    private val physical get() = requireNotNull(SalaryPreferenceRegistry.fileName(PayslipImportStoreV2.PREFS, scope))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        val auth = Mockito.mock(FirebaseAuth::class.java)
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("ht016-storage-owner")
        Mockito.`when`(auth.currentUser).thenReturn(user)
        GpsDeliveryAuthShadow.auth = auth
        SalaryAccountScope.capture()
        assertTrue(context.getSharedPreferences(physical, 0).edit().clear().commit())
    }

    private fun draft(ctx: Context = context) = requireNotNull(PayslipImportStoreV2.create(
        ctx, company, "content://ht016.invalid/synthetic.pdf", "application/pdf"
    )).copy(year = 2026, month = 10, periodConfirmed = true,
        rawFields = mapOf(gross to "2 000,00", net to "1550,00", mutual to "25,50"),
        proposedValues = mapOf(gross to 2000.0, net to 1550.0, mutual to 20.0),
        proposedEvidence = mapOf(gross to "Synthetic gross 2000", net to "Synthetic net 1550", mutual to "Synthetic old mutual 20"))

    private fun save(d: PayslipImportStoreV2.Draft, ctx: Context = context) =
        requireNotNull(PayslipImportStoreV2.save(ctx, d))

    private fun prefs() = SalaryAccountScope.preferences(context, PayslipImportStoreV2.PREFS)

    @Test fun completePublicationKeepsOneIdentityAndOnlyEvidenceMatchingManualAmounts() {
        val saved = save(draft())
        val result = PayslipImportStoreV2.commit(context, saved)
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, result.status)
        val record = requireNotNull(result.record)
        assertEquals(saved.recordId, record.id)
        assertEquals(1, V2PayslipStore.all(context).size)
        assertEquals(mapOf(gross to 2000.0, net to 1550.0, mutual to 25.5), PayslipObservedValuesStoreV2.getAll(context, record.id))
        assertEquals(mapOf(gross to "Synthetic gross 2000", net to "Synthetic net 1550"), PayslipObservedValuesStoreV2.evidence(context, record.id))
        assertTrue(PayslipImportStoreV2.read(context, company).drafts.isEmpty())
        // A duplicate callback is idempotent and still performs a durable commit.
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(context, saved).status)
        assertEquals(listOf(record), V2PayslipStore.all(context))
    }

    @Test fun damagedDetailsBlockAllNewPublicationAndRetainExactDraftForRepair() {
        val saved = save(draft())
        assertTrue(prefs().edit().putString(PayslipObservedValuesStoreV2.KEY, "{damaged").commit())
        val before = prefs().all.toMap()
        assertEquals(PayslipImportStoreV2.Status.STORAGE_BLOCKED, PayslipImportStoreV2.commit(context, saved).status)
        assertEquals(before, prefs().all)
        assertTrue(V2PayslipStore.all(context).isEmpty())
        val resumed = requireNotNull(PayslipImportStoreV2.load(context, company, saved.id))
        assertEquals(saved.rawFields, resumed.rawFields)
        assertFalse(resumed.periodConfirmed)
        assertTrue(prefs().edit().putString(PayslipObservedValuesStoreV2.KEY, "{}").commit())
        val confirmed = save(resumed.copy(periodConfirmed = true))
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(context, confirmed).status)
        assertEquals(listOf(saved.recordId), V2PayslipStore.all(context).map { it.id })
    }

    @Test fun invalidAndBlankTextSurvivesReloadButCannotBePublished() {
        val entered = draft().copy(rawFields = mapOf(gross to "2000,", net to "wrong amount", mutual to ""))
        val saved = save(entered)
        val loaded = requireNotNull(PayslipImportStoreV2.load(context, company, saved.id))
        assertEquals(entered.rawFields, loaded.rawFields)
        assertEquals(PayslipImportStoreV2.Status.INVALID_INPUT, PayslipImportStoreV2.commit(context, saved).status)
        assertTrue(V2PayslipStore.all(context).isEmpty())
        val emptyGross = save(saved.copy(rawFields = mapOf(gross to "", net to "1550")))
        assertEquals(PayslipImportStoreV2.Status.INVALID_INPUT, PayslipImportStoreV2.commit(context, emptyGross).status)
    }

    @Test fun failedDraftWriteDoesNotCreateRecordOrCertifyDurability() {
        val faulty = FaultContext(context).apply { onCommit = { false } }
        val entered = draft(faulty)
        assertNull(PayslipImportStoreV2.save(faulty, entered))
        assertEquals(PayslipImportStoreV2.Status.CONFLICT, PayslipImportStoreV2.commit(faulty, entered).status)
        assertTrue(V2PayslipStore.all(context).isEmpty())
        faulty.onCommit = { it() }
        val saved = save(entered, faulty)
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(faulty, saved).status)
        assertEquals(entered.recordId, V2PayslipStore.all(context).single().id)
    }

    @Test fun failedFinalWriteBeforePublicationLeavesDraftAndRetryUsesSameId() {
        val saved = save(draft())
        val faulty = FaultContext(context).apply { onCommit = { false } }
        val before = prefs().all.toMap()
        assertEquals(PayslipImportStoreV2.Status.STORAGE_BLOCKED, PayslipImportStoreV2.commit(faulty, saved).status)
        assertEquals(before, prefs().all)
        faulty.onCommit = { it() }
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(faulty, saved).status)
        assertEquals(saved.recordId, V2PayslipStore.all(context).single().id)
    }

    @Test fun falseAfterPublicationKeepsDiscoverableDraftAndRetryWritesSameIdentityAgain() {
        val saved = save(draft())
        val faulty = FaultContext(context)
        var attempts = 0
        faulty.onCommit = { commit -> attempts++; commit(); false }
        assertEquals(PayslipImportStoreV2.Status.STORAGE_BLOCKED, PayslipImportStoreV2.commit(faulty, saved).status)
        assertEquals(saved.id, PayslipImportStoreV2.read(context, company).drafts.single().id)
        assertEquals(saved.recordId, V2PayslipStore.all(context).single().id)
        assertEquals(2, attempts) // failed publication plus retained retry intent, neither certified
        val resumed = requireNotNull(PayslipImportStoreV2.load(context, company, saved.id))
        assertEquals(saved.rawFields, resumed.rawFields)
        assertFalse(resumed.periodConfirmed)
        faulty.onCommit = { commit -> attempts++; commit() }
        val retry = save(resumed.copy(periodConfirmed = true), faulty)
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(faulty, retry).status)
        assertEquals(4, attempts) // retry persists the draft revision and final coherent publication
        assertEquals(1, V2PayslipStore.all(context).size)
        assertTrue(PayslipImportStoreV2.read(context, company).drafts.isEmpty())
    }

    @Test fun failedIdempotentReplayKeepsItsDraftAlongsideAnotherPendingImport() {
        val first = save(draft())
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(context, first).status)
        val other = save(draft())
        val faulty = FaultContext(context).apply { onCommit = { commit -> commit(); false } }
        assertEquals(PayslipImportStoreV2.Status.STORAGE_BLOCKED, PayslipImportStoreV2.commit(faulty, first).status)
        assertEquals(setOf(first.id, other.id), PayslipImportStoreV2.read(context, company).drafts.map { it.id }.toSet())
        assertEquals(first.recordId, V2PayslipStore.all(context).single().id)
    }

    @Test fun editingAfterAmbiguousPublicationRebasesOnlyTheExactPriorTarget() {
        val saved = save(draft())
        val faulty = FaultContext(context).apply { onCommit = { commit -> commit(); false } }
        assertEquals(PayslipImportStoreV2.Status.STORAGE_BLOCKED, PayslipImportStoreV2.commit(faulty, saved).status)
        val resumed = requireNotNull(PayslipImportStoreV2.load(context, company, saved.id))
        val amended = save(resumed.copy(periodConfirmed = true, rawFields = resumed.rawFields + (mutual to "45,50")))
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(context, amended).status)
        assertEquals(saved.recordId, V2PayslipStore.all(context).single().id)
        assertEquals(45.5, requireNotNull(PayslipObservedValuesStoreV2.getAll(context, saved.recordId)[mutual]), 0.0)
    }

    @Test fun priorPartialUsesExplicitRecordAndNeverCreatesAnotherBulletin() {
        val original = requireNotNull(V2PayslipStore.add(context, 2026, 10,
            Uri.parse("content://ht016.invalid/prior.pdf"), "application/pdf", 2000.0, 1550.0, true, company))
        val other = requireNotNull(V2PayslipStore.add(context, 2026, 9,
            Uri.parse("content://ht016.invalid/other.pdf"), "application/pdf", 1800.0, null, true, company))
        val pending = requireNotNull(PayslipImportStoreV2.create(context, company, original.sourceUri, original.sourceMime, original.id))
        val saved = save(pending.copy(periodConfirmed = true, rawFields = draft().rawFields))
        assertEquals(saved.id, requireNotNull(PayslipImportStoreV2.create(context, company, original.sourceUri, original.sourceMime, original.id)).id)
        assertEquals(PayslipImportStoreV2.Status.SUCCESS, PayslipImportStoreV2.commit(context, saved).status)
        assertEquals(setOf(original.id, other.id), V2PayslipStore.all(context).map { it.id }.toSet())
        assertEquals(original.importedAtMs, V2PayslipStore.all(context).single { it.id == original.id }.importedAtMs)
        assertEquals(other, V2PayslipStore.all(context).single { it.id == other.id })
    }

    @Test fun concurrentChangesToPriorDetailsOrEvidenceAreNotOverwritten() {
        val original = requireNotNull(V2PayslipStore.add(context, 2026, 10,
            Uri.parse("content://ht016.invalid/prior.pdf"), "application/pdf", 2000.0, null, true, company))
        val saved = save(requireNotNull(PayslipImportStoreV2.create(context, company, original.sourceUri, original.sourceMime, original.id))
            .copy(periodConfirmed = true, rawFields = draft().rawFields))
        assertTrue(PayslipObservedValuesStoreV2.put(context, original.id, mapOf(gross to 2000.0, mutual to 45.0), mapOf(mutual to "Other explicit confirmation")))
        val before = prefs().all.toMap()
        assertEquals(PayslipImportStoreV2.Status.CONFLICT, PayslipImportStoreV2.commit(context, saved).status)
        assertEquals(before, prefs().all)
    }

    @Test fun deletedPriorRecordCannotBeResurrectedByPendingImport() {
        val original = requireNotNull(V2PayslipStore.add(context, 2026, 10,
            Uri.parse("content://ht016.invalid/prior.pdf"), "application/pdf", 2000.0, null, true, company))
        val saved = save(requireNotNull(PayslipImportStoreV2.create(context, company, original.sourceUri, original.sourceMime, original.id))
            .copy(periodConfirmed = true, rawFields = draft().rawFields))
        assertTrue(V2PayslipStore.remove(context, original.id))
        assertEquals(PayslipImportStoreV2.Status.CONFLICT, PayslipImportStoreV2.commit(context, saved).status)
        assertTrue(V2PayslipStore.all(context).isEmpty())
        assertNotNull(PayslipImportStoreV2.load(context, company, saved.id))
    }

    @Test fun secondEditorCannotReplaceOrDiscardANewerDraftRevision() {
        val saved = save(draft())
        val stale = requireNotNull(PayslipImportStoreV2.load(context, company, saved.id))
        val newer = save(saved.copy(rawFields = saved.rawFields + (mutual to "35,50")))
        assertNull(PayslipImportStoreV2.save(context, stale.copy(rawFields = stale.rawFields + (mutual to "45,50"))))
        assertFalse(PayslipImportStoreV2.discard(context, stale))
        assertEquals("35,50", requireNotNull(PayslipImportStoreV2.load(context, company, newer.id)).rawFields[mutual])
    }

    @Test fun malformedDraftOrEvidenceIsPreservedWithoutPublishingPartialValues() {
        val saved = save(draft())
        assertTrue(prefs().edit().putString("observed_evidence_v2", "{bad}").commit())
        assertEquals(PayslipImportStoreV2.Status.STORAGE_BLOCKED, PayslipImportStoreV2.commit(context, saved).status)
        assertTrue(V2PayslipStore.all(context).isEmpty())
        assertTrue(prefs().edit().putString(PayslipImportStoreV2.DRAFTS_KEY, "{bad drafts}").commit())
        val before = prefs().all.toMap()
        assertFalse(PayslipImportStoreV2.read(context, company).reliable)
        assertNull(PayslipImportStoreV2.save(context, saved))
        assertFalse(PayslipImportStoreV2.discard(context, saved))
        assertEquals(before, prefs().all)
    }

    @Test fun anotherExistingWriterWaitsForPublicationAndItsRecordIsNotLost() {
        val saved = save(draft())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val concurrentStarted = CountDownLatch(1)
        val faulty = FaultContext(context).apply {
            onCommit = { commit -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); commit() }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val importing = pool.submit<PayslipImportStoreV2.CommitResult> { PayslipImportStoreV2.commit(faulty, saved) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = pool.submit<V2PayslipStore.Record?> {
                concurrentStarted.countDown()
                V2PayslipStore.add(context, 2026, 9, Uri.parse("content://ht016.invalid/parallel.pdf"), "application/pdf", 1900.0, null, true, company)
            }
            assertTrue(concurrentStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(PayslipImportStoreV2.Status.SUCCESS, importing.get(5, TimeUnit.SECONDS).status)
            val otherRecord = requireNotNull(other.get(5, TimeUnit.SECONDS))
            assertEquals(setOf(saved.recordId, otherRecord.id), V2PayslipStore.all(context).map { it.id }.toSet())
            assertEquals(25.5, requireNotNull(PayslipObservedValuesStoreV2.getAll(context, saved.recordId)[mutual]), 0.0)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    private inner class FaultContext(base: Context) : ContextWrapper(base) {
        var onCommit: (() -> Boolean) -> Boolean = { it() }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences(name, mode)
            if (name != physical) return prefs
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
                        override fun remove(key: String?) = apply { editor.remove(key) }
                        override fun commit() = onCommit { editor.commit() }
                        override fun apply() { commit() }
                    }
                }
            }
        }
    }
}
