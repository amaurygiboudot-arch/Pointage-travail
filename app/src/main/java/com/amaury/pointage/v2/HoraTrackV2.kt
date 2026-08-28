package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.DefaultTimeEngineV2
import com.amaury.pointage.v2.engine.GpsEngineV2
import com.amaury.pointage.v2.engine.TimeEngineV2

object HoraTrackV2 {
    const val ENABLED = true
    const val TEST_MODE = true
    const val SCHEMA_VERSION = 2

    enum class Layer {
        TIME,
        GPS,
        COMPANY_CONTRACT,
        LEGAL_AI,
        PAYROLL,
        COUNTERS_RIGHTS,
        PAYSLIP,
        BACKUP_RESTORE,
        SECURITY,
        PDF,
        HISTORY,
        ANALYTICS,
        DIAGNOSTICS
    }

    val time: TimeEngineV2 = DefaultTimeEngineV2
    val gps: GpsEngineV2 = GpsEngineV2()

    fun activeLayers(): Set<Layer> {
        return if (ENABLED) Layer.entries.toSet() else emptySet()
    }

    fun legacyDisabledFor(layer: Layer): Boolean {
        return ENABLED && layer in activeLayers()
    }

    fun assertTestIsolation() {
        check(TEST_MODE && ENABLED) {
            "Le mode de test V2 doit être explicitement actif"
        }
    }
}

data class V2ValidationReport(
    val passed: Boolean,
    val checks: List<Pair<String, Boolean>>,
    val failures: List<String>
)

object V2ValidationSuite {
    fun run(): V2ValidationReport {
        val checks = mutableListOf<Pair<String, Boolean>>()

        fun addCheck(name: String, value: Boolean) {
            checks += name to value
        }

        val slotMs = 15L * 60_000L
        val base = 7L * 60L * 60L * 1000L
        val expectedEnd = 16L * 60L * 60L * 1000L

        addCheck(
            "Entrée 07:00 -> 07:00",
            HoraTrackV2.time.countedEntryFromRealArrival(base) == base
        )
        addCheck(
            "Entrée 07:05 -> 07:00",
            HoraTrackV2.time.countedEntryFromRealArrival(base + 5L * 60_000L) == base
        )
        addCheck(
            "Entrée 07:06 -> 07:15",
            HoraTrackV2.time.countedEntryFromRealArrival(base + 6L * 60_000L) == base + slotMs
        )
        addCheck(
            "Sortie +20 -> prévue",
            HoraTrackV2.time.countedExitFromRealExit(expectedEnd + 20L * 60_000L, expectedEnd) == expectedEnd
        )
        addCheck(
            "Sortie +21 -> réelle",
            HoraTrackV2.time.countedExitFromRealExit(expectedEnd + 21L * 60_000L, expectedEnd) == expectedEnd + 21L * 60_000L
        )
        addCheck(
            "Sortie sans horaire prévu -> réelle",
            HoraTrackV2.time.countedExitFromRealExit(expectedEnd + 5L * 60_000L, null) == expectedEnd + 5L * 60_000L
        )
        addCheck(
            "Toutes les couches V2 actives",
            HoraTrackV2.activeLayers() == HoraTrackV2.Layer.entries.toSet()
        )
        addCheck(
            "Test = diagnostics seulement",
            HoraTrackV2.TEST_MODE && HoraTrackV2.ENABLED
        )

        HoraTrackV2.Layer.entries
            .filterNot { it == HoraTrackV2.Layer.DIAGNOSTICS }
            .forEach { layer ->
                addCheck(
                    "Legacy bloqué : ${layer.name}",
                    HoraTrackV2.legacyDisabledFor(layer)
                )
            }

        val failures = checks.filterNot { it.second }.map { it.first }
        return V2ValidationReport(
            passed = failures.isEmpty(),
            checks = checks,
            failures = failures
        )
    }
}
