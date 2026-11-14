package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.V2PreferenceValues
import android.content.SharedPreferences
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class LegacyJournalRecoveryViewV2Test {
    private lateinit var auth: FirebaseAuth
    private lateinit var activity: Activity
    private lateinit var view: LegacyJournalRecoveryViewV2

    @Before fun setup() {
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account("A")
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
            .edit().putString("history", "[]").commit()
        view = LegacyJournalRecoveryViewV2(activity)
        activity.setContentView(view)
        view.onWindowFocusChanged(true)
    }

    private fun account(uid: String?) {
        val user = uid?.let {
            Mockito.mock(FirebaseUser::class.java).also { user ->
                Mockito.`when`(user.uid).thenReturn(uid)
                Mockito.`when`(user.email).thenReturn("$uid@example.test")
            }
        }
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }

    @Test fun declarationStartsUncheckedAndCancelDoesNotAssign() {
        (view.getChildAt(1) as Button).performClick()
        // Dialog.show posts OnShowListener; inspect the state after that UI turn.
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertTrue(activity.getSharedPreferences("horatrack_v2_legacy_recovery_claim", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals("[]", activity.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE).getString("history", null))
    }

    @Test fun changingAccountDismissesOwnershipDialogWithoutAssignment() {
        (view.getChildAt(1) as Button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        account("B")
        view.onWindowFocusChanged(true)
        assertFalse(dialog.isShowing)
        assertTrue(activity.getSharedPreferences("horatrack_v2_legacy_recovery_claim", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun signedOutCannotOpenRecoveryConfirmation() {
        account(null)
        view.onWindowFocusChanged(true)
        assertFalse((view.getChildAt(1) as Button).isEnabled)
    }

    private fun openRefusedDiagnostic(): AlertDialog {
        activity.getSharedPreferences("pointage", Context.MODE_PRIVATE).edit()
            .putString("data", """[{"entry":1000,"exit":2000,"pauses":[]}]""").commit()
        (view.getChildAt(1) as Button).performClick()
        val button = view.findViewWithTag<Button>("legacy_journal_diagnostic")
        assertEquals(android.view.View.VISIBLE, button.visibility)
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    @Test fun refusedRecoveryAllowsDeclaredReadOnlyDiagnosticAndCancelDoesNotAssign() {
        val dialog = openRefusedDiagnostic()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        val declaration = dialog.window!!.decorView.findViewWithTag<CheckBox>("legacy_journal_diagnostic_declaration")
        assertFalse(declaration.isChecked)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.getSharedPreferences("horatrack_v2_legacy_recovery_claim", 0).all.isEmpty())
        assertEquals("[]", activity.getSharedPreferences("horatrack_v2_test_runtime", 0).getString("history", null))
    }

    @Test fun diagnosticDetailClearsAfterAccountRoundTripWithoutClaimingJournal() {
        val dialog = openRefusedDiagnostic()
        dialog.window!!.decorView.findViewWithTag<CheckBox>("legacy_journal_diagnostic_declaration").isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val result = ShadowAlertDialog.getLatestAlertDialog()
        val text = result.window!!.decorView.findViewWithTag<TextView>("legacy_journal_diagnostic_report")
        assertTrue(text.text.toString().contains("REAL_ARRIVAL_MISSING"))
        account("B"); SalaryAccountScope.capture()
        account("A"); SalaryAccountScope.capture()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(result.isShowing)
        assertEquals("", text.text.toString())
        assertTrue(activity.getSharedPreferences("horatrack_v2_legacy_recovery_claim", 0).all.isEmpty())
    }

    @Test fun sourceChangeDismissesDiagnosticDeclarationBeforeAnyDetails() {
        val dialog = openRefusedDiagnostic()
        activity.getSharedPreferences("pointage", 0).edit().putString("data", "[]").commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertTrue(activity.getSharedPreferences("horatrack_v2_legacy_recovery_claim", 0).all.isEmpty())
    }

    @Test fun pendingRestoreInvalidatesDisplayedDiagnosticWithoutRecoveryOrPreferenceEdit() {
        val counted = Robolectric.buildActivity(DiagnosticCountingActivity::class.java).setup().get()
        activity = counted
        view = LegacyJournalRecoveryViewV2(activity)
        activity.setContentView(view)
        view.onWindowFocusChanged(true)
        val physical = V2RuntimeAccountScope.fileName("horatrack_v2_test_runtime", "uid:A")!!
        val destination = activity.getSharedPreferences(physical, 0)
        destination.edit().putString("history", "[]").commit()
        for (state in listOf("PREPARED", "COMMITTED")) {
            val declaration = openRefusedDiagnostic()
            declaration.window!!.decorView.findViewWithTag<CheckBox>("legacy_journal_diagnostic_declaration").isChecked = true
            declaration.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val result = ShadowAlertDialog.getLatestAlertDialog()
            val detail = result.window!!.decorView.findViewWithTag<TextView>("legacy_journal_diagnostic_report")
            val journal = File(activity.noBackupFilesDir, "agkgmg_restore_transaction_v1.json")
            val record = JSONObject().put("version", 1).put("scope", "uid:A").put("state", state)
                .put("entries", JSONArray().put(JSONObject().put("logical", "horatrack_v2_test_runtime")
                    .put("physical", physical).put("before", V2PreferenceValues.encode(mapOf("history" to "before")))
                    .put("after", V2PreferenceValues.encode(mapOf("history" to "[]")))))
            val bytes = record.toString().toByteArray(Charsets.UTF_8)
            journal.writeBytes(bytes)
            try {
                val edits = counted.preferenceEdits
                view.onWindowFocusChanged(true)
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(result.isShowing)
                assertEquals("", detail.text.toString())
                assertEquals(edits, counted.preferenceEdits)
                assertEquals("[]", destination.getString("history", null))
                assertArrayEquals(bytes, journal.readBytes())
                view.findViewWithTag<Button>("legacy_journal_diagnostic").performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(edits, counted.preferenceEdits)
                assertArrayEquals(bytes, journal.readBytes())
            } finally { journal.delete() }
        }
    }
}

/** Counts editor creation, including writes that would put back the same values. */
class DiagnosticCountingActivity : Activity() {
    var preferenceEdits = 0
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val original = super.getSharedPreferences(name, mode)
        return object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                preferenceEdits++
                return original.edit()
            }
        }
    }
}
