package com.amaury.pointage.v2

import org.junit.Assert.*
import org.junit.Test

class ConfirmedMonthlySicknessCashV2Test {
    private fun record(direct: Double? = 100.0, subrogated: Double? = null) =
        ConfirmedMonthlySicknessCashV2.Record("company", "2026-10", direct, subrogated, "Décompte CPAM du 07/10, référence 42", 1L)
    @Test fun unknownIsNotZeroAndRecipientsRemainSeparate() {
        val direct = record()
        assertTrue(ConfirmedMonthlySicknessCashV2.valid(direct))
        assertNull(direct.subrogatedEmployerNetBeforeTax)
        val subrogated = record(null, 200.0)
        assertTrue(ConfirmedMonthlySicknessCashV2.valid(subrogated))
        assertNull(subrogated.directEmployeeNetBeforeTax)
        assertTrue(ConfirmedMonthlySicknessCashV2.valid(record(0.0, 0.0)))
        assertFalse(ConfirmedMonthlySicknessCashV2.valid(record(null, null)))
    }
    @Test fun invalidAmountOrUnscopedEvidenceCannotBeConfirmed() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertFalse(ConfirmedMonthlySicknessCashV2.valid(record(value)))
        }
        assertFalse(ConfirmedMonthlySicknessCashV2.valid(record().copy(companyId = "")))
        assertFalse(ConfirmedMonthlySicknessCashV2.valid(record().copy(period = "2026-13")))
        assertFalse(ConfirmedMonthlySicknessCashV2.valid(record().copy(source = " ")))
        assertFalse(ConfirmedMonthlySicknessCashV2.valid(record().copy(confirmedAtMs = 0)))
    }
}
