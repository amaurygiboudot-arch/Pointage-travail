package com.amaury.pointage.v2

import android.content.Context
import android.net.Uri
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.CompanyPayrollOverridesV2
import com.amaury.pointage.v2.engine.NetSalaryEngineV2
import com.amaury.pointage.v2.engine.PayslipComparisonV2
import com.amaury.pointage.v2.engine.PayslipEngineV2
import com.amaury.pointage.v2.engine.PlasturgieProvidentIncapacityV2
import com.amaury.pointage.v2.engine.PlasturgieSicknessMaintenanceV2
import com.amaury.pointage.v2.engine.SicknessDailyAllowanceV2
import com.amaury.pointage.v2.engine.SicknessTheoreticalNetV2
import com.amaury.pointage.v2.model.AbsenceV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** Stockage local des bulletins. Chaque nouveau bulletin peut être rattaché à une entreprise. */
object V2PayslipStore {
 private const val PREFS="horatrack_v2_payslips"; private const val KEY_ITEMS="items"
 data class Record(val id:String,val year:Int,val month:Int,val sourceUri:String,val sourceMime:String?,val gross:Double?,val net:Double?,val extractionConfidence:Double,val confirmedByUser:Boolean,val importedAtMs:Long,val companyId:String="")
 fun add(context:Context,year:Int,month:Int,uri:Uri,mime:String?,gross:Double?,net:Double?,confirmedByUser:Boolean,companyId:String=""):Record{val r=Record(UUID.randomUUID().toString(),year,month.coerceIn(0,11),uri.toString(),mime,gross,net,if(confirmedByUser&&(gross!=null||net!=null))1.0 else 0.0,confirmedByUser,System.currentTimeMillis(),companyId);val p=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE);val a=runCatching{JSONArray(p.getString(KEY_ITEMS,"[]")?:"[]")}.getOrElse{JSONArray()};a.put(toJson(r));p.edit().putString(KEY_ITEMS,a.toString()).apply();return r}
 fun all(context:Context):List<Record>{val raw=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_ITEMS,"[]")?:"[]";val a=runCatching{JSONArray(raw)}.getOrElse{JSONArray()};return buildList{for(i in 0 until a.length())a.optJSONObject(i)?.let(::fromJson)?.let(::add)}.sortedByDescending{it.importedAtMs}}
 fun forCompany(context:Context,companyId:String):List<Record> = all(context).filter { it.companyId == companyId || (it.companyId.isBlank() && companyId.isBlank()) }
 fun latest(context:Context)=all(context).firstOrNull()
 fun latestForCompany(context:Context,companyId:String)=forCompany(context,companyId).firstOrNull()

 fun sicknessAllowanceForAbsence(context:Context,companyId:String,absence:AbsenceV2):SicknessDailyAllowanceV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val zone=ZoneId.systemDefault();val start=Instant.ofEpochMilli(absence.startMs).atZone(zone).toLocalDate();val endExclusive=Instant.ofEpochMilli(absence.endMs).atZone(zone).toLocalDate();val grossByMonth=linkedMapOf<YearMonth,Double>()
  forCompany(context,companyId).asSequence().filter{it.confirmedByUser&&it.gross!=null}.forEach{record->val ym=YearMonth.of(record.year,record.month+1);if(ym !in grossByMonth)grossByMonth[ym]=record.gross!!}
  return SicknessDailyAllowanceV2.calculate(start,endExclusive,grossByMonth)
 }

 fun sicknessMaintenanceForAbsence(context:Context,companyId:String,absence:AbsenceV2):PlasturgieSicknessMaintenanceV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val company=SalaryCompanyStore.list(context).firstOrNull{it.id==companyId};val prefs=SalaryCompanyStore.prefs(context,companyId)
  val idcc=company?.idcc?.ifBlank{prefs.getString("company_idcc","").orEmpty()}?:prefs.getString("company_idcc","").orEmpty()
  return PlasturgieSicknessMaintenanceV2.calculate(idcc,absence,V2RightsStore.absencesForCompany(context,companyId),parseEntryDate(prefs),SalaryCompanyStore.acceptedEmployerIds(context,companyId),ZoneId.systemDefault())
 }

 fun sicknessProvidentRelayForAbsence(context:Context,companyId:String,absence:AbsenceV2):PlasturgieProvidentIncapacityV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val company=SalaryCompanyStore.list(context).firstOrNull{it.id==companyId};val prefs=SalaryCompanyStore.prefs(context,companyId)
  val idcc=company?.idcc?.ifBlank{prefs.getString("company_idcc","").orEmpty()}?:prefs.getString("company_idcc","").orEmpty()
  val start=Instant.ofEpochMilli(absence.startMs).atZone(ZoneId.systemDefault()).toLocalDate();val entry=parseEntryDate(prefs)
  val seniorityMonths=entry?.let{if(it.isAfter(start))0 else ChronoUnit.MONTHS.between(it,start).toInt().coerceAtLeast(0)}
  val professionalStatus=prefs.getString("professional_status","").orEmpty().trim().uppercase(Locale.ROOT).takeIf{it.isNotBlank()}
  return PlasturgieProvidentIncapacityV2.assess(idcc,seniorityMonths,professionalStatus)
 }

 fun sicknessTheoreticalNetForAbsence(context:Context,companyId:String,absence:AbsenceV2):SicknessTheoreticalNetV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val maintenance=sicknessMaintenanceForAbsence(context,companyId,absence)?:return null;val allowance=sicknessAllowanceForAbsence(context,companyId,absence)
  val company=SalaryCompanyStore.list(context).firstOrNull{it.id==companyId}?:return null;val prefs=SalaryCompanyStore.prefs(context,companyId);val idcc=company.idcc.ifBlank{prefs.getString("company_idcc","").orEmpty()}.trim();val convention=ConventionCatalog.findByIdcc(context,idcc)
  val zone=ZoneId.systemDefault();val start=Instant.ofEpochMilli(absence.startMs).atZone(zone).toLocalDate();val endExclusive=Instant.ofEpochMilli(absence.endMs).atZone(zone).toLocalDate();val monthlyNet=linkedMapOf<YearMonth,Double>();val bridgeWarnings=mutableListOf<String>()
  if(convention==null)return SicknessTheoreticalNetV2.calculate(start,endExclusive,maintenance,emptyMap(),allowance,absence.providentTreatment,absence.employerProvidentOverlapNetAmount).copy(warnings=listOf("Base nette maladie : convention collective introuvable pour l'IDCC $idcc."))
  var ym=YearMonth.from(start);val last=YearMonth.from(endExclusive.minusDays(1))
  while(!ym.isAfter(last)){
   val calc=runCatching{V2SalaryAdapter.calculateForCompany(context,company,ym.year,ym.monthValue-1,convention)}.getOrNull();val rawType=prefs.getString("contract_type","").orEmpty().trim().uppercase(Locale.ROOT);val hourlyRate=prefs.getString("hourly_rate","").orEmpty().replace(',','.').toDoubleOrNull()
   val contractualGross=when(rawType){
    "FULL_TIME"->{val structural=if(hourlyRate!=null&&hourlyRate>0.0)calc?.overtimeTiers.orEmpty().filter{it.label.contains("structurelles",ignoreCase=true)}.sumOf{tier->tier.durationMs/3_600_000.0*hourlyRate*tier.multiplier}else 0.0;calc?.regularGross?.plus(structural)}
    "PART_TIME","FORFAIT_HEURES","FORFAIT_JOURS"->calc?.regularGross
    else->null
   }?.takeIf{it>0.0}
   if(contractualGross==null)bridgeWarnings+="Base nette maladie : rémunération contractuelle théorique indisponible pour ${"%02d/%04d".format(ym.monthValue,ym.year)}."
   else{
    val overrides=CompanyPayrollOverridesV2.load(context,companyId,ym.atEndOfMonth(),ignoreAbsencesForTheoreticalBase=true)
    val net=runCatching{NetSalaryEngineV2.calculate(contractualGross,ym.year,overrides,complementaryMinutes=0)}.getOrNull()
    if(net==null)bridgeWarnings+="Base nette maladie : conversion brut/net impossible pour ${"%02d/%04d".format(ym.monthValue,ym.year)}." else monthlyNet[ym]=net.netBeforeIncomeTax
   }
   ym=ym.plusMonths(1)
  }
  if(monthlyNet.isNotEmpty())bridgeWarnings+="Base nette maladie : salaire contractuel mensualisé retenu ; paniers/remboursements, heures supplémentaires variables et majorations non certaines pendant l'arrêt sont exclus."
  val result=SicknessTheoreticalNetV2.calculate(start,endExclusive,maintenance,monthlyNet,allowance,absence.providentTreatment,absence.employerProvidentOverlapNetAmount)
  return result.copy(warnings=(result.warnings+bridgeWarnings).distinct())
 }

 fun remove(context:Context,id:String){val p=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE);val kept=all(context).filterNot{it.id==id};val a=JSONArray();kept.sortedBy{it.importedAtMs}.forEach{a.put(toJson(it))};p.edit().putString(KEY_ITEMS,a.toString()).apply()}
 fun comparison(context:Context,record:Record):PayslipComparisonV2?{
  val observed=linkedMapOf<String,Double>();record.gross?.let{observed["Brut"]=it};if(observed.isEmpty())return null
  if(record.companyId.isNotBlank()){
   val company=SalaryCompanyStore.list(context).firstOrNull{it.id==record.companyId}?:return null;val prefs=SalaryCompanyStore.prefs(context,company.id);val idcc=company.idcc.ifBlank{prefs.getString("company_idcc","").orEmpty()}.trim();if(idcc.isBlank())return null;val convention=ConventionCatalog.findByIdcc(context,idcc)?.takeIf{it.idcc.isNotBlank()}?:return null
   val expected=runCatching{V2SalaryAdapter.calculateForCompany(context,company,record.year,record.month,convention)}.getOrNull()?:return null;if(!expected.monthlyGrossReliable)return null;if(expected.completedSessions==0&&expected.warnings.isNotEmpty())return null;return PayslipEngineV2.compare(mapOf("Brut" to expected.monthlyEstimatedGross),observed,0.02)
  }
  val profile=V2ProfileStore.load(context,1);val rate=profile.contract?.grossHourlyRate?:return null;val idcc=profile.employer?.collectiveAgreementId?.trim().orEmpty();if(idcc.isBlank())return null;val convention=ConventionCatalog.findByIdcc(context,idcc)?.takeIf{it.idcc.isNotBlank()}?:return null;val expected=V2SalaryAdapter.calculate(context,record.year,record.month,rate,convention);if(!expected.monthlyGrossReliable)return null;if(expected.completedSessions==0&&expected.warnings.isNotEmpty())return null;return PayslipEngineV2.compare(mapOf("Brut" to expected.monthlyEstimatedGross),observed,0.02)
 }
 private fun parseEntryDate(prefs:android.content.SharedPreferences):LocalDate?=runCatching{prefs.getString("entry_date","").orEmpty().trim().takeIf{it.isNotBlank()}?.let{LocalDate.parse(it,DateTimeFormatter.ofPattern("dd/MM/yyyy",Locale.FRANCE))}}.getOrNull()
 private fun toJson(r:Record)=JSONObject().put("id",r.id).put("year",r.year).put("month",r.month).put("uri",r.sourceUri).put("mime",r.sourceMime?:JSONObject.NULL).put("gross",r.gross?:JSONObject.NULL).put("net",r.net?:JSONObject.NULL).put("confidence",r.extractionConfidence).put("confirmed",r.confirmedByUser).put("importedAt",r.importedAtMs).put("companyId",r.companyId)
 private fun fromJson(o:JSONObject):Record?{val id=o.optString("id").takeIf{it.isNotBlank()}?:return null;val year=o.optInt("year",0).takeIf{it in 2000..2200}?:return null;val month=o.optInt("month",-1).takeIf{it in 0..11}?:return null;val uri=o.optString("uri").takeIf{it.isNotBlank()}?:return null;return Record(id,year,month,uri,o.optString("mime").takeIf{it.isNotBlank()&&it!="null"},o.opt("gross").let{(it as? Number)?.toDouble()},o.opt("net").let{(it as? Number)?.toDouble()},o.optDouble("confidence",0.0).coerceIn(0.0,1.0),o.optBoolean("confirmed",false),o.optLong("importedAt",0L),o.optString("companyId"))}
}
