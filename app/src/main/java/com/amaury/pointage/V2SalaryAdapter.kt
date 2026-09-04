package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ConventionRuleStore
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.CompanyAgreementOvertimeOverlayV2
import com.amaury.pointage.v2.engine.CompanyAgreementPayrollBridgeV2
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.engine.FullTimeStructuralOvertimeV2
import com.amaury.pointage.v2.engine.MonthlySalaryProrationV2
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PaidWorkAllocationV2
import com.amaury.pointage.v2.engine.PartTimeComplementaryHoursV2
import com.amaury.pointage.v2.engine.PayrollEngineV2
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.engine.PayrollWeekV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Passerelle unique entre les écrans Salaire et PayrollEngineV2. */
object V2SalaryAdapter {
 data class TierDuration(val label:String,val durationMs:Long,val multiplier:Double)
 data class Result(val regularMs:Long,val overtimeTiers:List<TierDuration>,val totalWorkedMs:Long,val regularGross:Double,val overtimeGross:Double,val premiumsGross:Double,val monthlyEstimatedGross:Double,val monthlyGrossReliable:Boolean,val nightMs:Long,val saturdayMs:Long,val sundayMs:Long,val complementaryMinutes:Int,val completedSessions:Int,val warnings:List<String>)

 fun calculateForCompany(context:Context,company:SalaryCompanyStore.Company,year:Int,month:Int,convention:ConventionCatalog.Convention,ruleHistory:ConventionRuleHistoryV2?=null):Result {
  require(HoraTrackV2.ENABLED)
  val prefs=SalaryCompanyStore.prefs(context,company.id)
  val rawType=prefs.getString("contract_type","").orEmpty().uppercase(Locale.ROOT)
  val type=when(rawType){"FULL_TIME"->ContractTypeV2.FULL_TIME;"PART_TIME"->ContractTypeV2.PART_TIME;"FORFAIT_HEURES"->ContractTypeV2.FORFAIT_HOURS;"FORFAIT_JOURS"->ContractTypeV2.FORFAIT_DAYS;"FORFAIT"->ContractTypeV2.FORFAIT;"OTHER"->ContractTypeV2.OTHER;else->null}
  val weekly=prefs.getString("contract_weekly_hours","").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}?.let{(it*60).roundToInt()}
  val rate=prefs.getString("hourly_rate","").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}
  val forfaitHours=prefs.getString("forfait_annual_hours","").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}
  val forfaitDays=prefs.getString("forfait_annual_days","").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}
  val monthlyGross=prefs.getString("monthly_gross_salary","").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}
  val hire=runCatching{prefs.getString("entry_date","").orEmpty().trim().takeIf{it.isNotBlank()}?.let{LocalDate.parse(it,DateTimeFormatter.ofPattern("dd/MM/yyyy",Locale.FRANCE)).toEpochDay()}}.getOrNull()
  val missing=mutableListOf<String>()
  if(type==null)missing+="type de contrat"
  when(type){
   ContractTypeV2.FULL_TIME,ContractTypeV2.PART_TIME,ContractTypeV2.OTHER->{if(weekly==null)missing+="durée hebdomadaire";if(rate==null)missing+="taux horaire"}
   ContractTypeV2.FORFAIT_HOURS->{if(forfaitHours==null)missing+="heures du forfait annuel";if(monthlyGross==null)missing+="salaire brut mensuel convenu"}
   ContractTypeV2.FORFAIT_DAYS->{if(forfaitDays==null)missing+="jours du forfait annuel";if(monthlyGross==null)missing+="salaire brut mensuel convenu"}
   ContractTypeV2.FORFAIT->missing+="type de forfait à préciser"
   null->Unit
  }
  val complete=type!=null&&missing.isEmpty()
  val contract=if(complete) ContractV2(
   id="contract_${company.id}",
   employerId=company.id,
   type=type!!,
   contractualWeeklyMinutes=weekly,
   grossHourlyRate=rate,
   hireDateEpochDay=hire,
   forfaitHoursPeriod=if(type==ContractTypeV2.FORFAIT_HOURS)ForfaitHoursPeriodV2.YEAR else null,
   forfaitHours=if(type==ContractTypeV2.FORFAIT_HOURS)forfaitHours else null,
   forfaitAnnualDays=if(type==ContractTypeV2.FORFAIT_DAYS)forfaitDays else null,
   monthlyGrossSalary=if(type==ContractTypeV2.FORFAIT_HOURS||type==ContractTypeV2.FORFAIT_DAYS)monthlyGross else null
  ) else null
  val period=PayrollPeriodV2.month(year,month)
  val acceptedIds=SalaryCompanyStore.acceptedEmployerIds(context,company.id)
  val absenceImpact=AbsencePayrollImpactV2.forMonth(
   V2RightsStore.absences(context),
   period.referenceDate,
   acceptedIds
  )
  val companyAgreement=CompanyAgreementPayrollBridgeV2.load(context,company.id,period.referenceDate,period)
  return calculateCore(
   contract,missing,V2RuntimeStore.allSessions(context),year,month,rate?:0.0,convention,
   ruleHistory?:V2ConventionRuleStore.history(context),acceptedIds,companyAgreement,absenceImpact
  )
 }

 fun calculate(context:Context,year:Int,month:Int,hourlyRate:Double,convention:ConventionCatalog.Convention,companySlot:Int=1,ruleHistory:ConventionRuleHistoryV2?=null):Result {
  val p=V2ProfileStore.load(context,companySlot.coerceIn(1,2))
  val ids=p.contract?.let{setOf(it.employerId)}.orEmpty()
  val referenceDate=LocalDate.of(year,month+1,1).let{it.withDayOfMonth(it.lengthOfMonth())}
  val absenceImpact=AbsencePayrollImpactV2.forMonth(V2RightsStore.absences(context),referenceDate,ids)
  return calculateCore(p.contract,p.missing,V2RuntimeStore.allSessions(context),year,month,hourlyRate,convention,ruleHistory?:V2ConventionRuleStore.history(context),ids,null,absenceImpact)
 }
 fun calculateBound(year:Int,month:Int,hourlyRate:Double,convention:ConventionCatalog.Convention,companySlot:Int=1,ruleHistory:ConventionRuleHistoryV2?=null):Result {val p=V2ProfileStore.loadBound(companySlot.coerceIn(1,2));return calculateCore(p?.contract,p?.missing.orEmpty(),V2RuntimeStore.allSessionsBound(),year,month,hourlyRate,convention,ruleHistory,p?.contract?.let{setOf(it.employerId)}.orEmpty())}

 private fun calculateCore(contract:ContractV2?,missing:List<String>,sessions:List<WorkSessionV2>,year:Int,month:Int,fallbackRate:Double,convention:ConventionCatalog.Convention,ruleHistory:ConventionRuleHistoryV2?,acceptedEmployerIds:Set<String>,companyAgreementSnapshot:CompanyAgreementPayrollBridgeV2.Snapshot?=null,absenceImpact:AbsencePayrollImpactV2.Snapshot?=null):Result {
  if(contract==null)return empty(missing.map{"Fiche Salaire à compléter : $it"})
  val ids=acceptedEmployerIds.ifEmpty{setOf(contract.employerId)}
  val monthStart=Calendar.getInstance(Locale.FRANCE).apply{clear();set(year,month,1,0,0,0)}.timeInMillis
  val monthEnd=Calendar.getInstance(Locale.FRANCE).apply{clear();set(year,month,1,0,0,0);add(Calendar.MONTH,1)}.timeInMillis
  val selected=sessions.filter{s->val start=s.countedEntryMs?:return@filter false;val end=s.countedExitMs?:return@filter false;s.employerId in ids&&s.realExitMs!=null&&end>start&&start<monthEnd&&end>monthStart}
  val warnings=mutableListOf<String>()
  val referenceDate=LocalDate.of(year,month+1,1).let{it.withDayOfMonth(it.lengthOfMonth())}
  val entryDate=contract.hireDateEpochDay?.let(LocalDate::ofEpochDay)
  val grossAssessment=MonthlySalaryProrationV2.assess(entryDate,referenceDate)
  grossAssessment.warning?.let(warnings::add)
  absenceImpact?.warnings?.let(warnings::addAll)
  if(absenceImpact?.hasUnpaidAbsence==true){
   warnings+="Absence non rémunérée enregistrée : le brut exact exige les heures de travail prévues dans l'entreprise pour ce mois. Aucun montant de retenue n'est inventé."
  }
  val monthlyGrossReliable=grossAssessment.exactMonthlyGrossAvailable&&absenceImpact?.hasUnpaidAbsence!=true
  data class W(var paid:Int=0,var night:Int=0,var sat:Int=0,var sun:Int=0)
  val weeks=linkedMapOf<Pair<Int,Int>,W>()
  val historical=ruleHistory?.allVersions(convention.idcc)?.isNotEmpty()==true
  val nightRule=if(historical)null else ConventionNightRules.forIdcc(convention.idcc)
  var nightMs=0L;var satMs=0L;var sunMs=0L
  selected.forEach{s->
   PaidWorkAllocationV2.splitByIsoWeek(s,monthStart,monthEnd).forEach{slice->
    val paidMinutes=(slice.paidMs/60000L).toInt()
    val w=weeks.getOrPut(slice.weekYear to slice.weekOfYear){W()}
    w.paid+=paidMinutes
    nightRule?.let{r->val n=nightPaidOverlap(s,slice.startMs,slice.endMs,r.startMinute,r.endMinute);w.night+=(n/60000L).toInt();nightMs+=n}
    val sat=dayPaidOverlap(s,slice.startMs,slice.endMs,Calendar.SATURDAY)
    val sun=dayPaidOverlap(s,slice.startMs,slice.endMs,Calendar.SUNDAY)
    w.sat+=(sat/60000L).toInt();w.sun+=(sun/60000L).toInt();satMs+=sat;sunMs+=sun
   }
  }

  if(contract.type==ContractTypeV2.FORFAIT_HOURS||contract.type==ContractTypeV2.FORFAIT_DAYS){
   val worked=PayrollEngineV2.calculate(contract,weeks.values.map{PayrollWeekV2(it.paid,it.night,it.sat,it.sun)},PayrollRulesV2())
   val contractualMonthlyMinutes=when(contract.type){
    ContractTypeV2.FORFAIT_HOURS->when(contract.forfaitHoursPeriod){ForfaitHoursPeriodV2.WEEK->contract.forfaitHours?.times(52.0/12.0)?.times(60.0);ForfaitHoursPeriodV2.MONTH->contract.forfaitHours?.times(60.0);ForfaitHoursPeriodV2.YEAR->contract.forfaitHours?.div(12.0)?.times(60.0);null->null}
    else->null
   }
   warnings+=when(contract.type){
    ContractTypeV2.FORFAIT_HOURS->"Forfait heures : la rémunération mensuelle convenue est conservée ; les pointages ne recréent pas artificiellement des heures supplémentaires déjà comprises dans le forfait."
    ContractTypeV2.FORFAIT_DAYS->"Forfait jours : la rémunération n'est pas convertie en taux horaire ; les pointages servent au suivi du temps et de la charge."
    else->""
   }
   if(selected.isEmpty())warnings+="Aucune session pointée sur la période : la rémunération forfaitaire contractuelle reste une base théorique ; les absences enregistrées sont contrôlées séparément."
   return Result(
    regularMs=contractualMonthlyMinutes?.toLong()?.times(60000L)?:0L,
    overtimeTiers=emptyList(),
    totalWorkedMs=weeks.values.sumOf{it.paid}.toLong()*60000L,
    regularGross=worked.regularGross,
    overtimeGross=0.0,
    premiumsGross=worked.premiumsGross+worked.fixedPremiumsGross,
    monthlyEstimatedGross=worked.grossEstimate,
    monthlyGrossReliable=monthlyGrossReliable,
    nightMs=nightMs,
    saturdayMs=satMs,
    sundayMs=sunMs,
    complementaryMinutes=0,
    completedSessions=selected.size,
    warnings=warnings+worked.traces
   )
  }

  val rate=contract.grossHourlyRate?:fallbackRate.takeIf{it>0}?:return empty(missing.map{"Fiche Salaire à compléter : $it"})
  val date=LocalDate.of(year,month+1,1);val snap=ruleHistory?.applicable(convention.idcc,date.toEpochDay());val hr=snap?.rules
  val isPartTime=contract.type==ContractTypeV2.PART_TIME
  val isFullTime=contract.type==ContractTypeV2.FULL_TIME
  val tiers=if(isPartTime) emptyList() else when{historical&&hr!=null->hr.overtimeTiers.map{ConventionCatalog.OvertimeTier(it.fromMinutes/60.0,it.toMinutes?.div(60.0),it.multiplier)};historical->emptyList();convention.rulesIntegrated->convention.overtimeTiers;else->emptyList()}
  if(!isPartTime){if(historical&&hr==null)warnings+="Règles conventionnelles historiques : À confirmer pour cette période" else if(!historical&&!convention.rulesIntegrated)warnings+="Barème conventionnel d'heures supplémentaires non intégré : HoraTrack conserve au minimum la majoration légale de 10 % sur les heures non couvertes."}
  val regularLimit=when{isPartTime->contract.contractualWeeklyMinutes;isFullTime->hr?.weeklyRegularMinutes?:35*60;else->hr?.weeklyRegularMinutes?:contract.contractualWeeklyMinutes?:tiers.firstOrNull()?.fromHour?.times(60)?.roundToInt()}
  if(regularLimit==null)return empty(warnings+"Durée hebdomadaire de référence absente")
  val baseRules=hr?.copy(weeklyRegularMinutes=regularLimit)?:PayrollRulesV2(weeklyRegularMinutes=regularLimit,overtimeTiers=tiers.map{OvertimeTierV2((it.fromHour*60).roundToInt(),it.toHour?.let{x->(x*60).roundToInt()},it.multiplier)},nightMultiplier=nightRule?.premiumMultiplier)
  val agreementOverlay=if(isFullTime&&companyAgreementSnapshot!=null)CompanyAgreementOvertimeOverlayV2.fromSnapshot(baseRules.overtimeTiers,companyAgreementSnapshot)else null
  agreementOverlay?.warnings?.let(warnings::addAll)
  val effectiveRules=if(agreementOverlay!=null)baseRules.copy(overtimeTiers=agreementOverlay.tiers)else baseRules
  val payrollRules=if(isPartTime||isFullTime)effectiveRules.copy(overtimeTiers=emptyList()) else effectiveRules
  val worked=PayrollEngineV2.calculate(contract.copy(grossHourlyRate=rate),weeks.values.map{PayrollWeekV2(it.paid,it.night,it.sat,it.sun)},payrollRules)

  val complementary=if(isPartTime)weeks.values.map{PartTimeComplementaryHoursV2.calculateWeek(regularLimit,it.paid,rate)}else emptyList()
  val complementaryMinutes=complementary.sumOf{it.complementaryMinutes}
  val complementaryGross=complementary.sumOf{it.grossToAdd}
  warnings+=complementary.flatMap{it.warnings}.distinct()
  if(isPartTime)warnings+="Temps partiel : barème supplétif des heures complémentaires appliqué (+10 % puis +25 %) tant qu'aucune stipulation conventionnelle structurée plus précise n'est intégrée."

  val fullTime=if(isFullTime){
   val contractual=contract.contractualWeeklyMinutes?:regularLimit
   FullTimeStructuralOvertimeV2.calculate(
    contractualWeeklyMinutes=contractual,
    regularWeeklyLimit=regularLimit,
    paidWeeks=weeks.values.map{it.paid},
    grossHourlyRate=rate,
    overtimeTiers=effectiveRules.overtimeTiers
   )
  }else null
  fullTime?.let{ft->warnings+=ft.warnings;if((contract.contractualWeeklyMinutes?:0)>regularLimit)warnings+="Temps plein supérieur à ${String.format(Locale.FRANCE,"%.2f",regularLimit/60.0)} h : les heures supplémentaires structurelles sont intégrées à la mensualisation avec leur majoration."}

  val monthlyMinutes=contract.contractualWeeklyMinutes?.let{it*52.0/12.0}
  val partTimeBase=if(isPartTime)monthlyMinutes?.div(60.0)?.times(rate)else null
  val baseGross=when{isFullTime->fullTime?.monthlyBaseGross;isPartTime->partTimeBase;else->null}
  val gross=when{isPartTime->baseGross?.plus(complementaryGross+worked.premiumsGross);isFullTime->baseGross?.plus((fullTime?.variableOvertimeGross?:0.0)+worked.premiumsGross);else->worked.grossEstimate}?:worked.grossEstimate
  if(baseGross!=null){
   if(isFullTime)warnings+="Salaire de base mensualisé : durée légale/référence + éventuelles heures structurelles majorées ; les pointages ajoutent seulement les dépassements du contrat."
   else warnings+="Salaire de base mensualisé : ${String.format(Locale.FRANCE,"%.2f",monthlyMinutes!!/60.0)} h × ${String.format(Locale.FRANCE,"%.2f",rate)} € ; pointages utilisés pour les éléments variables."
  }
  if(selected.isEmpty())warnings+="Aucune session pointée : base mensualisée théorique conservée ; les absences enregistrées sont contrôlées séparément."

  var regular=0;val tm=LongArray(payrollRules.overtimeTiers.size);weeks.values.forEach{w->regular+=minOf(w.paid,regularLimit);payrollRules.overtimeTiers.forEachIndexed{i,t->tm[i]+=(minOf(w.paid,t.toMinutes?:Int.MAX_VALUE)-maxOf(regularLimit,t.fromMinutes)).coerceAtLeast(0)}}
  val displayedTiers=when{
   isPartTime->complementary.flatMap{it.tiers}.groupBy{it.label to it.multiplier}.map{(key,values)->TierDuration(key.first,values.sumOf{it.minutes}.toLong()*60000L,key.second)}
   isFullTime->buildList{
    fullTime?.structuralTiers?.forEach{t->add(TierDuration("Heures sup. structurelles +${((t.multiplier-1.0)*100).roundToInt()} %",(t.minutes*60000.0).toLong(),t.multiplier))}
    fullTime?.variableTiers?.forEach{t->add(TierDuration("Heures sup. variables +${((t.multiplier-1.0)*100).roundToInt()} %",(t.minutes*60000.0).toLong(),t.multiplier))}
   }
   else->payrollRules.overtimeTiers.mapIndexed{i,t->TierDuration("Heures sup. +${((t.multiplier-1)*100).roundToInt()} %",tm[i]*60000,t.multiplier)}
  }
  val regularMs=when{isFullTime->((fullTime?.monthlyRegularMinutes?:0.0)*60000.0).toLong();isPartTime->(monthlyMinutes?.times(60000.0)?.toLong()?:regular.toLong()*60000L);else->regular.toLong()*60000L}
  val regularGross=when{isFullTime->(fullTime?.monthlyRegularMinutes?:0.0)/60.0*rate;else->baseGross?:worked.regularGross}
  val overtimeGross=when{isFullTime->(fullTime?.structuralOvertimeGross?:0.0)+(fullTime?.variableOvertimeGross?:0.0);isPartTime->complementaryGross;else->worked.overtimeGross}
  val traces=worked.traces.filterNot{(isPartTime||isFullTime)&&it.startsWith("Aucune majoration d'heures supplémentaires")}
  return Result(regularMs,displayedTiers,weeks.values.sumOf{it.paid}.toLong()*60000L,regularGross,overtimeGross,worked.premiumsGross,gross,monthlyGrossReliable,nightMs,satMs,sunMs,complementaryMinutes,selected.size,warnings+traces+listOfNotNull(snap?.let{"Règles historiques ${it.versionId} — source ${it.sourceId}"}))
 }
 private fun empty(w:List<String> = emptyList())=Result(0,emptyList(),0,0.0,0.0,0.0,0.0,false,0,0,0,0,0,w)
 private fun nightPaidOverlap(session:WorkSessionV2,rangeStart:Long,rangeEnd:Long,startMinute:Int,endMinute:Int):Long{if(rangeEnd<=rangeStart)return 0L;var total=0L;val day=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=rangeStart;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DAY_OF_YEAR,-1)};val last=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=rangeEnd;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DAY_OF_YEAR,1)};while(day.timeInMillis<=last.timeInMillis){val s=(day.clone() as Calendar).apply{set(Calendar.HOUR_OF_DAY,startMinute/60);set(Calendar.MINUTE,startMinute%60)};val e=(day.clone() as Calendar).apply{set(Calendar.HOUR_OF_DAY,endMinute/60);set(Calendar.MINUTE,endMinute%60);if(endMinute<=startMinute)add(Calendar.DAY_OF_YEAR,1)};val from=maxOf(rangeStart,s.timeInMillis);val to=minOf(rangeEnd,e.timeInMillis);if(to>from)total+=PaidWorkAllocationV2.paidOverlap(session,from,to);day.add(Calendar.DAY_OF_YEAR,1)};return total}
 private fun dayPaidOverlap(session:WorkSessionV2,rangeStart:Long,rangeEnd:Long,dayOfWeek:Int):Long{if(rangeEnd<=rangeStart)return 0L;var total=0L;val day=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=rangeStart;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};val last=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=rangeEnd;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};while(day.timeInMillis<=last.timeInMillis){if(day.get(Calendar.DAY_OF_WEEK)==dayOfWeek){val next=(day.clone() as Calendar).apply{add(Calendar.DAY_OF_YEAR,1)};val from=maxOf(rangeStart,day.timeInMillis);val to=minOf(rangeEnd,next.timeInMillis);if(to>from)total+=PaidWorkAllocationV2.paidOverlap(session,from,to)};day.add(Calendar.DAY_OF_YEAR,1)};return total}
 internal fun dayOverlap(entry:Long,exit:Long,dayOfWeek:Int):Long{if(exit<=entry)return 0;var total=0L;val day=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=entry;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};val last=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=exit;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};while(day.timeInMillis<=last.timeInMillis){if(day.get(Calendar.DAY_OF_WEEK)==dayOfWeek){val next=(day.clone() as Calendar).apply{add(Calendar.DAY_OF_YEAR,1)};total+=(minOf(exit,next.timeInMillis)-maxOf(entry,day.timeInMillis)).coerceAtLeast(0L)};day.add(Calendar.DAY_OF_YEAR,1)};return total}
}
