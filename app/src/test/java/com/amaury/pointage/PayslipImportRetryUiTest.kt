package com.amaury.pointage

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import com.amaury.pointage.v2.PayslipImportStoreV2
import com.amaury.pointage.v2.PayslipObservedValuesStoreV2
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.V2PayslipStore
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** Actual confirmation screens with synthetic documents; no OCR or real account is contacted. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class PayslipImportRetryUiTest {
    private lateinit var app: Context
    private lateinit var auth: FirebaseAuth
    private val company = SalaryCompanyStore.Company("ht016-company", "Entreprise fictive HT016", "")
    private val sourceUri = Uri.parse("content://ht016.invalid/fictional-payslip")
    private val controllers = mutableListOf<ActivityController<V2PayslipImportActivity>>()

    private data class Screen(
        val controller: ActivityController<V2PayslipImportActivity>,
        val dialog: AlertDialog
    ) {
        val activity: V2PayslipImportActivity get() = controller.get()
    }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account("ht016-A")
        assertTrue(SalaryCompanyStore.createOrUpdate(app, company))
        assertTrue(prefs().edit().clear().commit())
    }

    @After fun cleanup() {
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        controllers.toList().forEach { controller ->
            controller.pause().stop().destroy()
        }
        controllers.clear()
        idle()
    }

    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        SalaryAccountScope.capture()
    }

    private fun prefs(): SharedPreferences = SalaryAccountScope.preferences(app, "horatrack_v2_payslips")
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun field(screen: Screen, hintPrefix: String): EditText =
        descendants(screen.dialog.window!!.decorView).filterIsInstance<EditText>()
            .single { it.hint.toString().startsWith(hintPrefix) }

    private fun intent(companyId: String = company.id): Intent = SalaryAccountUi.attach(
        Intent(app, V2PayslipImportActivity::class.java)
            .putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID, companyId)
            .putExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME, company.name),
        SalaryAccountScope.capture()
    )

    private fun launch(intent: Intent = intent(), state: Bundle? = null): ActivityController<V2PayslipImportActivity> {
        val controller = Robolectric.buildActivity(V2PayslipImportActivity::class.java, intent)
        controller.get().setTheme(R.style.Theme_PointageTravail)
        controllers += controller
        controller.setup(state)
        idle()
        return controller
    }

    private fun latestScreen(controller: ActivityController<V2PayslipImportActivity>): Screen {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("The actual confirmation dialog must be visible", dialog.isShowing)
        assertTrue("The privacy assertions must exercise an attached window", dialog.window!!.decorView.isAttachedToWindow)
        return Screen(controller, dialog)
    }

    private fun newScreen(parsed: PayslipDocumentParserV2.Result? = null): Screen {
        val controller = launch()
        // Enter the real manual confirmation path without invoking an OCR service or a content provider.
        V2PayslipImportActivity::class.java.getDeclaredMethod(
            "askConfirmedValues", Uri::class.java, String::class.java,
            PayslipDocumentParserV2.Result::class.java
        ).apply { isAccessible = true }.invoke(controller.get(), sourceUri, "application/pdf", parsed)
        idle()
        return latestScreen(controller)
    }

    private fun fill(screen: Screen) {
        field(screen, "Brut du bulletin").setText("2000,00")
        field(screen, "Net à payer avant PAS").setText("1550,00")
        field(screen, "Mutuelle").setText("25,50")
    }

    private fun confirmPeriod(screen: Screen) {
        descendants(screen.dialog.window!!.decorView).filterIsInstance<Button>()
            .single { it.text.toString().startsWith("Choisir la période du bulletin") }.performClick()
        idle()
        val picker = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotSame(screen.dialog, picker)
        assertTrue(picker.listView.performItemClick(null, 0, 0L))
        idle()
    }

    private fun save(screen: Screen) {
        screen.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun pending(): PayslipImportStoreV2.Draft {
        val result = PayslipImportStoreV2.read(app, company.id)
        assertTrue(result.reliable)
        return result.drafts.single()
    }

    private fun destroy(screen: Screen, state: Bundle? = null) {
        screen.controller.pause()
        if (state != null) screen.controller.saveInstanceState(state)
        screen.dialog.dismiss()
        screen.controller.stop().destroy()
        controllers.remove(screen.controller)
        idle()
    }

    private fun assertConfirmedRecord(id: String? = null) {
        val record = V2PayslipStore.forCompany(app, company.id).single()
        id?.let { assertEquals(it, record.id) }
        assertEquals(2000.0, record.gross!!, 0.0)
        assertEquals(1550.0, record.net!!, 0.0)
        assertEquals(25.5, PayslipObservedValuesStoreV2.getAll(app, record.id)[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE]!!, 0.0)
        assertTrue(PayslipImportStoreV2.read(app, company.id).drafts.isEmpty())
    }

    @Test fun explicitPeriodRemainsRequiredBeforePublishingConfirmedAmounts() {
        val screen = newScreen()
        fill(screen)
        save(screen)

        assertTrue(screen.dialog.isShowing)
        assertFalse(screen.activity.isFinishing)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        assertTrue(PayslipObservedValuesStoreV2.readResult(app).valuesByRecord.isEmpty())
        assertEquals("Choisis explicitement la période inscrite sur le bulletin", ShadowToast.getTextOfLatestToast())
        assertEquals("25,50", field(screen, "Mutuelle").text.toString())
    }

    @Test fun invalidOptionalAmountDoesNotPublishOrCloseTheForm() {
        val screen = newScreen()
        fill(screen)
        confirmPeriod(screen)
        field(screen, "Mutuelle").setText("1,2,3")
        save(screen)

        assertTrue(screen.dialog.isShowing)
        assertFalse(screen.activity.isFinishing)
        assertNotNull(field(screen, "Mutuelle").error)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        assertTrue(PayslipObservedValuesStoreV2.readResult(app).valuesByRecord.isEmpty())
        assertEquals("2000,00", field(screen, "Brut du bulletin").text.toString())
    }

    @Test fun successfulConfirmationPublishesOneRecordAndAllDetailsThenFinishes() {
        val screen = newScreen()
        fill(screen)
        confirmPeriod(screen)
        save(screen)

        assertTrue(screen.activity.isFinishing)
        assertFalse(screen.dialog.isShowing)
        assertConfirmedRecord()
        val record = V2PayslipStore.all(app).single()
        // A queued second click on the completed dialog must never create a new UUID.
        save(screen)
        assertConfirmedRecord(record.id)
    }

    @Test fun unreadableDetailsKeepManualInputDurableAndRetryPublishesOnlyOneRecord() {
        val screen = newScreen()
        fill(screen)
        confirmPeriod(screen)
        assertTrue(prefs().edit().putString("observed_values_v2", "{unreadable-details").commit())
        save(screen)

        assertTrue(screen.dialog.isShowing)
        assertFalse(screen.activity.isFinishing)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        assertEquals("{unreadable-details", prefs().getString("observed_values_v2", null))
        assertEquals("25,50", field(screen, "Mutuelle").text.toString())
        assertEquals("25,50", pending().rawFields[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE])
        val stableId = pending().recordId
        assertFalse(ShadowToast.getTextOfLatestToast().contains("Bulletin importé"))
        assertEquals("RÉESSAYER", screen.dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())

        assertTrue(prefs().edit().putString("observed_values_v2", "{}").commit())
        save(screen)
        assertConfirmedRecord(stableId)
        assertTrue(screen.activity.isFinishing)
    }

    @Test fun unreadableRecordHistoryIsPreservedAndDoesNotConsumeTheForm() {
        val screen = newScreen()
        fill(screen)
        confirmPeriod(screen)
        assertTrue(prefs().edit().putString("items", "{unreadable-records").commit())
        save(screen)

        assertEquals("{unreadable-records", prefs().getString("items", null))
        assertTrue(screen.dialog.isShowing)
        assertFalse(screen.activity.isFinishing)
        assertEquals("25,50", pending().rawFields[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE])
        assertTrue(PayslipObservedValuesStoreV2.readResult(app).valuesByRecord.isEmpty())

        assertTrue(prefs().edit().putString("items", "[]").commit())
        save(screen)
        assertConfirmedRecord()
    }

    @Test fun unreadableProvenanceCannotPublishOnlyTheRecordAndDiscardInput() {
        val screen = newScreen()
        fill(screen)
        confirmPeriod(screen)
        assertTrue(prefs().edit().putString("observed_evidence_v2", "{unreadable-excerpts").commit())
        save(screen)

        assertTrue(screen.dialog.isShowing)
        assertFalse(screen.activity.isFinishing)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        assertTrue(PayslipObservedValuesStoreV2.readResult(app).valuesByRecord.isEmpty())
        assertEquals("{unreadable-excerpts", prefs().getString("observed_evidence_v2", null))
        assertEquals("1550,00", pending().rawFields[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX])

        assertTrue(prefs().edit().putString("observed_evidence_v2", "{}").commit())
        save(screen)
        assertConfirmedRecord()
    }

    @Test fun failedConfirmationSurvivesActivityRecreationAndRequiresPeriodAgain() {
        val first = newScreen()
        fill(first)
        confirmPeriod(first)
        assertTrue(prefs().edit().putString("observed_values_v2", "{unreadable-details").commit())
        save(first)
        val stableId = pending().recordId
        // Also retain a correction entered after the failed attempt, before Android saves state.
        field(first, "Mutuelle").setText("26,75")
        val state = Bundle()
        val launchIntent = first.activity.intent
        destroy(first, state)

        val second = latestScreen(launch(launchIntent, state))
        assertEquals("26,75", field(second, "Mutuelle").text.toString())
        assertEquals("1550,00", field(second, "Net à payer avant PAS").text.toString())
        assertTrue(prefs().edit().putString("observed_values_v2", "{}").commit())
        save(second)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        assertFalse(second.activity.isFinishing)
        confirmPeriod(second)
        save(second)

        val record = V2PayslipStore.all(app).single()
        assertEquals(stableId, record.id)
        assertEquals(26.75, PayslipObservedValuesStoreV2.getAll(app, record.id)[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE]!!, 0.0)
        assertTrue(PayslipImportStoreV2.read(app, company.id).drafts.isEmpty())
    }

    @Test fun aFreshLaunchRecoversSavedInputWithoutReadingTheDocumentAgain() {
        val first = newScreen()
        fill(first)
        confirmPeriod(first)
        assertTrue(prefs().edit().putString("observed_values_v2", "{unreadable-details").commit())
        save(first)
        val draft = pending()
        destroy(first)

        val second = latestScreen(launch(intent().putExtra(V2PayslipImportActivity.EXTRA_DRAFT_ID, draft.id)))
        assertEquals("2000,00", field(second, "Brut du bulletin").text.toString())
        assertEquals("25,50", field(second, "Mutuelle").text.toString())
        // No provider serves the synthetic content URI; successful resumption must not need OCR.
        assertTrue(prefs().edit().putString("observed_values_v2", "{}").commit())
        confirmPeriod(second)
        save(second)
        assertConfirmedRecord(draft.recordId)
    }

    @Test fun existingPartialBulletinIsCompletedByItsIdWithoutDuplicatingIt() {
        val existing = requireNotNull(V2PayslipStore.add(app, 2026, 0, sourceUri, "application/pdf", 2000.0, 1550.0, true, company.id))
        val screen = latestScreen(launch(intent().putExtra(V2PayslipImportActivity.EXTRA_RECORD_ID, existing.id)))
        fill(screen)
        confirmPeriod(screen)
        assertTrue(prefs().edit().putString("observed_values_v2", "{unreadable-details").commit())
        save(screen)

        assertTrue(screen.dialog.isShowing)
        assertFalse(screen.activity.isFinishing)
        assertEquals(existing, V2PayslipStore.all(app).single())
        assertEquals(existing.id, pending().recordId)
        assertEquals("25,50", pending().rawFields[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE])

        assertTrue(prefs().edit().putString("observed_values_v2", "{}").commit())
        save(screen)
        assertConfirmedRecord(existing.id)
        assertEquals(existing.importedAtMs, V2PayslipStore.all(app).single().importedAtMs)
    }

    @Test fun expiredConfirmationCannotPublishAfterAccountRoundTripButFreshResumeCan() {
        val first = newScreen()
        fill(first)
        confirmPeriod(first)
        assertTrue(prefs().edit().putString("observed_values_v2", "{unreadable-details").commit())
        save(first)
        val draft = pending()
        val oldButton = first.dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        account("ht016-B")
        assertTrue(SalaryCompanyStore.createOrUpdate(app, company))
        val accountBRecord = requireNotNull(V2PayslipStore.add(
            app, 2026, 0, Uri.parse("content://ht016.invalid/account-B"), "application/pdf", 9000.0, 7000.0, true, company.id
        ))
        val accountBBefore = prefs().all.toMap()
        oldButton.performClick()
        idle()
        assertEquals(accountBBefore, prefs().all)
        assertEquals(accountBRecord, V2PayslipStore.all(app).single())
        assertTrue(PayslipImportStoreV2.read(app, company.id).drafts.isEmpty())
        assertFalse(first.dialog.isShowing)
        assertTrue(first.activity.isFinishing)

        account("ht016-A")
        assertTrue(prefs().edit().putString("observed_values_v2", "{}").commit())
        oldButton.performClick()
        idle()
        assertTrue(V2PayslipStore.all(app).isEmpty())
        assertEquals(draft.id, pending().id)
        destroy(first)

        val second = latestScreen(launch(intent().putExtra(V2PayslipImportActivity.EXTRA_DRAFT_ID, draft.id)))
        save(second)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        confirmPeriod(second)
        save(second)
        assertConfirmedRecord(draft.recordId)
    }

    @Test fun anotherCompanyCannotOpenOrConsumeThePendingConfirmation() {
        val first = newScreen()
        fill(first)
        confirmPeriod(first)
        assertTrue(prefs().edit().putString("observed_values_v2", "{unreadable-details").commit())
        save(first)
        val draftId = pending().id
        destroy(first)
        val otherCompany = company.copy(id = "ht016-other-company", name = "Autre entreprise fictive")
        assertTrue(SalaryCompanyStore.createOrUpdate(app, otherCompany))

        val rejected = launch(intent(otherCompany.id).putExtra(V2PayslipImportActivity.EXTRA_DRAFT_ID, draftId))
        val unavailable = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(unavailable.isShowing)
        assertTrue(descendants(unavailable.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        unavailable.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(rejected.get().isFinishing)
        assertEquals(draftId, pending().id)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        assertTrue(PayslipImportStoreV2.read(app, otherCompany.id).drafts.isEmpty())
    }

    @Test fun correctingAnOcrProposalDoesNotRetainItsOriginalExcerptAsEvidence() {
        val parsed = PayslipDocumentParserV2.parse("Salaire brut 2000,00\nNet à payer avant impôt sur le revenu 1550,00")
        assertTrue(parsed.gross.highConfidence)
        assertEquals(2000.0, parsed.gross.amount!!, 0.0)
        assertNotNull(parsed.gross.sourceLabel)
        val screen = newScreen(parsed)
        fill(screen)
        field(screen, "Brut du bulletin").setText("2100,00")
        confirmPeriod(screen)
        save(screen)

        val record = V2PayslipStore.all(app).single()
        assertEquals(2100.0, record.gross!!, 0.0)
        assertFalse(PayslipObservedValuesStoreV2.evidence(app, record.id).containsKey(PayslipDocumentParserV2.KEY_GROSS))
        assertEquals(25.5, PayslipObservedValuesStoreV2.getAll(app, record.id)[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE]!!, 0.0)
    }

    @Test fun laterKeepsEvenUnconfirmedInputAndTheNormalEntryPointOffersItAgain() {
        val first = newScreen()
        fill(first)
        field(first, "Mutuelle").setText("1,2,3")
        first.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(first.activity.isFinishing)
        assertFalse(first.dialog.isShowing)
        assertTrue(V2PayslipStore.all(app).isEmpty())
        val saved = pending()
        assertEquals("1,2,3", saved.rawFields[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE])
        destroy(first)

        val controller = launch()
        val pendingList = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(pendingList.isShowing)
        assertEquals(1, pendingList.listView.count)
        assertTrue(pendingList.listView.performItemClick(null, 0, 0L))
        idle()
        val resumed = latestScreen(controller)
        assertEquals("1,2,3", field(resumed, "Mutuelle").text.toString())
        assertEquals("2000,00", field(resumed, "Brut du bulletin").text.toString())
        assertEquals(saved.id, pending().id)
        assertTrue(V2PayslipStore.all(app).isEmpty())
    }

    @Test fun abandoningPendingDetailsDoesNotDeleteAnAlreadyImportedBulletin() {
        val existing = requireNotNull(V2PayslipStore.add(app, 2026, 0, sourceUri, "application/pdf", 2000.0, 1550.0, true, company.id))
        val screen = latestScreen(launch(intent().putExtra(V2PayslipImportActivity.EXTRA_RECORD_ID, existing.id)))
        fill(screen)
        confirmPeriod(screen)
        assertTrue(prefs().edit().putString("observed_values_v2", "{unreadable-details").commit())
        save(screen)
        assertEquals(existing.id, pending().recordId)

        screen.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotSame(screen.dialog, confirmation)
        assertEquals(existing, V2PayslipStore.all(app).single())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        assertTrue(screen.activity.isFinishing)
        assertTrue(PayslipImportStoreV2.read(app, company.id).drafts.isEmpty())
        assertEquals(existing, V2PayslipStore.all(app).single())
        assertEquals("{unreadable-details", prefs().getString("observed_values_v2", null))
    }
}
