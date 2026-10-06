package com.amaury.pointage

import android.app.Application
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import com.amaury.pointage.v2.V2EmploymentContractHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SalaryInformationSheetSaveTest {
    private fun form(): SalaryInformationSheetView {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("horatrack_v2_employment_contract_history", 0).edit().clear().commit()
        assertTrue(SalaryCompanyStore.createOrUpdate(context, SalaryCompanyStore.Company("save-test", "Entreprise", "")))
        return SalaryInformationSheetView(context).bindCompany("save-test").apply {
            val spinner = javaClass.getDeclaredField("contractType").apply { isAccessible = true }.get(this) as Spinner
            spinner.setSelection(1)
            field("weeklyHours").setText("35")
            field("hourlyRate").setText("14")
            field("entryDate").setText("01/01/2026")
            field("contractEffectiveDate").setText("01/01/2026")
            field("contractSource").setText("Contrat signé")
        }
    }

    private fun SalaryInformationSheetView.field(name: String): EditText =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as EditText

    private fun SalaryInformationSheetView.save() {
        findViewWithTag<Button>("information_sheet_save").performClick()
    }

    @Test fun invalidTaxDoesNotPublishContract() {
        val form = form()
        form.field("incomeTaxRate").setText("101")
        form.save()
        assertTrue(V2EmploymentContractHistoryStore.readConfirmed(form.context).snapshots.isEmpty())
        assertTrue(form.field("incomeTaxRate").error != null)
    }

    @Test fun invalidOptionalAmountsDoNotPublishContract() {
        listOf("seniorityRttDifferential", "mutualEmployeeAmount", "providentEmployeeAmount",
            "transportEmployeeAmount", "employerProtectionTaxableAmount", "employeeProvidentNonDeductibleAmount").forEach { name ->
            val form = form()
            form.field(name).setText("-1")
            form.save()
            assertTrue(name, V2EmploymentContractHistoryStore.readConfirmed(form.context).snapshots.isEmpty())
            assertTrue(name, form.field(name).error != null)
        }
    }

    @Test fun validFormStillPublishesConfirmedContract() {
        val form = form()
        form.field("incomeTaxRate").setText("0")
        form.field("mutualEmployeeAmount").setText("25")
        form.save()
        assertEquals(1, V2EmploymentContractHistoryStore.readConfirmed(form.context).snapshots.size)
        assertEquals("25.0", SalaryCompanyStore.prefs(form.context, "save-test").getString("mutual_employee_amount", null))
    }
}
