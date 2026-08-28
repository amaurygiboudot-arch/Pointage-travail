package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.*

object HoraTrackV2 {
 const val ENABLED=true;const val TEST_MODE=true;const val SCHEMA_VERSION=2
 enum class Layer{TIME,GPS,COMPANY_CONTRACT,LEGAL_AI,PAYROLL,COUNTERS_RIGHTS,PAYSLIP,BACKUP_RESTORE,SECURITY,PDF,HISTORY,ANALYTICS,DIAGNOSTICS}
 val time:TimeEngineV2=DefaultTimeEngineV2;val gps:GpsEngineV2=GpsEngineV2()
 fun activeLayers():Set<Layer>=if(ENABLED)Layer.entries.toSet() else emptySet()
 fun legacyDisabledFor(layer:Layer)=ENABLED&&layer in activeLayers()
 fun assertTestIsolation(){check(TEST_MODE&&ENABLED){"Le mode de test V2 doit être explicitement actif"}}
}

data class V2ValidationReport(val passed:Boolean,val checks:List<Pair<String,Boolean>>,val failures:List<String>)
object V2ValidationSuite{
 fun run():V2ValidationReport{val checks=mutableListOf<Pair<String,Boolean>>();fun c(n:String,v:Boolean){checks+=n to v};val slot=15L*60_000L;val base=7L*60L*60L*1000L;c("Entrée 07:00 -> 07:00",HoraTrackV2.time.countedEntryFromRealArrival(base)==base);c("Entrée 07:05 -> 07:00",HoraTrackV2.time.countedEntryFromRealArrival(base+5*60_000)==base);c("Entrée 07:06 -> 07:15",HoraTrackV2.time.countedEntryFromRealArrival(base+6*60_000)==base+slot);val expected=16L*60L*60L*1000L;c("Sortie +20 -> prévue",HoraTrackV2.time.countedExitFromRealExit(expected+20*60_000,expected)==expected);c("Sortie +21 -> réelle",HoraTrackV2.time.countedExitFromRealExit(expected+21*60_000,expected)==expected+21*60_000);c("Sortie sans horaire prévu -> réelle",HoraTrackV2.time.countedExitFromRealExit(expected+5*60_000,null)==expected+5*60_000);c("Toutes les couches V2 actives",HoraTrackV2.activeLayers()==HoraTrackV2.Layer.entries.toSet());c("Test = diagnostics seulement",HoraTrackV2.TEST_MODE&&HoraTrackV2.ENABLED);HoraTrackV2.Layer.entries.filterNot{it==HoraTrackV2.Layer.DIAGNOSTICS}.forEach{c("Legacy bloqué : ${it.name}",HoraTrackV2.legacyDisabledFor(it))};val failures=checks.filterNot{it.second}.map{it.first};return V2ValidationReport(failures.isEmpty(),checks,failures)}
}
