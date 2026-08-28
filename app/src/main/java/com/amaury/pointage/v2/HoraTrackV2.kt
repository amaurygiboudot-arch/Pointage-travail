package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.*

/** Point d'entrée unique du moteur HoraTrack V2. */
object HoraTrackV2 {
    /** Activé uniquement pour la phase de test utilisateur. */
    const val ENABLED = true
    const val TEST_MODE = true
    const val SCHEMA_VERSION = 2

    enum class Layer { TIME, GPS, COMPANY_CONTRACT, LEGAL_AI, PAYROLL, COUNTERS_RIGHTS, PAYSLIP, BACKUP_RESTORE, SECURITY, PDF, HISTORY, ANALYTICS, DIAGNOSTICS }

    val time:TimeEngineV2 = DefaultTimeEngineV2
    val gps:GpsEngineV2 = GpsEngineV2()

    fun activeLayers():Set<Layer> = if(ENABLED) Layer.entries.toSet() else emptySet()

    fun assertTestIsolation() {
        check(TEST_MODE && ENABLED) { "Le mode de test V2 doit être explicitement actif" }
    }
}

data class V2ValidationReport(val passed:Boolean,val checks:List<Pair<String,Boolean>>,val failures:List<String>)
object V2ValidationSuite {
    fun run():V2ValidationReport {
        val checks=mutableListOf<Pair<String,Boolean>>()
        fun check(name:String,value:Boolean){checks += name to value}
        val slot=15L*60_000L
        val base=7L*60L*60L*1000L
        check("Entrée 07:00 -> 07:00",HoraTrackV2.time.countedEntryFromRealArrival(base)==base)
        check("Entrée 07:05 -> 07:00",HoraTrackV2.time.countedEntryFromRealArrival(base+5*60_000L)==base)
        check("Entrée 07:06 -> 07:15",HoraTrackV2.time.countedEntryFromRealArrival(base+6*60_000L)==base+slot)
        val expected=16L*60L*60L*1000L
        check("Sortie +20 -> prévue",HoraTrackV2.time.countedExitFromRealExit(expected+20*60_000L,expected)==expected)
        check("Sortie +21 -> réelle",HoraTrackV2.time.countedExitFromRealExit(expected+21*60_000L,expected)==expected+21*60_000L)
        check("Sortie sans horaire prévu -> réelle",HoraTrackV2.time.countedExitFromRealExit(expected+5*60_000L,null)==expected+5*60_000L)
        check("Toutes les couches déclarées",HoraTrackV2.Layer.entries.size==13)
        check("Mode test V2 actif",HoraTrackV2.TEST_MODE && HoraTrackV2.ENABLED)
        val failures=checks.filterNot{it.second}.map{it.first}
        return V2ValidationReport(failures.isEmpty(),checks,failures)
    }
}
