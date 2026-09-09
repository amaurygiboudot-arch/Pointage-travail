package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmployerGeneralReductionContextV2
import java.time.YearMonth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RgduPaidHoursContextV2Test {
    private val month = YearMonth.of(2026, 9)

    @Test
    fun `legacy stored context stays readable but paid hours remain unknown`() {
        val decoded = CompanyEmployerGeneralReductionContextStoreV2.decode(
            """[{"id":"c1","month":"2026-09","fullMonthPresent":true,"standardCommonLawCaseConfirmed":true,"noOtherEmployerReductionConfirmed":true,"source":"DSN 09/2026"}]"""
        )

        assertTrue(decoded.reliable)
        assertNull(decoded.records.single().paidHoursComplete)
        val snapshot = EmployerGeneralReductionContextV2.resolve(decoded.records, month)
        assertTrue(snapshot.reliable)
        assertNull(snapshot.paidHoursComplete)
    }

    @Test
    fun `explicit paid hours completeness is preserved`() {
        val complete = CompanyEmployerGeneralReductionContextStoreV2.decode(
            """[{"id":"c1","month":"2026-09","fullMonthPresent":true,"standardCommonLawCaseConfirmed":true,"noOtherEmployerReductionConfirmed":true,"source":"Contrôle employeur","paidHoursComplete":true}]"""
        )
        val incomplete = CompanyEmployerGeneralReductionContextStoreV2.decode(
            """[{"id":"c2","month":"2026-09","fullMonthPresent":true,"standardCommonLawCaseConfirmed":true,"noOtherEmployerReductionConfirmed":true,"source":"Contrôle employeur","paidHoursComplete":false}]"""
        )

        assertTrue(complete.reliable)
        assertTrue(complete.records.single().paidHoursComplete == true)
        assertTrue(incomplete.reliable)
        assertTrue(incomplete.records.single().paidHoursComplete == false)
    }

    @Test
    fun `malformed paid hours flag never becomes a confirmed fact`() {
        val decoded = CompanyEmployerGeneralReductionContextStoreV2.decode(
            """[{"id":"c1","month":"2026-09","fullMonthPresent":true,"standardCommonLawCaseConfirmed":true,"noOtherEmployerReductionConfirmed":true,"source":"import","paidHoursComplete":"true"}]"""
        )

        assertFalse(decoded.reliable)
        assertTrue(decoded.records.isEmpty())
    }
}
