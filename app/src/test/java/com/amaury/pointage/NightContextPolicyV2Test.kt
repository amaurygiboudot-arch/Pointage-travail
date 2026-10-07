package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class NightContextPolicyV2Test {
    @Test fun `overnight interval includes start midnight and excludes end`() {
        for (minute in 0..1439) {
            val expected = if (minute >= 1320 || minute < 420) "night" else "normal"
            assertEquals(expected, NightContextPolicyV2.resolve("normal", true, 1320, 420, minute))
        }
    }
    @Test fun `day interval and explicit context priority`() {
        for (minute in 0..1439) {
            assertEquals(if (minute in 60 until 180) "night" else "normal", NightContextPolicyV2.resolve("normal", true, 60, 180, minute))
            assertEquals("normal", NightContextPolicyV2.resolve("normal", false, 60, 180, minute))
            for (context in listOf("work", "home", "night", "economy")) assertEquals(context, NightContextPolicyV2.resolve(context, true, 60, 180, minute))
        }
    }
    @Test fun `invalid interval never becomes always enabled`() {
        assertTrue(runCatching { NightContextPolicyV2.resolve("normal", true, 0, 0, 1) }.isFailure)
        assertTrue(runCatching { NightContextPolicyV2.resolve("normal", true, 0, 60, 1440) }.isFailure)
    }
}
