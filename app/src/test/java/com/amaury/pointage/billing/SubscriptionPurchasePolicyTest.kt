package com.amaury.pointage.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPurchasePolicyTest {
    @Test fun suspensionAndResumptionBothPreventASecondSubscription() {
        assertFalse(SubscriptionPurchasePolicy.serverAllowsNewSubscription(true, true))
        assertFalse(SubscriptionPurchasePolicy.serverAllowsNewSubscription(true, false))
    }
    @Test fun onlyConfirmedAbsenceAllowsNewSubscription() {
        assertTrue(SubscriptionPurchasePolicy.serverAllowsNewSubscription(false, false))
        assertFalse(SubscriptionPurchasePolicy.serverAllowsNewSubscription(null, false))
        assertFalse(SubscriptionPurchasePolicy.serverAllowsNewSubscription(false, null))
        assertFalse(SubscriptionPurchasePolicy.serverAllowsNewSubscription("false", false))
        assertFalse(SubscriptionPurchasePolicy.serverAllowsNewSubscription(false, true))
    }
}
