package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class PersonalizationProfileV2Test {
    @Test fun `export round trip preserves all explicit preferences`() {
        val value = PersonalizationProfileV2(2f, true, false, 4f, "economy", false)
        assertEquals(value, PersonalizationProfileV2.decode(value.encode()))
        assertTrue(value.effectiveReduceMotion)
    }
    @Test fun `manual normal restores user's explicit motion choice`() {
        val value = PersonalizationProfileV2(context = "economy")
        assertTrue(value.effectiveReduceMotion)
        assertFalse(value.copy(context = "normal").effectiveReduceMotion)
        assertTrue(value.copy(context = "normal", reduceMotion = true).effectiveReduceMotion)
    }
    @Test fun `import rejects future schemas wrong types and unknown keys`() {
        val raw = PersonalizationProfileV2().encode()
        listOf(raw.replace("\"schemaVersion\":1", "\"schemaVersion\":99"),
            raw.replace("\"highContrast\":false", "\"highContrast\":\"false\""),
            raw.dropLast(1) + ",\"salary\":999}", "{}", "x".repeat(4097)
        ).forEach { assertTrue(runCatching { PersonalizationProfileV2.decode(it) }.isFailure) }
    }
    @Test fun `invalid numeric values and unknown context fail before storage`() {
        listOf(PersonalizationProfileV2(textScale = Float.NaN),
            PersonalizationProfileV2(textScale = .5f), PersonalizationProfileV2(readerScale = 5f),
            PersonalizationProfileV2(context = "unknown")
        ).forEach { assertTrue(runCatching { it.validated() }.isFailure) }
    }
    @Test fun `all private account profiles are excluded from generic backup`() {
        assertFalse(BackupSecurityPolicy.canTransferPreferenceFile("personalization_private_v2_accountA"))
        assertFalse(CloudSettingsBackupPolicy.canTransferPreferenceFile("personalization_private_v2_accountA"))
        assertTrue(BackupSecurityPolicy.canTransferPreferenceFile("appearance_settings"))
    }
}
