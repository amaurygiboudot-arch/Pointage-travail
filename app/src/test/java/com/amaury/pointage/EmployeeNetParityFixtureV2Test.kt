package com.amaury.pointage

import com.amaury.pointage.v2.engine.CompanyPayrollOverridesV2
import com.amaury.pointage.v2.engine.EmployeeNetProjectionV2
import com.amaury.pointage.v2.engine.PlasturgieProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import com.amaury.pointage.v2.engine.SegmentedCashGrossAssemblyResultV2
import com.amaury.pointage.v2.engine.SegmentedCashGrossNetProjectionV2
import com.amaury.pointage.v2.model.ContractTypeV2
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The same independent, synthetic cent fixtures are consumed by the Swift tests. */
@RunWith(Parameterized::class)
class EmployeeNetParityFixtureV2Test(private val id: String, private val input: JSONObject) {
    @Test
    fun sharedFixtureUsesTheSameAmountsThroughDirectMonthlyAndSegmentedRoutes() {
        val year = input.getInt("year")
        val period = YearMonth.of(year, input.getInt("month"))
        val company = CompanyPayrollOverridesV2.Snapshot(
            companyId = "synthetic-net-parity", idcc = null, referenceDate = period.atEndOfMonth(),
            entryDate = LocalDate.of(2020, 1, 1), seniorityMonths = 75,
            contractType = ContractTypeV2.valueOf(input.getString("contractType")),
            contractualWeeklyMinutes = input.getInt("weeklyMinutes"), forfaitAnnualDays = null,
            unpaidAbsenceDays = 0, hasUnpaidAbsence = false,
            mutualEmployeeAmount = input.number("mutual"), providentEmployeeAmount = input.number("provident"),
            transportEmployeeAmount = input.number("transport"),
            employerProtectionTaxableAmount = input.number("employerTaxable"),
            employeeProvidentNonDeductibleAmount = input.number("employeeNonDeductible"),
            incomeTaxRate = input.number("rate"),
            professionalStatus = if (input.isNull("professionalStatus")) null else input.getString("professionalStatus"),
            protectionCategory = PlasturgieProtectionCategoryV2.classify(null, period.atEndOfMonth(), null),
            warnings = emptyList(),
            alsaceMoselleLocalRegime = if (input.isNull("alsaceMoselle")) null else input.getBoolean("alsaceMoselle"),
            employerProtectionCsgCrdsBaseAmount = input.number("employerCsgBase"),
            verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride(),
            benefitsInKindGross = input.getDouble("benefitsGross"),
            benefitsInKindReliable = input.getBoolean("benefitsReliable")
        )
        val cashGross = input.getDouble("cashGross")
        val complementaryMinutes = input.getInt("complementaryMinutes")
        val upstreamReliable = input.getBoolean("upstreamReliable")
        val direct = EmployeeNetProjectionV2.calculate(cashGross, year, company, complementaryMinutes, upstreamReliable)
        assertProjection("direct", direct)
        val monthly = V2SalaryNetBridgeV2.projectKnownGross(cashGross, year, company, complementaryMinutes, upstreamReliable)
        assertProjection("monthly", monthly)
        val segmented = SegmentedCashGrossNetProjectionV2.project(
            cash = SegmentedCashGrossAssemblyResultV2(
                workedGross = cashGross, additionalCashGross = 0.0,
                cashGross = cashGross, reliable = upstreamReliable, warnings = emptyList()
            ),
            year = year, company = company, complementaryMinutes = complementaryMinutes,
            complementaryMinutesReliable = true
        )
        assertEquals("$id segmented complete", input.getJSONObject("expected").getBoolean("complete"), segmented.netBeforeIncomeTaxComplete)
        assertProjection("segmented", segmented.projection)
    }

    private fun assertProjection(route: String, actual: EmployeeNetProjectionV2.Result?) {
        val expected = input.getJSONObject("expected")
        assertEquals("$id $route complete", expected.getBoolean("complete"), actual?.netBeforeIncomeTaxComplete ?: false)
        assertAmount(route, expected, "beforeCents", actual?.netBeforeIncomeTax)
        assertAmount(route, expected, "taxableCents", actual?.netTaxable)
        assertAmount(route, expected, "pasCents", actual?.incomeTax)
        assertAmount(route, expected, "afterCents", actual?.netAfterIncomeTax)
    }

    private fun assertAmount(route: String, expected: JSONObject, key: String, actual: Double?) {
        val label = "$id $route $key"
        if (expected.isNull(key)) assertNull(label, actual) else {
            assertTrue("$label must exist", actual != null)
            assertEquals(label, expected.getLong(key) / 100.0, actual!!, 1e-9)
        }
    }

    private fun JSONObject.number(key: String): Double? = if (isNull(key)) null else getDouble(key)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> {
            val path = "ios/HPTravail/HPTravailTests/Fixtures/net-projection-v2.json"
            val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, path) }.firstOrNull { it.isFile }
                ?: error("Shared payroll fixture absent: $path")
            val root = JSONObject(fixture.readText())
            assertEquals(1, root.getInt("schemaVersion"))
            val cases = root.getJSONArray("cases")
            assertEquals(35, root.getInt("caseCount"))
            assertEquals(root.getInt("caseCount"), cases.length())
            val ids = mutableSetOf<String>()
            return (0 until cases.length()).map { index ->
                val input = cases.getJSONObject(index)
                val id = input.getString("id")
                check(ids.add(id)) { "Duplicate payroll fixture: $id" }
                arrayOf(id, input)
            }
        }
    }
}
