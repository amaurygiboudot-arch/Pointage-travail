package com.amaury.pointage.v2.ui

object HomeTabVisibilityPolicyV2 {
    const val INACTIVITY_TIMEOUT_MS = 10_000L

    fun shouldHide(isHome: Boolean, inactiveForMs: Long): Boolean =
        isHome && inactiveForMs >= INACTIVITY_TIMEOUT_MS
}
