package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionOrderContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt").isFile }

    private fun priority(source: String, tag: String): Int {
        val match = Regex("""$tag -> (\d+)""").find(source)
            ?: error("Priority not found for $tag")
        return match.groupValues[1].toInt()
    }

    @Test
    fun `application section sorts before the other public settings sections`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt").readText()

        val application = priority(source, "TAG_UPDATES")
        val account = priority(source, "TAG_ACCOUNT_SECURITY")
        val pointage = priority(source, "TAG_POINTAGE")

        assertTrue(application < account)
        assertTrue(account < pointage)
    }
}
