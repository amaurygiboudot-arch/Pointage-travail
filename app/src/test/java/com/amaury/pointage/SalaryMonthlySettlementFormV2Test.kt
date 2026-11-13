package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import com.amaury.pointage.v2.ConfirmedMonthlyPayrollSettlementV2 as Settlement
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
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28],shadows=[GpsDeliveryAuthShadow::class],instrumentedPackages=["com.google.firebase.auth"])
class SalaryMonthlySettlementFormV2Test {
    private val app get()=RuntimeEnvironment.getApplication() as Context
    private lateinit var auth: FirebaseAuth
    private val company=SalaryCompanyStore.Company("form-fixture","Synthetic form company","")
    private fun account(uid:String) { val user=Mockito.mock(FirebaseUser::class.java);Mockito.`when`(user.uid).thenReturn(uid);Mockito.`when`(auth.currentUser).thenReturn(user);SalaryAccountScope.capture() }
    @Before fun setup() {
        auth=Mockito.mock(FirebaseAuth::class.java);GpsDeliveryAuthShadow.auth=auth;account("settlement-form-A")
        SalaryAccountScope.preferences(app,Settlement.PREFS).edit().clear().commit()
        assertTrue(SalaryCompanyStore.createOrUpdate(app,company))
    }
    private fun children(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap{children(view.getChildAt(it))} else emptyList())
    private fun inputs(dialog:AlertDialog)=children(dialog.window!!.decorView).filterIsInstance<EditText>()
    private fun consent(dialog:AlertDialog)=children(dialog.window!!.decorView).filterIsInstance<CheckBox>().single{it.text.contains("montants constatés")}
    private fun exhaustive(dialog:AlertDialog)=children(dialog.window!!.decorView).filterIsInstance<CheckBox>().single{it.text.toString()==Settlement.ADJUSTMENTS_CONFIRMATION}
    private fun fillObservation(dialog:AlertDialog) {
        inputs(dialog).single{it.hint.contains("Net réellement")}.setText("1245.67")
        inputs(dialog).single{it.hint.contains("Source vérifiable")}.setText("Synthetic source 17")
    }

    @Test fun editingAmountsOrSourceRevokesConfirmationAndExhaustiveness() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var saved=false
            SalaryMonthlySettlementFormV2.show(controller.get(),company,"2026-04",{true}){saved=true}
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            fillObservation(dialog)
            inputs(dialog).take(4).forEach{it.setText("0")}
            exhaustive(dialog).isChecked=true;consent(dialog).isChecked=true
            inputs(dialog).single{it.hint.contains("Net réellement")}.setText("1245.68")
            assertFalse(consent(dialog).isChecked);assertFalse(exhaustive(dialog).isChecked)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(saved);assertNull(Settlement.read(app,company.id,"2026-04").record)
            consent(dialog).isChecked=true
            inputs(dialog).single{it.hint.contains("Source vérifiable")}.setText("Updated synthetic source")
            assertFalse(consent(dialog).isChecked)
            consent(dialog).isChecked=true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(ShadowToast.getTextOfLatestToast()?.toString(),saved)
            val record=Settlement.read(app,company.id,"2026-04").record!!
            assertEquals(1245.68,record.netPaidObserved!!,0.0)
            assertFalse(record.adjustmentsComplete)
            assertNull(record.netSocialObserved)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun staleMonthOrRemovedCompanyKeepsTheDraftWithoutWriting() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var current=true
            SalaryMonthlySettlementFormV2.show(controller.get(),company,"2026-04",{current}){}
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val dialog=ShadowAlertDialog.getLatestAlertDialog();fillObservation(dialog);consent(dialog).isChecked=true
            current=false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertNull(Settlement.read(app,company.id,"2026-04").record)
            assertTrue(dialog.isShowing)
            assertEquals("1245.67",inputs(dialog).single{it.hint.contains("Net réellement")}.text.toString())
            current=true;consent(dialog).isChecked=true;assertTrue(SalaryCompanyStore.remove(app,company.id))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(SalaryAccountScope.preferences(app,Settlement.PREFS).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun capturedFormCannotWriteAnotherAccountWithTheSameCompanyId() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            SalaryMonthlySettlementFormV2.show(controller.get(),company,"2026-04",{true}){}
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val dialog=ShadowAlertDialog.getLatestAlertDialog();fillObservation(dialog);consent(dialog).isChecked=true
            val save=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            account("settlement-form-B");assertTrue(SalaryCompanyStore.createOrUpdate(app,company))
            save.performClick()
            assertNull(Settlement.read(app,company.id,"2026-04").record)
            account("settlement-form-A");consent(dialog).isChecked=true;save.performClick()
            assertNull(Settlement.read(app,company.id,"2026-04").record)
        } finally { controller.pause().stop().destroy() }
    }
}
