package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsOrphanGpsSaveClassV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/AddressUiButtons.kt").isFile }

    @Test
    fun `orphan legacy GPS save class stays removed`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/AddressUiButtons.kt").readText()
        assertFalse(source.contains("class SafeGpsSaveButton"))
    }
}
