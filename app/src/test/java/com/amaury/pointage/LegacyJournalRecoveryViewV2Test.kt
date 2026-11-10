package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.Button
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
}
