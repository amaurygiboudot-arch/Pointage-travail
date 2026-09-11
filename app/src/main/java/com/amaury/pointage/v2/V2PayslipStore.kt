package com.amaury.pointage.v2

import android.content.Context
import android.net.Uri
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.CompanyPayrollOverridesV2
import com.amaury.pointage.v2.engine.ConventionSicknessMaintenanceV2
import com.amaury.pointage.v2.engine.NetSalaryEngineV2
import com.amaury.pointage.v2.engine.PayslipComparisonV2
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import com.amaury.pointage.v2.engine.PayslipEngineV2
import com.amaury.pointage.v2.engine.PlasturgieProtectionCategoryV2
import com.amaury.pointage.v2.engine.PlasturgieProvidentIncapacityV2
import com.amaury.pointage.v2.engine.PlasturgieProvidentRelayControlV2
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
 private const val PREFS="horatrack_v2_payslips"
 private const val KEY_ITEMS="items"
 private const val STORAGE_WARNING="Bulletins réels : stockage local illisible ou incohérent ; aucune base IJSS ni comparaison n'est considérée fiable avant vérification."

 data class Record(
  val id:String,
  val year:Int,
  val month:Int,
  val sourceUri:String,
  val sourceMime:String?,
  val gross:Double?,
  val net:Double?,
  val extractionConfidence:Double,
  val confirmedByUser:Boolean,
  val importedAtMs:Long,
  val companyId:String=""
 )

 data class ReadResult(
  val records:List<Record>,
  val reliable:Boolean,
  val warnings:List<String>
 )

 private data class OptionalNumber(val valid:Boolean,val value:Double?)

 internal fun readResult(context:Context):ReadResult{
  val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  if(!prefs.contains(KEY_ITEMS))return ReadResult(emptyList(),true,emptyList())
  val raw=runCatching{prefs.getString(KEY_ITEMS,null)}.getOrNull()?:return corruptResult()
  return decodeRecords(raw)
 }

 fun all(context:Context):List<Record> = readResult(context).records.sortedByDescending{it.importedAtMs}

 fun forCompany(context:Context,companyId:String):List<Record> = all(context).filter {
  it.companyId==companyId || (it.companyId.isBlank()&&companyId.isBlank())
 }

 fun latest(context:Context)=all(context).firstOrNull()
 fun latestForCompany(context:Context,companyId:String)=forCompany(context,companyId).firstOrNull()

 fun add(
  context:Context,
  year:Int,
  month:Int,
  uri:Uri,
  mime:String?,
  gross:Double?,
  net:Double?,
  confirmedByUser:Boolean,
  companyId:String=""
 ):Record?{
  require(year in 2000..2200){"Année de bulletin invalide"}
  require(month in 0..11){"Mois de bulletin invalide"}
  require(uri.toString().isNotBlank()){"Document du bulletin manquant"}
  require(gross==null || gross.isFinite()&&gross>=0.0){"Brut du bulletin invalide"}
  require(net==null || net.isFinite()&&net>=0.0){"Net du bulletin invalide"}
  require(!confirmedByUser || gross!=null || net!=null){"Bulletin confirmé sans montant"}

  val stored=readResult(context)
  if(!stored.reliable)return null
  val record=Record(
   id=UUID.randomUUID().toString(),
   year=year,
   month=month,
   sourceUri=uri.toString(),
   sourceMime=mime,
   gross=gross,
   net=net,
   extractionConfidence=if(confirmedByUser&&(gross!=null||net!=null))1.0 else 0.0,
   confirmedByUser=confirmedByUser,
   importedAtMs=System.currentTimeMillis(),
   companyId=companyId
  )
  val updated=stored.records+record
  return record.takeIf{saveRecords(context,updated)}
 }

 /**
  * Calcule l'IJSS maladie uniquement à partir de bulletins réels confirmés.
  * Aucun brut estimé HoraTrack n'est injecté dans cette base de référence.
  */
 fun sicknessAllowanceForAbsence(context:Context,companyId:String,absence:AbsenceV2):SicknessDailyAllowanceV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val zone=ZoneId.systemDefault()
  val start=Instant.ofEpochMilli(absence.startMs).atZone(zone).toLocalDate()
  val endExclusive=Instant.ofEpochMilli(absence.endMs).atZone(zone).toLocalDate()
  val stored=readResult(context)
  val grossByMonth=confirmedGrossByMonth(stored,companyId)
  if(grossByMonth==null){
   return SicknessDailyAllowanceV2.Result(
    complete=false,
    dailyGross=null,
    payableDays=null,
    estimatedGrossTotal=null,
    referenceMonths=emptyList(),
    warnings=(stored.warnings+STORAGE_WARNING).distinct()
   )
  }
  return SicknessDailyAllowanceV2.calculate(start,endExclusive,grossByMonth)
 }

 internal fun confirmedGrossByMonth(stored:ReadResult,companyId:String):Map<YearMonth,Double>?{
  if(!stored.reliable)return null
  val grossByMonth=linkedMapOf<YearMonth,Double>()
  stored.records
   .asSequence()
   .filter{it.companyId==companyId || (it.companyId.isBlank()&&companyId.isBlank())}
   .filter{it.confirmedByUser&&it.gross!=null}
   .sortedByDescending{it.importedAtMs}
   .forEach{record->
    val ym=YearMonth.of(record.year,record.month+1)
    if(ym !in grossByMonth)grossByMonth[ym]=record.gross!!
   }
  return grossByMonth
 }

 internal fun confirmedCompany(
  stored:SalaryCompanyStore.ReadResult,
  companyId:String
 ):SalaryCompanyStore.Company?{
  val id=companyId.trim()
  if(!stored.reliable || id.isBlank())return null
  return stored.companies.firstOrNull{it.id==id}
 }

 /**
  * Résout le maintien maladie conventionnel à partir de l'IDCC, du statut,
  * de la classification et de la période. Aucune convention n'est supposée.
  */
 fun sicknessMaintenanceForAbsence(context:Context,companyId:String,absence:AbsenceV2):ConventionSicknessMaintenanceV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  return V2ConventionSicknessMaintenanceBridge.load(context,companyId,absence)?.result
 }

 /**
  * Décrit le relais minimum de prévoyance Plasturgie sans le confondre avec une
  * prestation d'entreprise qui chevaucherait la période de maintien employeur.
  */
 fun sicknessProvidentRelayForAbsence(context:Context,companyId:String,absence:AbsenceV2):PlasturgieProvidentIncapacityV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val zone=ZoneId.systemDefault()
  val start=Instant.ofEpochMilli(absence.startMs).atZone(zone).toLocalDate()
  val endExclusive=Instant.ofEpochMilli(absence.endMs).atZone(zone).toLocalDate()
  val profile=ConventionLegalProfileV2.load(context,companyId)
      ?:return PlasturgieProvidentIncapacityV2.unresolved(
       "Prévoyance Plasturgie : profil juridique entreprise introuvable ou non fiable ; aucune applicabilité n'est déduite d'anciennes préférences locales."
      )
  val seniorityMonths=profile.entryDate
   ?.takeIf{!it.isAfter(start)}
   ?.let{entry->ChronoUnit.MONTHS.between(entry,start).toInt().coerceAtLeast(0)}
  val protectionCategory=PlasturgieProtectionCategoryV2.classify(
   profile.idcc,start,profile.classification.coefficient
  )
  val maintenance=sicknessMaintenanceForAbsence(context,companyId,absence)
  val absenceDays=ChronoUnit.DAYS.between(start,endExclusive).toInt().coerceAtLeast(0)
  return PlasturgieProvidentIncapacityV2.assess(
   idcc=profile.idcc,
   seniorityMonths=seniorityMonths,
   protectionCategory=protectionCategory,
   maintenance=maintenance,
   absenceCalendarDays=absenceDays
  )
 }

 /** Contrôle un décompte assureur réel sans reconstruire sa périodisation. */
 fun sicknessProvidentRelayControlForAbsence(
  context:Context,
  companyId:String,
  absence:AbsenceV2
 ):PlasturgieProvidentRelayControlV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val relay=sicknessProvidentRelayForAbsence(context,companyId,absence)?:return null
  return PlasturgieProvidentRelayControlV2.calculate(
   relay=relay,
   grossTargetAtSixtyPercentAmount=absence.providentRelayTargetGross60Amount,
   socialSecurityGrossAmount=absence.providentRelaySocialSecurityGrossAmount,
   observedProvidentGrossAmount=absence.providentRelayObservedGrossAmount
  )
 }

 /**
  * Reconstruit la rémunération NETTE AVANT PAS que le salarié aurait perçue sans
  * l'arrêt, puis la prorate sur les jours calendaires de la période indemnisable.
  *
  * La base contractuelle exclut volontairement les paniers/remboursements de frais,
  * les heures supplémentaires variables et les majorations de pointage qui ne sont
  * pas certaines pendant l'absence. Les heures supplémentaires structurelles d'un
  * contrat temps plein sont conservées car elles appartiennent à la mensualisation.
  */
 fun sicknessTheoreticalNetForAbsence(context:Context,companyId:String,absence:AbsenceV2):SicknessTheoreticalNetV2.Result?{
  if(absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
  val maintenance=sicknessMaintenanceForAbsence(context,companyId,absence)?:return null
  val allowance=sicknessAllowanceForAbsence(context,companyId,absence)
  val company=confirmedCompany(SalaryCompanyStore.readConfirmed(context),companyId)?:return null
  val prefs=SalaryCompanyStore.prefs(context,companyId)
  val idcc=company.idcc.ifBlank{prefs.getString("company_idcc","").orEmpty()}.trim()
  val convention=ConventionCatalog.findByIdcc(context,idcc)
  val zone=ZoneId.systemDefault()
  val start=Instant.ofEpochMilli(absence.startMs).atZone(zone).toLocalDate()
  val endExclusive=Instant.ofEpochMilli(absence.endMs).atZone(zone).toLocalDate()
  val monthlyNet=linkedMapOf<YearMonth,Double>()
  val bridgeWarnings=mutableListOf<String>()

  if(convention==null){
   return SicknessTheoreticalNetV2.calculate(
    start,endExclusive,maintenance,emptyMap(),allowance,
    absence.providentTreatment,absence.employerProvidentOverlapNetAmount
   ).copy(warnings=listOf("Base nette maladie : convention collective introuvable pour l'IDCC $idcc."))
  }

  var ym=YearMonth.from(start)
  val last=YearMonth.from(endExclusive.minusDays(1))
  while(!ym.isAfter(last)){
   val calc=runCatching{
    V2SalaryAdapter.calculateForCompany(context,company,ym.year,ym.monthValue-1,convention)
   }.getOrNull()
   val rawType=prefs.getString("contract_type","").orEmpty().trim().uppercase(Locale.ROOT)
   val hourlyRate=prefs.getString("hourly_rate","").orEmpty().replace(',','.').toDoubleOrNull()
   val contractualGross=when(rawType){
    "FULL_TIME" -> {
     val structural=if(hourlyRate!=null && hourlyRate>0.0){
      calc?.overtimeTiers.orEmpty()
       .filter{it.label.contains("structurelles",ignoreCase=true)}
       .sumOf{tier->tier.durationMs/3_600_000.0*hourlyRate*tier.multiplier}
     }else 0.0
     calc?.regularGross?.plus(structural)
    }
    "PART_TIME","FORFAIT_HEURES","FORFAIT_JOURS" -> calc?.regularGross
    else -> null
   }?.takeIf{it>0.0}

   if(contractualGross==null){
    bridgeWarnings += "Base nette maladie : rémunération contractuelle théorique indisponible pour ${"%02d/%04d".format(ym.monthValue,ym.year)}."
   }else{
    val overrides=CompanyPayrollOverridesV2.load(
     context=context,
     companyId=companyId,
     referenceDate=ym.atEndOfMonth(),
     ignoreAbsencesForTheoreticalBase=true
    )
    val net=runCatching{
     NetSalaryEngineV2.calculate(contractualGross,ym.year,overrides,complementaryMinutes=0)
    }.getOrNull()
    if(net==null){
     bridgeWarnings += "Base nette maladie : conversion brut/net impossible pour ${"%02d/%04d".format(ym.monthValue,ym.year)}."
    }else{
     val referenceNet=NetSalaryReferencePolicyV2.beforeIncomeTax(net)
     if(referenceNet==null){
      bridgeWarnings += "Base nette maladie : net HoraTrack V2 encore incomplet pour ${"%02d/%04d".format(ym.monthValue,ym.year)} ; aucune valeur partielle n'est utilisée comme référence."
     }else{
      monthlyNet[ym]=referenceNet
     }
    }
   }
   ym=ym.plusMonths(1)
  }

  if(monthlyNet.isNotEmpty()){
   bridgeWarnings += "Base nette maladie : salaire contractuel mensualisé retenu ; paniers/remboursements, heures supplémentaires variables et majorations non certaines pendant l'arrêt sont exclus."
  }
  val result=SicknessTheoreticalNetV2.calculate(
   start,endExclusive,maintenance,monthlyNet,allowance,
   absence.providentTreatment,absence.employerProvidentOverlapNetAmount
  )
  return result.copy(warnings=(result.warnings+bridgeWarnings).distinct())
 }

 fun remove(context:Context,id:String):Boolean{
  if(id.isBlank())return false
  val stored=readResult(context)
  if(!stored.reliable)return false
  if(stored.records.none{it.id==id})return false
  val kept=stored.records.filterNot{it.id==id}
  if(!saveRecords(context,kept))return false
  PayslipObservedValuesStoreV2.remove(context,id)
  return true
 }

 fun comparison(context:Context,record:Record):PayslipComparisonV2?{
  val source=readResult(context)
  if(!source.reliable || source.records.none{it.id==record.id})return null
  val stored=PayslipObservedValuesStoreV2.get(context,record.id).toMutableMap()
  record.gross?.let{stored.putIfAbsent(PayslipDocumentParserV2.KEY_GROSS,it)}
  if(stored.isEmpty())return null

  if(record.companyId.isNotBlank()){
   val company=confirmedCompany(SalaryCompanyStore.readConfirmed(context),record.companyId)?:return null
   val prefs=SalaryCompanyStore.prefs(context,company.id)
   val idcc=company.idcc.ifBlank{prefs.getString("company_idcc","").orEmpty()}.trim();if(idcc.isBlank())return null
   val convention=ConventionCatalog.findByIdcc(context,idcc)?.takeIf{it.idcc.isNotBlank()}?:return null
   val expected=runCatching{V2SalaryAdapter.calculateForCompany(context,company,record.year,record.month,convention)}.getOrNull()?:return null
   if(!expected.monthlyGrossReliable)return null
   if(expected.completedSessions==0&&expected.warnings.isNotEmpty())return null

   val expectedValues=linkedMapOf<String,Double>()
   expectedValues[PayslipDocumentParserV2.KEY_GROSS]=expected.monthlyEstimatedGross
   expectedValues[PayslipDocumentParserV2.KEY_OVERTIME_GROSS]=expected.overtimeGross
   expectedValues[PayslipDocumentParserV2.KEY_PREMIUMS_GROSS]=expected.premiumsGross
   expected.mealBasketTotal?.let{expectedValues[PayslipDocumentParserV2.KEY_MEAL_BASKETS]=it}

   val referenceDate=YearMonth.of(record.year,record.month+1).atEndOfMonth()
   val overrides=CompanyPayrollOverridesV2.load(context,company.id,referenceDate)
   val net=runCatching{
    NetSalaryEngineV2.calculate(expected.monthlyEstimatedGross,record.year,overrides,expected.complementaryMinutes)
   }.getOrNull()
   net?.let{calculated->
    expectedValues[PayslipDocumentParserV2.KEY_GROSS]=calculated.gross
    NetSalaryReferencePolicyV2.beforeIncomeTax(calculated)?.let{
     expectedValues[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX]=it
    }
    NetSalaryReferencePolicyV2.taxable(calculated)?.let{
     expectedValues[PayslipDocumentParserV2.KEY_NET_TAXABLE]=it
    }
   }
   overrides.mutualEmployeeAmount?.let{expectedValues[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE]=it}
   val providentExpected=overrides.providentEmployeeAmount ?: net?.conventionProvidentEmployee?.takeIf{it>0.0}
   providentExpected?.let{expectedValues[PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE]=it}

   // Une valeur observée reste conservée même si le moteur ne sait pas encore la recalculer.
   // Elle n'est simplement pas transformée en anomalie tant qu'aucune valeur attendue sûre n'existe.
   val comparableObserved=stored.filterKeys{it in expectedValues.keys}
   if(comparableObserved.isEmpty())return null
   val comparableExpected=expectedValues.filterKeys{it in comparableObserved.keys}
   return PayslipEngineV2.compare(comparableExpected,comparableObserved,0.02)
  }

  // Compatibilité des anciens bulletins sans entreprise stable : comparaison brut uniquement.
  val observedGross=stored[PayslipDocumentParserV2.KEY_GROSS]?:return null
  val profile=V2ProfileStore.load(context,1);val rate=profile.contract?.grossHourlyRate?:return null;val idcc=profile.employer?.collectiveAgreementId?.trim().orEmpty();if(idcc.isBlank())return null;val convention=ConventionCatalog.findByIdcc(context,idcc)?.takeIf{it.idcc.isNotBlank()}?:return null;val expected=V2SalaryAdapter.calculate(context,record.year,record.month,rate,convention);if(!expected.monthlyGrossReliable)return null;if(expected.completedSessions==0&&expected.warnings.isNotEmpty())return null;return PayslipEngineV2.compare(mapOf(PayslipDocumentParserV2.KEY_GROSS to expected.monthlyEstimatedGross),mapOf(PayslipDocumentParserV2.KEY_GROSS to observedGross),0.02)
 }

 private fun saveRecords(context:Context,records:List<Record>):Boolean{
  if(records.groupingBy{it.id}.eachCount().any{it.value>1})return false
  if(records.any{!isValidRecord(it)})return false
  val array=JSONArray()
  records.sortedBy{it.importedAtMs}.forEach{array.put(toJson(it))}
  return runCatching{
   context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    .edit().putString(KEY_ITEMS,array.toString()).commit()
  }.getOrDefault(false)
 }

 internal fun decodeRecords(raw:String):ReadResult{
  if(raw.isBlank())return corruptResult()
  return runCatching{
   val array=JSONArray(raw)
   var malformed=false
   val records=buildList{
    for(index in 0 until array.length()){
     val obj=array.optJSONObject(index)
     if(obj==null){
      malformed=true
      continue
     }
     val record=parseStoredRecord(obj)
     if(record==null)malformed=true else add(record)
    }
   }
   if(records.groupingBy{it.id}.eachCount().any{it.value>1})malformed=true
   ReadResult(
    records=records,
    reliable=!malformed,
    warnings=if(malformed)listOf(STORAGE_WARNING)else emptyList()
   )
  }.getOrElse{corruptResult()}
 }

 private fun parseStoredRecord(o:JSONObject):Record?{
  val id=(o.opt("id") as? String)?.trim()?.takeIf{it.isNotBlank()}?:return null
  val year=requiredInt(o,"year")?.takeIf{it in 2000..2200}?:return null
  val month=requiredInt(o,"month")?.takeIf{it in 0..11}?:return null
  val uri=(o.opt("uri") as? String)?.trim()?.takeIf{it.isNotBlank()}?:return null
  val mime=optionalString(o,"mime")?:if(o.has("mime")&&!o.isNull("mime"))return null else null
  val gross=optionalNumber(o,"gross");if(!gross.valid)return null
  val net=optionalNumber(o,"net");if(!net.valid)return null
  if(gross.value!=null&&(gross.value<0.0||!gross.value.isFinite()))return null
  if(net.value!=null&&(net.value<0.0||!net.value.isFinite()))return null

  val confidence=if(o.has("confidence")){
   val value=(o.opt("confidence") as? Number)?.toDouble()?:return null
   value.takeIf{it.isFinite()&&it in 0.0..1.0}?:return null
  }else 0.0
  val confirmed=if(o.has("confirmed"))o.opt("confirmed") as? Boolean?:return null else false
  val importedAt=if(o.has("importedAt")){
   val number=o.opt("importedAt") as? Number?:return null
   number.toLong().takeIf{it>=0L}?:return null
  }else 0L
  val companyId=if(o.has("companyId")){
   if(o.isNull("companyId"))"" else (o.opt("companyId") as? String)?.trim()?:return null
  }else ""
  if(confirmed&&gross.value==null&&net.value==null)return null

  return Record(
   id=id,
   year=year,
   month=month,
   sourceUri=uri,
   sourceMime=mime,
   gross=gross.value,
   net=net.value,
   extractionConfidence=confidence,
   confirmedByUser=confirmed,
   importedAtMs=importedAt,
   companyId=companyId
  )
 }

 private fun requiredInt(o:JSONObject,key:String):Int?{
  val number=o.opt(key) as? Number?:return null
  val value=number.toDouble()
  if(!value.isFinite()||value%1.0!=0.0)return null
  return value.toInt()
 }

 private fun optionalNumber(o:JSONObject,key:String):OptionalNumber{
  if(!o.has(key)||o.isNull(key))return OptionalNumber(true,null)
  val value=(o.opt(key) as? Number)?.toDouble()?:return OptionalNumber(false,null)
  return OptionalNumber(value.isFinite(),value.takeIf{it.isFinite()})
 }

 private fun optionalString(o:JSONObject,key:String):String?{
  if(!o.has(key)||o.isNull(key))return null
  return (o.opt(key) as? String)?.trim()?.takeIf{it.isNotBlank()}
 }

 private fun isValidRecord(r:Record):Boolean =
  r.id.isNotBlank()&&
   r.year in 2000..2200&&
   r.month in 0..11&&
   r.sourceUri.isNotBlank()&&
   (r.gross==null||r.gross.isFinite()&&r.gross>=0.0)&&
   (r.net==null||r.net.isFinite()&&r.net>=0.0)&&
   r.extractionConfidence.isFinite()&&r.extractionConfidence in 0.0..1.0&&
   r.importedAtMs>=0L&&
   (!r.confirmedByUser||r.gross!=null||r.net!=null)

 private fun corruptResult()=ReadResult(emptyList(),false,listOf(STORAGE_WARNING))

 private fun toJson(r:Record)=JSONObject()
  .put("id",r.id)
  .put("year",r.year)
  .put("month",r.month)
  .put("uri",r.sourceUri)
  .put("mime",r.sourceMime?:JSONObject.NULL)
  .put("gross",r.gross?:JSONObject.NULL)
  .put("net",r.net?:JSONObject.NULL)
  .put("confidence",r.extractionConfidence)
  .put("confirmed",r.confirmedByUser)
  .put("importedAt",r.importedAtMs)
  .put("companyId",r.companyId)
}
