package com.amaury.pointage.v2.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabVisibilityPolicyV2Test {
    @Test
    fun `home tabs stay visible before ten seconds`() {
        assertFalse(HomeTabVisibilityPolicyV2.shouldHide(true, 9_999L))
    }

    @Test
    fun `home tabs hide after ten seconds of inactivity`() {
        assertTrue(HomeTabVisibilityPolicyV2.shouldHide(true, 10_000L))
    }

    @Test
    fun `other tabs never use home auto hide`() {
        assertFalse(HomeTabVisibilityPolicyV2.shouldHide(false, 60_000L))
    }
}
