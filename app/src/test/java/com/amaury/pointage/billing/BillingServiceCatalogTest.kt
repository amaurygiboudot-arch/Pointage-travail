package com.amaury.pointage.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingServiceCatalogTest {
    @Test fun approvedServicesHaveStableDistinctIdsAndTargetPrices() {
        assertEquals(mapOf(
            "horatrack_analysis" to 499,
            "horatrack_payslip_comparison" to 699,
            "horatrack_annual_review" to 999,
            "horatrack_claim_dossier" to 1499
        ), BillingServiceCatalog.services.associate { it.productId to it.targetPriceEuroCents })
        assertEquals(4, BillingServiceCatalog.services.map { it.productId }.toSet().size)
    }

    @Test fun missingDeliveryCannotBePurchasedAndPdfIsIncluded() {
        BillingServiceCatalog.services.forEach { service ->
            assertEquals(BillingServiceCatalog.Availability.DELIVERY_NOT_READY, service.availability)
            assertFalse(service.purchasable)
            assertTrue(service.pdfIncluded)
        }
    }

    @Test fun targetPricesUseEuroCentsWithoutFloatingPointRounding() {
        assertEquals(listOf("4,99 €", "6,99 €", "9,99 €", "14,99 €"),
            BillingServiceCatalog.services.map { it.targetPriceLabel })
    }
}
