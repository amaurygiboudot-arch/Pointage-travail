package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDriveActionsV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `V2 exposes one explicit backup action while legacy sync stays hidden`() {
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")
        val backup = source("app/src/main/java/com/amaury/pointage/V2BackupRestoreView.kt")

        assertTrue(
            settings.contains("if (configured && !HoraTrackV2.ENABLED) View.VISIBLE else View.GONE")
        )
        assertTrue(backup.contains("SAUVEGARDER MAINTENANT"))
        assertTrue(backup.contains("RESTAURER UNE SAUVEGARDE"))
    }
}
