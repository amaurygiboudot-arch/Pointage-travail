package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import com.amaury.pointage.billing.BillingContract
import com.amaury.pointage.billing.BillingServiceFlow
import com.amaury.pointage.billing.PaidServiceReports
import com.amaury.pointage.v2.SalaryAccountScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.json.JSONObject
import java.io.File

/** Real form, activity and dialog boundaries; only Firebase identity is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class SalaryAccountUiIsolationTest {
    private lateinit var app: Context
    private lateinit var auth: FirebaseAuth
    private val company = SalaryCompanyStore.Company("same-employer", "Synthetic employer", "")

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account("ui-A")
        PendingSalaryCompanyAccess.clear()
    }

    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        SalaryAccountScope.capture()
    }

    private fun seed(rate: String) {
        assertTrue(SalaryCompanyStore.createOrUpdate(app, company))
        assertTrue(SalaryCompanyStore.prefs(app, company.id).edit().putString("hourly_rate", rate).commit())
    }

    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    private fun button(view: View, text: String): Button = children(view).filterIsInstance<Button>().single { it.text.toString() == text }

    @Test fun capturedContractFormCannotWriteTheNextAccountWithTheSameEmployerId() {
        seed("10")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val form = SalaryContractDetailsView(controller.get(), company)
            controller.get().setContentView(form)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue("The privacy assertion exercises an attached screen", form.isAttachedToWindow)
            button(form, "MODIFIER").performClick()
            val rate = children(form).filterIsInstance<EditText>().single { it.hint.toString() == "Taux horaire brut" }
            rate.setText("999")
            val save = button(form, "ENREGISTRER")
            account("ui-B")
            seed("20")
            save.performClick()
            assertEquals("20", SalaryCompanyStore.prefs(app, company.id).getString("hourly_rate", null))
            assertFalse(SalaryCompanyStore.prefs(form.context, company.id).edit().putString("hourly_rate", "888").commit())
            // Auth notifications are intentionally queued outside persistence locks. Even before
            // that callback runs, a frame traversal must hide A without blocking the whole window.
            val cancelDraw = form.viewTreeObserver.dispatchOnPreDraw()
            assertFalse("Redacting the salary screen must not cancel the rest of the window", cancelDraw)
            assertEquals(View.GONE, form.visibility)
            assertEquals(0, form.childCount)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            account("ui-A")
            save.performClick()
            assertEquals("10", SalaryCompanyStore.prefs(app, company.id).getString("hourly_rate", null))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun attachedSalaryScreenRedactsWhenQueuedAccountNotificationIsDelivered() {
        seed("10")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val form = SalaryContractDetailsView(controller.get(), company)
            controller.get().setContentView(form)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(form.isAttachedToWindow)
            assertEquals(View.VISIBLE, form.visibility)
            assertTrue(form.childCount > 0)
            account("ui-B")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, form.visibility)
            assertEquals(0, form.childCount)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun redactedSalaryScreenDoesNotKeepSchedulingWindowLayouts() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val screen = object : SalaryAccountLayout(controller.get()) {
                var layoutRequests = 0
                override fun requestLayout() {
                    layoutRequests++
                    super.requestLayout()
                }
            }
            screen.addView(EditText(screen.context).apply { setText("Synthetic private value") })
            controller.get().setContentView(screen)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(screen.isAttachedToWindow)
            account("ui-B")
            assertFalse(screen.viewTreeObserver.dispatchOnPreDraw())
            assertEquals(View.GONE, screen.visibility)
            assertEquals(0, screen.childCount)
            val requestsAfterRedaction = screen.layoutRequests
            repeat(3) { assertFalse(screen.viewTreeObserver.dispatchOnPreDraw()) }
            assertEquals("An expired screen must not keep the whole window drawing", requestsAfterRedaction, screen.layoutRequests)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun currentContractFormStillSaves() {
        seed("10")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val form = SalaryContractDetailsView(controller.get(), company)
            controller.get().setContentView(form)
            button(form, "MODIFIER").performClick()
            children(form).filterIsInstance<EditText>().single { it.hint.toString() == "Taux horaire brut" }.setText("24")
            button(form, "ENREGISTRER").performClick()
            assertEquals("24", SalaryCompanyStore.prefs(app, company.id).getString("hourly_rate", null))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun deviceAuthenticationTicketIsSingleUseAndNeverRevivesAfterAccountRoundTrip() {
        val origin = SalaryAccountScope.capture()
        PendingSalaryCompanyAccess.authorize(company.id, origin)
        assertEquals(company.id, PendingSalaryCompanyAccess.consume())
        assertNull(PendingSalaryCompanyAccess.consume())
        PendingSalaryCompanyAccess.authorize(company.id, origin)
        account("ui-B")
        account("ui-A")
        assertFalse(PendingSalaryCompanyAccess.hasCurrentAccess())
        assertNull(PendingSalaryCompanyAccess.consume())
        PendingSalaryCompanyAccess.authorizeRecovery(origin)
        assertFalse(PendingSalaryCompanyAccess.consumeRecovery())
    }

    @Test fun staleImportLaunchIsRejectedBeforeOpeningDocumentOrDeliveringOcr() {
        val origin = SalaryAccountScope.capture()
        val intent = SalaryAccountUi.attach(Intent(app, V2PayslipImportActivity::class.java)
            .putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID, company.id)
            .putExtra(V2PayslipImportActivity.EXTRA_SOURCE_URI, "content://never-read.invalid/a.pdf"), origin)
        account("ui-B")
        account("ui-A")
        val controller = Robolectric.buildActivity(V2PayslipImportActivity::class.java, intent).create()
        try {
            assertTrue(controller.get().isFinishing)
            assertNull(SalaryAccountUi.read(intent))
        } finally {
            controller.destroy()
        }
    }

    @Test fun missingLaunchIdentityDoesNotBecomeCurrentAccount() {
        val intent = Intent(app, V2PayslipImportActivity::class.java)
            .putExtra(V2PayslipImportActivity.EXTRA_SOURCE_URI, "content://never-read.invalid/a.pdf")
        val controller = Robolectric.buildActivity(V2PayslipImportActivity::class.java, intent).create()
        try {
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test fun nestedSensitiveConfirmationClosesOnAccountChange() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = SalaryAccountScope.bindContext(controller.get())
            val dialog = SalaryAccountUi.dialog(context).setMessage("Synthetic account A data").setPositiveButton("OK", null).show()
            assertTrue(dialog.isShowing)
            account("ui-B")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun paidReportSelectionCannotContinueWithAnotherAccountOrReviveOnReturn() {
        seed("10")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var prepared = false
            PaidServiceReports.prepare(controller.get(), "horatrack_analysis") { prepared = true }
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            account("ui-B")
            seed("20")
            account("ui-A")
            // Deliberately deliver the old selection even after the dialog has been dismissed.
            dialog.listView.performItemClick(null, 0, 0L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertFalse(prepared)
            assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun preparedPaidReportMetadataRequiresItsOwnerAndOriginalSession() {
        val hash = "a".repeat(64)
        val sourceContext = SalaryAccountScope.bindContext(app)
        app.getSharedPreferences("billing_service_reports", 0).edit()
            .putString("${BillingContract.accountId("ui-A")}_$hash", JSONObject()
                .put("productId", "horatrack_analysis").put("reportId", "b".repeat(64))
                .put("name", "Synthetic private report A").toString()).commit()
        assertEquals("Synthetic private report A", BillingServiceFlow.prepared(sourceContext, "ui-A", hash)?.name)
        account("ui-B")
        assertNull(BillingServiceFlow.prepared(app, "ui-A", hash))
        assertNull(BillingServiceFlow.prepared(sourceContext, "ui-A", hash))
        account("ui-A")
        assertNull(BillingServiceFlow.prepared(sourceContext, "ui-A", hash))
        assertNotNull(BillingServiceFlow.prepared(app, "ui-A", hash))
    }

    private fun frozenPdfState(file: File, uid: String, hash: String): Bundle = Bundle().apply {
        SalaryAccountUi.save(this, SalaryAccountScope.capture().copy(processId = "synthetic-previous-process"))
        putString("pdf_owner", uid)
        putString("pdf_hash", hash)
        putString("save_file", file.absolutePath)
        putString("save_hash", hash)
    }

    @Test fun frozenPdfCanResumeForReadonlyReverificationAfterProcessRestart() {
        val file = File(app.filesDir, "billing_pdf_archive/${BillingContract.accountId("ui-A")}/example.pdf")
        file.parentFile!!.mkdirs()
        file.writeText("synthetic PDF bytes; rendering is intentionally not started")
        val state = frozenPdfState(file, "ui-A", BillingContract.documentId(file))
        val intent = Intent(app, PdfPreviewActivity::class.java).putExtra("pdf_path", file.absolutePath)
        val controller = Robolectric.buildActivity(PdfPreviewActivity::class.java, intent).create(state)
        try {
            assertFalse(controller.get().isFinishing)
            // create() does not resume/render/authorize; the ordinary billing gate still must run.
        } finally {
            controller.destroy()
        }
    }

    @Test fun frozenPdfResumeRejectsAnotherOwnerAndChangedBytes() {
        val file = File(app.filesDir, "billing_pdf_archive/${BillingContract.accountId("ui-A")}/example.pdf")
        file.parentFile!!.mkdirs()
        file.writeText("original synthetic bytes")
        val state = frozenPdfState(file, "ui-A", BillingContract.documentId(file))
        val intent = Intent(app, PdfPreviewActivity::class.java).putExtra("pdf_path", file.absolutePath)
        account("ui-B")
        val foreignController = Robolectric.buildActivity(PdfPreviewActivity::class.java, intent).create(state)
        try {
            assertTrue(foreignController.get().isFinishing)
        } finally {
            foreignController.destroy()
        }
        account("ui-A")
        file.writeText("different synthetic bytes")
        val changedController = Robolectric.buildActivity(PdfPreviewActivity::class.java, intent).create(state)
        try {
            assertTrue(changedController.get().isFinishing)
        } finally {
            changedController.destroy()
        }
    }
}
