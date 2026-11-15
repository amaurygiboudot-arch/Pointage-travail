package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.amaury.pointage.v2.CompanyEmployeeDeductionStoreV2
import com.amaury.pointage.v2.CompanyIncomeTaxRateStoreV2
import com.amaury.pointage.v2.RestoreGuardedPreferences
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.V2SalaryCalculationRoute
import com.amaury.pointage.v2.engine.CompanyEmployeeDeductionResolverV2
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[28], shadows=[GpsDeliveryAuthShadow::class], instrumentedPackages=["com.google.firebase.auth"])
class SalaryDatedPayrollFactsUiV2Test {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    private val company = SalaryCompanyStore.Company("dated-ui", "Entreprise synthétique", "")
    private val month = YearMonth.of(2026, 4)

    @Before fun setup() {
        val auth=Mockito.mock(FirebaseAuth::class.java)
        val user=Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("dated-payroll-ui")
        Mockito.`when`(auth.currentUser).thenReturn(user)
        GpsDeliveryAuthShadow.auth=auth
        SalaryAccountScope.capture()
        assertTrue(SalaryCompanyStore.createOrUpdate(app, company))
        SalaryCompanyStore.prefs(app, company.id).edit().clear().commit()
        selectMonth(month)
    }

    private fun selectMonth(value: YearMonth) {
        RestoreGuardedPreferences.open(app, "navigation_state", Context.MODE_PRIVATE).edit()
            .putLong("report_month_ms", value.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()).commit()
    }
    private fun AlertDialog.field(tag: String): EditText = window!!.decorView.findViewWithTag(tag)
    private fun AlertDialog.save() = getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    private fun editor(form: SalaryInformationSheetView, section: String, add: String): AlertDialog {
        form.findViewWithTag<Button>(section).performClick()
        ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.findViewWithTag<Button>(add).performClick()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun formField(form: SalaryInformationSheetView, name: String) =
        form.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(form) as EditText

    @Test fun directDeductionEntrySavesOnlyDisplayedMonthAndKeepsContractDraft() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val form=SalaryInformationSheetView(controller.get()).bindCompany(company.id)
            formField(form, "hourlyRate").setText("18")
            val dialog=editor(form, "company_employee_deductions", "dated_deduction_add")
            assertEquals("04/2026", dialog.field("dated_deduction_start").text.toString())
            assertEquals("04/2026", dialog.field("dated_deduction_end").text.toString())
            dialog.field("dated_deduction_amount").setText("23.40")
            dialog.field("dated_deduction_source").setText("Bulletin synthétique avril")
            dialog.save()
            val kind=CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE
            assertEquals(23.40, CompanyEmployeeDeductionStoreV2.resolve(app, company.id, month)[kind].amount!!, 0.0)
            assertNull(CompanyEmployeeDeductionStoreV2.resolve(app, company.id, month.minusMonths(1))[kind].amount)
            assertNull(CompanyEmployeeDeductionStoreV2.resolve(app, company.id, month.plusMonths(1))[kind].amount)
            assertNull(SalaryCompanyStore.prefs(app, company.id).getString("mutual_employee_amount", null))
            assertEquals("18", formField(form, "hourlyRate").text.toString())
            assertTrue(form.findViewWithTag<TextView>("company_employee_deduction_status").text.contains("23,40"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun missingDeductionDateOrSourceKeepsDraftWithoutPublishing() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val form=SalaryInformationSheetView(controller.get()).bindCompany(company.id)
            val dialog=editor(form, "company_employee_deductions", "dated_deduction_add")
            dialog.field("dated_deduction_amount").setText("0")
            dialog.field("dated_deduction_start").setText("")
            dialog.field("dated_deduction_source").setText("Source synthétique")
            dialog.save()
            assertTrue(dialog.isShowing)
            assertNotNull(dialog.field("dated_deduction_start").error)
            assertTrue(CompanyEmployeeDeductionStoreV2.read(app, company.id).records.isEmpty())
            dialog.field("dated_deduction_start").setText("04/2026")
            dialog.field("dated_deduction_source").setText("")
            dialog.save()
            assertNotNull(dialog.field("dated_deduction_source").error)
            assertEquals("0", dialog.field("dated_deduction_amount").text.toString())
            assertTrue(CompanyEmployeeDeductionStoreV2.read(app, company.id).records.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun directTaxEntryRequiresValidDateAndSourceAndRemainsMonthly() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val form=SalaryInformationSheetView(controller.get()).bindCompany(company.id)
            val dialog=editor(form, "company_income_tax_history", "dated_income_tax_add")
            dialog.field("dated_income_tax_rate").setText("101")
            dialog.field("dated_income_tax_source").setText("Taux synthétique avril")
            dialog.save()
            assertNotNull(dialog.field("dated_income_tax_rate").error)
            dialog.field("dated_income_tax_rate").setText("0")
            dialog.field("dated_income_tax_start").setText("")
            dialog.save()
            assertNotNull(dialog.field("dated_income_tax_start").error)
            assertTrue(CompanyIncomeTaxRateStoreV2.read(app, company.id).records.isEmpty())
            dialog.field("dated_income_tax_start").setText("04/2026")
            dialog.field("dated_income_tax_source").setText("")
            dialog.save()
            assertNotNull(dialog.field("dated_income_tax_source").error)
            dialog.field("dated_income_tax_source").setText("Taux synthétique avril")
            dialog.save()
            assertEquals(0.0, CompanyIncomeTaxRateStoreV2.resolve(app, company.id, month).rate!!, 0.0)
            assertNull(CompanyIncomeTaxRateStoreV2.resolve(app, company.id, month.plusMonths(1)).rate)
            assertNull(SalaryCompanyStore.prefs(app, company.id).getString("income_tax_rate_percent", null))
            assertTrue(form.findViewWithTag<TextView>("company_income_tax_status").text.contains("0,00"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun legacyValuesStayVisibleAsDraftsAndDoNotSatisfyMonth() {
        SalaryCompanyStore.prefs(app, company.id).edit()
            .putString("income_tax_rate_percent", "4.0").putString("mutual_employee_amount", "20.0").commit()
        val form=SalaryInformationSheetView(app).bindCompany(company.id)
        val tax=form.findViewWithTag<TextView>("company_income_tax_status").text.toString()
        val deduction=form.findViewWithTag<TextView>("company_employee_deduction_status").text.toString()
        assertTrue(tax.contains("2026-04") && tax.contains("brouillon") && tax.contains("Aucun taux daté"))
        assertTrue(deduction.contains("20.0") && deduction.contains("brouillon") && deduction.contains("à confirmer"))
        assertTrue(CompanyIncomeTaxRateStoreV2.read(app, company.id).records.isEmpty())
        assertTrue(CompanyEmployeeDeductionStoreV2.read(app, company.id).records.isEmpty())
    }

    @Test fun blockedWorkspaceDisplaysExistingSpecificDiagnostics() {
        val details=V2SalaryCalculationRoute.resolveDetails(app, company, month.year, month.monthValue-1)
        assertEquals(V2SalaryCalculationRoute.Route.BLOCKED, details.route)
        assertTrue(details.warnings.size>1)
        val workspace=SalaryPayslipWorkspaceView(app, company)
        val displayed=workspace.findViewWithTag<TextView>("salary_route_diagnostics").text.toString()
        details.warnings.forEach { assertTrue(it, displayed.contains(it)) }
        assertTrue(displayed.contains(company.name) && displayed.contains(month.toString()))
    }
}
