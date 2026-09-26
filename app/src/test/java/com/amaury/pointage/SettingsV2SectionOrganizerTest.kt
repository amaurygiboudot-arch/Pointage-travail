package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsV2SectionOrganizerTest {
    @Test
    fun `sections follow canonical settings v2 order`() {
        val tags = listOf(
            SettingsV2Host.TAG_UPDATES,
            SettingsV2Host.TAG_ACCOUNT_SECURITY,
            SettingsV2Host.TAG_POINTAGE,
            SettingsV2Host.TAG_CELESTIAL,
            SettingsV2Host.TAG_PERSONALIZATION,
            SettingsV2Host.TAG_WIDGET,
            SettingsV2Host.TAG_DRIVE,
            SettingsV2Host.TAG_HELP,
            SettingsV2Host.TAG_EXTRAS
        )

        val priorities = tags.map { tag ->
            SettingsV2SectionOrganizer.canonicalSectionPriority(tag)
                ?: error("Missing priority for $tag")
        }

        assertEquals(listOf(5, 10, 20, 30, 31, 32, 40, 50, 55), priorities)
        assertTrue(priorities.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun `unknown section has no canonical priority`() {
        assertEquals(null, SettingsV2SectionOrganizer.canonicalSectionPriority("legacy_unknown"))
    }
}
