package com.amaury.pointage.billing

/** Missing or malformed server preflight is never permission for another subscription. */
internal object SubscriptionPurchasePolicy {
    fun serverAllowsNewSubscription(existing: Any?, suspended: Any?): Boolean = existing == false && suspended == false
}
