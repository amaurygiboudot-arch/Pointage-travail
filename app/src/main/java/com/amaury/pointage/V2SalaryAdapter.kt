package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ConventionRuleStore
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PayrollEngineV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.engine.PayrollWeekV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Passerelle unique entre les écrans Salaire et PayrollEngineV2. */
object V2SalaryAdapter {
 data class TierDuration(val label:String,val durationMs:Long,val multiplier:Double)
 data class Result(val regularMs:Long,val overtimeTiers:List<TierDuration>,val totalWorkedMs:Long,val regularGross:Double,val overtimeGross:Double,val premiumsGross:Double,val monthlyEstimatedGross:Double,val nightMs:Long,val saturdayMs:Long,val sundayMs:Long,val completedSessions:Int,val warnings:List<String>)

 fun calculateForCompany(context:Context,company:SalaryCompanyStore.Company,year:Int,month:Int,convention:ConventionCatalog.Convention,ruleHistory:ConventionRuleHistoryV2?=null):Result {
  require(HoraTrackV2.ENABLED)
  val prefs=SalaryCompanyStore.prefs(context,company.id)
  val type=when(prefs.getString("contract_type","").orEmpty().uppercase(Locale.ROOT)){"FULL_TIME"->ContractTypeV2.FULL_TIME;"PART_TIME"->ContractTypeV2.PART_TIME;"FORFAIT"->ContractTypeV2.FORFAIT;"OTHER"->ContractTypeV2.OTHER;else->null}
  val weekly=prefs.getString("contract_weekly_hours","").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}?.let{(it*60).roundToInt()}
  val rate=prefs.getString("hourly_rate","").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}
  val hire=runCatching{prefs.getString("entry_date","").orEmpty().trim().takeIf{it.isNotBlank()}?.let{LocalDate.parse(it,DateTimeFormatter.ofPattern("dd/MM/yyyy",Locale.FRANCE)).toEpochDay()}}.getOrNull()
  val missing=mutableListOf<String>();if(type==null)missing+="type de contrat";if(weekly==null&&type!=ContractTypeV2.FORFAIT)missing+="durée hebdomadaire";if(rate==null)missing+="taux horaire"
  val contract=if(type!=null&&rate!=null&&(weekly!=null||type==ContractTypeV2.FORFAIT)) ContractV2("contract_${company.id}",company.id,type,weekly,rate,hire) else null
  return calculateCore(contract,missing,V2RuntimeStore.allSessions(context),year,month,rate?:0.0,convention,ruleHistory?:V2ConventionRuleStore.history(context),SalaryCompanyStore.acceptedEmployerIds(context,company.id))
 }

 fun calculate(context:Context,year:Int,month:Int,hourlyRate:Double,convention:ConventionCatalog.Convention,companySlot:Int=1,ruleHistory:ConventionRuleHistoryV2?=null):Result {val p=V2ProfileStore.load(context,companySlot.coerceIn(1,2));return calculateCore(p.contract,p.missing,V2RuntimeStore.allSessions(context),year,month,hourlyRate,convention,ruleHistory?:V2ConventionRuleStore.history(context),p.contract?.let{setOf(it.employerId)}.orEmpty())}
 fun calculateBound(year:Int,month:Int,hourlyRate:Double,convention:ConventionCatalog.Convention,companySlot:Int=1,ruleHistory:ConventionRuleHistoryV2?=null):Result {val p=V2ProfileStore.loadBound(companySlot.coerceIn(1,2));return calculateCore(p?.contract,p?.missing.orEmpty(),V2RuntimeStore.allSessionsBound(),year,month,hourlyRate,convention,ruleHistory,p?.contract?.let{setOf(it.employerId)}.orEmpty())}

 private fun calculateCore(contract:ContractV2?,missing:List<String>,sessions:List<WorkSessionV2>,year:Int,month:Int,fallbackRate:Double,convention:ConventionCatalog.Convention,ruleHistory:ConventionRuleHistoryV2?,acceptedEmployerIds:Set<String>):Result {
  val rate=contract?.grossHourlyRate?:fallbackRate.takeIf{it>0}?:return empty(missing.map{"Fiche Salaire à compléter : $it"});if(contract==null)return empty(missing.map{"Fiche Salaire à compléter : $it"})
  val ids=acceptedEmployerIds.ifEmpty{setOf(contract.employerId)};val selected=sessions.filter{s->val end=s.realExitMs?:return@filter false;val start=s.countedEntryMs?:s.realArrivalMs?:return@filter false;val c=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=start};s.employerId in ids&&end>start&&c.get(Calendar.YEAR)==year&&c.get(Calendar.MONTH)==month}
  val warnings=mutableListOf<String>();val date=LocalDate.of(year,month+1,1);val snap=ruleHistory?.applicable(convention.idcc,date.toEpochDay());val historical=ruleHistory?.allVersions(convention.idcc)?.isNotEmpty()==true;val hr=snap?.rules
  val tiers=when{historical&&hr!=null->hr.overtimeTiers.map{ConventionCatalog.OvertimeTier(it.fromMinutes/60.0,it.toMinutes?.div(60.0),it.multiplier)};historical->emptyList();convention.rulesIntegrated->convention.overtimeTiers;else->emptyList()};if(historical&&snap==null)warnings+="Règles conventionnelles historiques : À confirmer pour cette période" else if(!historical&&!convention.rulesIntegrated)warnings+="Paliers d'heures supplémentaires non confirmés pour cette convention"
  val regularLimit=hr?.weeklyRegularMinutes?:contract.contractualWeeklyMinutes?:tiers.firstOrNull()?.fromHour?.times(60)?.roundToInt()?:return empty(warnings+"Durée hebdomadaire de référence absente")
  data class W(var paid:Int=0,var night:Int=0,var sat:Int=0,var sun:Int=0);val weeks=linkedMapOf<Pair<Int,Int>,W>();val nightRule=if(historical)null else ConventionNightRules.forIdcc(convention.idcc);var nightMs=0L;var satMs=0L;var sunMs=0L
  selected.forEach{s->val start=s.countedEntryMs?:s.realArrivalMs?:return@forEach;val end=s.countedExitMs?:s.realExitMs?:return@forEach;if(end<=start)return@forEach;val paid=HoraTrackV2.time.calculate(s).paidWorkMs;val mins=(paid/60000).toInt();val c=Calendar.getInstance(Locale.FRANCE).apply{firstDayOfWeek=Calendar.MONDAY;minimalDaysInFirstWeek=4;timeInMillis=start};val w=weeks.getOrPut(c.getWeekYear() to c.get(Calendar.WEEK_OF_YEAR)){W()};w.paid+=mins;nightRule?.let{r->val n=((nightOverlap(start,end,r.startMinute,r.endMinute).toDouble()/(end-start))*paid).toLong().coerceIn(0,paid);w.night+=(n/60000).toInt();nightMs+=n};when(c.get(Calendar.DAY_OF_WEEK)){Calendar.SATURDAY->{w.sat+=mins;satMs+=paid};Calendar.SUNDAY->{w.sun+=mins;sunMs+=paid}}}
  val rules=hr?.copy(weeklyRegularMinutes=hr.weeklyRegularMinutes?:regularLimit)?:PayrollRulesV2(weeklyRegularMinutes=regularLimit,overtimeTiers=tiers.map{OvertimeTierV2((it.fromHour*60).roundToInt(),it.toHour?.let{x->(x*60).roundToInt()},it.multiplier)},nightMultiplier=nightRule?.premiumMultiplier);val worked=PayrollEngineV2.calculate(contract.copy(grossHourlyRate=rate),weeks.values.map{PayrollWeekV2(it.paid,it.night,it.sat,it.sun)},rules)
  val monthlyMinutes=contract.contractualWeeklyMinutes?.let{it*52.0/12.0};val base=when(contract.type){ContractTypeV2.FULL_TIME,ContractTypeV2.PART_TIME->monthlyMinutes?.div(60.0)?.times(rate);else->null};val gross=base?.plus(worked.overtimeGross+worked.premiumsGross)?:worked.grossEstimate
  if(base!=null)warnings+="Salaire de base mensualisé : ${String.format(Locale.FRANCE,"%.2f",monthlyMinutes!!/60.0)} h × ${String.format(Locale.FRANCE,"%.2f",rate)} € ; pointages utilisés pour les éléments variables.";if(selected.isEmpty())warnings+="Aucune session pointée : base mensualisée conservée ; les absences non rémunérées ne sont pas encore déduites automatiquement."
  var regular=0;val tm=LongArray(rules.overtimeTiers.size);weeks.values.forEach{w->regular+=minOf(w.paid,regularLimit);rules.overtimeTiers.forEachIndexed{i,t->tm[i]+=(minOf(w.paid,t.toMinutes?:Int.MAX_VALUE)-maxOf(regularLimit,t.fromMinutes)).coerceAtLeast(0)}}
  return Result((monthlyMinutes?.times(60000)?.toLong()?:regular.toLong()*60000),rules.overtimeTiers.mapIndexed{i,t->TierDuration("Heures sup. +${((t.multiplier-1)*100).roundToInt()} %",tm[i]*60000,t.multiplier)},weeks.values.sumOf{it.paid}.toLong()*60000,base?:worked.regularGross,worked.overtimeGross,worked.premiumsGross,gross,nightMs,satMs,sunMs,selected.size,warnings+worked.traces+listOfNotNull(snap?.let{"Règles historiques ${it.versionId} — source ${it.sourceId}"}))
 }
 private fun empty(w:List<String> = emptyList())=Result(0,emptyList(),0,0.0,0.0,0.0,0.0,0,0,0,0,w)
 private fun nightOverlap(entry:Long,exit:Long,startMinute:Int,endMinute:Int):Long{if(exit<=entry)return 0;var total=0L;val day=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=entry;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DAY_OF_YEAR,-1)};val last=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=exit;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DAY_OF_YEAR,1)};while(day.timeInMillis<=last.timeInMillis){val s=(day.clone() as Calendar).apply{set(Calendar.HOUR_OF_DAY,startMinute/60);set(Calendar.MINUTE,startMinute%60)};val e=(day.clone() as Calendar).apply{set(Calendar.HOUR_OF_DAY,endMinute/60);set(Calendar.MINUTE,endMinute%60);if(endMinute<=startMinute)add(Calendar.DAY_OF_YEAR,1)};total+=overlap(entry,exit,s.timeInMillis,e.timeInMillis);day.add(Calendar.DAY_OF_YEAR,1)};return total}
 private fun overlap(a:Long,b:Long,c:Long,d:Long)=(minOf(b,d)-maxOf(a,c)).coerceAtLeast(0)
}
