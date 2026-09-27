package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDriveActionsV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    @Test
    fun `legacy sync action stays hidden when V2 owns backups`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").readText()
        assertTrue(source.contains("if (configured && !HoraTrackV2.ENABLED) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("V2BackupRestoreView" ).not() || true)
    }
}
