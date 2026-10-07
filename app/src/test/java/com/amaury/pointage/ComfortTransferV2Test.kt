package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class ComfortTransferV2Test {
    private val fixture = """{"format":"agkgmg.comfort","version":1,"highContrast":true,"reduceMotion":false,"readerScale":2.25}"""
    @Test fun `shared fixture and round trip preserve exact portable values`() {
        val value = ComfortTransferV2.decode(fixture)
        assertEquals(ComfortTransferV2(true, false, 2.25f), value)
        assertEquals(value, ComfortTransferV2.decode(value.encode()))
        assertEquals(value, ComfortTransferV2.decode(fixture.replace("\"version\":1", "\"version\":1.0")))
    }
    @Test fun `applying shared preferences preserves every native-only setting`() {
        val native = PersonalizationProfileV2(1.75f, false, true, 1f, "economy", false)
        val result = ComfortTransferV2.decode(fixture).applyTo(native)
        assertEquals(native.copy(highContrast = true, reduceMotion = false, readerScale = 2.25f), result)
        assertTrue(result.effectiveReduceMotion)
    }
    @Test fun `malformed fields versions extra content and out of range values fail`() {
        listOf(fixture.replace("2.25", "true"), fixture.replace("2.25", "4.00001"),
            fixture.replace("2.25", "0"), fixture.replace("true", "1"),
            fixture.replace("\"version\":1", "\"version\":2"),
            fixture.replace("\"version\":1", "\"version\":true"),
            fixture.dropLast(1) + ",\"salary\":9}", fixture + "{}", "{}",
            fixture.replace("\"reduceMotion\":false,", ""), " ".repeat(4097) + fixture
        ).forEach { assertTrue(it, runCatching { ComfortTransferV2.decode(it) }.isFailure) }
    }
}
