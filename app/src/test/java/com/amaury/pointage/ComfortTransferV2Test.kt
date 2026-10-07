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
        val native = PersonalizationProfileV2(1.75f, false, true, 1f, "economy", false, true, 60, 180)
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
    @Test fun `v2 fixture matches the shared Android iOS wire contract`() {
        val fixtureV2 = """{"format":"agkgmg.comfort","version":2,"highContrast":true,"reduceMotion":false,"readerScale":2.25,"nightScheduleEnabled":true,"nightStartMinute":1320,"nightEndMinute":420}"""
        val expected = ComfortTransferV2(true, false, 2.25f, ComfortTransferV2.NightSchedule(true, 1320, 420))
        assertEquals(expected, ComfortTransferV2.decode(fixtureV2))
        assertEquals(expected, ComfortTransferV2.decode(expected.encode()))
    }
    @Test fun `new exports use v2 and preserve enabled and disabled night schedules`() {
        for (enabled in listOf(true, false)) {
            val source = PersonalizationProfileV2(highContrast = true, readerScale = 2.25f,
                nightScheduleEnabled = enabled, nightStartMinute = 1439, nightEndMinute = 0)
            val exported = ComfortTransferV2.from(source)
            val json = org.json.JSONObject(exported.encode())
            assertEquals(2, json.getInt("version"))
            assertEquals(enabled, json.getBoolean("nightScheduleEnabled"))
            assertEquals(1439, json.getInt("nightStartMinute"))
            assertEquals(0, json.getInt("nightEndMinute"))
            assertEquals(exported, ComfortTransferV2.decode(exported.encode()))
            val local = PersonalizationProfileV2(textScale = 1.75f, context = "work", writingAssistance = false,
                nightScheduleEnabled = !enabled, nightStartMinute = 600, nightEndMinute = 800)
            assertEquals(local.copy(highContrast = true, readerScale = 2.25f, nightScheduleEnabled = enabled,
                nightStartMinute = 1439, nightEndMinute = 0), exported.applyTo(local))
        }
    }
    @Test fun `v1 reexport stays v1 and preview discloses preservation`() {
        val legacy = ComfortTransferV2.decode(fixture)
        assertEquals(1, org.json.JSONObject(legacy.encode()).getInt("version"))
        assertNull(legacy.nightSchedule)
        assertTrue(legacy.preview().contains("seront conservés"))
        val modern = ComfortTransferV2.from(PersonalizationProfileV2(nightStartMinute = 65, nightEndMinute = 601))
        assertTrue(modern.preview().contains("désactivée, 01:05–10:01"))
        assertTrue(modern.preview().contains("fuseau horaire local"))
    }
    @Test fun `v2 requires all schedule fields and rejects invalid types ranges and equal times`() {
        val valid = ComfortTransferV2.from(PersonalizationProfileV2()).encode()
        val invalid = mutableListOf<String>()
        for (key in listOf("nightScheduleEnabled", "nightStartMinute", "nightEndMinute")) {
            invalid.add(org.json.JSONObject(valid).apply { remove(key) }.toString())
            invalid.add(org.json.JSONObject(valid).put(key, org.json.JSONObject.NULL).toString())
        }
        for (key in listOf("nightStartMinute", "nightEndMinute")) {
            for (value in listOf(-1, 1440, 1.5, true, "60", 4294967296L)) {
                invalid.add(org.json.JSONObject(valid).put(key, value).toString())
            }
        }
        for (value in listOf(1, "true")) invalid.add(org.json.JSONObject(valid).put("nightScheduleEnabled", value).toString())
        invalid.add(org.json.JSONObject(valid).put("nightEndMinute", 1320).toString())
        invalid.add(org.json.JSONObject(valid).put("version", 1).toString())
        invalid.add(org.json.JSONObject(valid).put("version", 2.5).toString())
        invalid.add(org.json.JSONObject(valid).put("version", 3).toString())
        invalid.add(org.json.JSONObject(valid).put("salary", 9).toString())
        invalid.forEach { assertTrue(it, runCatching { ComfortTransferV2.decode(it) }.isFailure) }
    }

}
