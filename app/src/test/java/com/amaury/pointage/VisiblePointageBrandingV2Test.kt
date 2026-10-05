package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePointageBrandingV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/MainActivity.kt").isFile }

    private val forbidden = mapOf(
        "PauseActionActivity.kt" to listOf("données HoraTrack", "état HoraTrack"),
        "v2/ui/V2Diagnostics.kt" to listOf("HoraTrack — diagnostic développeur", "Erreur interne HoraTrack", "Diagnostic HoraTrack", "Bouton/action HoraTrack"),
        "v2/ui/V2TestUiInstaller.kt" to listOf("🧪 HORATRACK — MODE TEST ACTIF", "Diagnostic HoraTrack :", "\"HoraTrack :"),
        "UpdateVerificationWorker.kt" to listOf("Mise à jour HoraTrack prête", "Mises à jour HoraTrack"),
        "v2/ui/V2LegacyIsolationUi.kt" to listOf("Historique HoraTrack indisponible", "Aucune session HoraTrack", "🧪 HoraTrack", "Analyse HoraTrack", "Aucune donnée HoraTrack", "HORATRACK\\n\\n"),
        "QuickActionsWidgetProvider.kt" to listOf("données HoraTrack", "Ouvre HoraTrack"),
        "v2/ui/V2GpsPromptController.kt" to listOf("HoraTrack te pose", "HoraTrack a détecté"),
        "HistorySearchFilterView.kt" to listOf("Historique HoraTrack indisponible"),
        "PointageWidgetProvider.kt" to listOf("Données HoraTrack", "données HoraTrack", "Ouvre HoraTrack"),
        "MainActivity.kt" to listOf("données HoraTrack", "HoraTrack_\$monthFile.pdf", "Historique HoraTrack indisponible", "lorsque HoraTrack est actif"),
        "SmartSetupManager.kt" to listOf("HoraTrack ne l\'activera jamais"),
        "PauseManagerButtonV2.kt" to listOf("données HoraTrack"),
        "v2/V2RuntimeReader.kt" to listOf("Historique HoraTrack V2 non fiable"),
        "CrashRecoveryManager.kt" to listOf("HoraTrack — rapport de crash"),
        "DailyPdfReport.kt" to listOf("RAPPORT JOURNALIER HORATRACK"),
        "MonthlyPdfReport.kt" to listOf("version de HoraTrack")
    )

    @Test
    fun `active pointage and diagnostics use the current visible product name`() {
        forbidden.forEach { (relative, oldPhrases) ->
            val source = File(root, "app/src/main/java/com/amaury/pointage/$relative").readText()
            oldPhrases.forEach { old ->
                assertFalse("$relative still exposes $old", source.contains(old))
            }
            assertTrue("$relative must expose AGKGMG after branding migration", source.contains("AGKGMG"))
        }
    }
}
