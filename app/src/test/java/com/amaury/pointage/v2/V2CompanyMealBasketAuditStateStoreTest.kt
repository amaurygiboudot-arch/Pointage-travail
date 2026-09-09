package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2CompanyMealBasketAuditStateStoreTest {
    @Test
    fun `cap accepte 300 et refuse 301 sans troncature`() {
        assertTrue(V2CompanyMealBasketAuditStateStore.canPersistCompleteRecordSet(300))
        assertFalse(V2CompanyMealBasketAuditStateStore.canPersistCompleteRecordSet(301))
    }
}
