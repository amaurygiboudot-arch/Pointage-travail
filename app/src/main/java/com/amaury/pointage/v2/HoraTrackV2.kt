package com.amaury.pointage.v2

import com.amaury.pointage.BuildConfig
import com.amaury.pointage.v2.engine.CelestialEngineV2
import com.amaury.pointage.v2.engine.DefaultCelestialEngineV2
import com.amaury.pointage.v2.engine.DefaultTimeEngineV2
import com.amaury.pointage.v2.engine.GpsEngineV2
import com.amaury.pointage.v2.engine.TimeEngineV2
import java.util.Calendar

object HoraTrackV2 {
    const val ENABLED = true
    val TEST_MODE: Boolean
        get() = diagnosticsEnabled(BuildConfig.DEBUG)
    const val SCHEMA_VERSION = 2

    internal fun diagnosticsEnabled(debuggable: Boolean): Boolean = ENABLED && debuggable

    enum class Layer {
        TIME,
        GPS,
        CELESTIAL,
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
    val celestial: CelestialEngineV2 = DefaultCelestialEngineV2

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

        fun localTime(hour: Int): Long = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 4, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val base = localTime(7)
        val expectedEnd = localTime(16)

        addCheck(
            "Entrée réelle 07:00 conservée",
            HoraTrackV2.time.countedEntryFromRealArrival(base) == base
        )
        addCheck(
            "Entrée réelle 07:10 conservée sans arrondi",
            HoraTrackV2.time.countedEntryFromRealArrival(base + 10L * 60_000L) == base + 10L * 60_000L
        )
        addCheck(
            "Entrée réelle 07:11 conservée sans grille",
            HoraTrackV2.time.countedEntryFromRealArrival(base + 11L * 60_000L) == base + 11L * 60_000L
        )
        addCheck(
            "Sortie réelle +20 conservée malgré heure prévue",
            HoraTrackV2.time.countedExitFromRealExit(expectedEnd + 20L * 60_000L, expectedEnd) == expectedEnd + 20L * 60_000L
        )
        addCheck(
            "Sortie réelle +21 conservée",
            HoraTrackV2.time.countedExitFromRealExit(expectedEnd + 21L * 60_000L, expectedEnd) == expectedEnd + 21L * 60_000L
        )
        addCheck(
            "Sortie réelle sans horaire prévu conservée",
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
