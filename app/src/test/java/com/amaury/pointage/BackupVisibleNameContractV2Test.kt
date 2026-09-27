package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupVisibleNameContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/v2/V2BackupManager.kt").isFile }

    @Test
    fun `new backups use AGKGMG names while legacy names stay readable`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/v2/V2BackupManager.kt").readText()
        assertTrue(source.contains("ROOT_FOLDER = \"AGKGMG\""))
        assertTrue(source.contains("FILE_NAME = \"AGKGMG_backup.json\""))
        assertTrue(source.contains("\"Pointage Travail\""))
        assertTrue(source.contains("\"HoraTrack_backup.json\""))
        assertTrue(source.contains("\"HoraTrack_V2_backup.json\""))
    }
}
