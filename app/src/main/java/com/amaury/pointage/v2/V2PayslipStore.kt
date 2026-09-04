package com.amaury.pointage.v2

import android.content.Context
import android.net.Uri
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.engine.PayslipComparisonV2
import com.amaury.pointage.v2.engine.PayslipEngineV2
import org.json.JSONArray
import org.json.JSONObject
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
 fun remove(context:Context,id:String){val p=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE);val kept=all(context).filterNot{it.id==id};val a=JSONArray();kept.sortedBy{it.importedAtMs}.forEach{a.put(toJson(it))};p.edit().putString(KEY_ITEMS,a.toString()).apply()}
 fun comparison(context:Context,record:Record):PayslipComparisonV2?{
  val observed=linkedMapOf<String,Double>();record.gross?.let{observed["Brut"]=it};if(observed.isEmpty())return null
  if(record.companyId.isNotBlank()){
   val company=SalaryCompanyStore.list(context).firstOrNull{it.id==record.companyId}?:return null
   val prefs=SalaryCompanyStore.prefs(context,company.id)
   val idcc=company.idcc.ifBlank{prefs.getString("company_idcc","").orEmpty()}.trim();if(idcc.isBlank())return null
   val convention=ConventionCatalog.findByIdcc(context,idcc)?.takeIf{it.idcc.isNotBlank()}?:return null
   val expected=runCatching{V2SalaryAdapter.calculateForCompany(context,company,record.year,record.month,convention)}.getOrNull()?:return null
   if(!expected.monthlyGrossReliable)return null
   if(expected.completedSessions==0&&expected.warnings.isNotEmpty())return null
   return PayslipEngineV2.compare(mapOf("Brut" to expected.monthlyEstimatedGross),observed,0.02)
  }
  val profile=V2ProfileStore.load(context,1);val rate=profile.contract?.grossHourlyRate?:return null;val idcc=profile.employer?.collectiveAgreementId?.trim().orEmpty();if(idcc.isBlank())return null;val convention=ConventionCatalog.findByIdcc(context,idcc)?.takeIf{it.idcc.isNotBlank()}?:return null;val expected=V2SalaryAdapter.calculate(context,record.year,record.month,rate,convention);if(!expected.monthlyGrossReliable)return null;if(expected.completedSessions==0&&expected.warnings.isNotEmpty())return null;return PayslipEngineV2.compare(mapOf("Brut" to expected.monthlyEstimatedGross),observed,0.02)
 }
 private fun toJson(r:Record)=JSONObject().put("id",r.id).put("year",r.year).put("month",r.month).put("uri",r.sourceUri).put("mime",r.sourceMime?:JSONObject.NULL).put("gross",r.gross?:JSONObject.NULL).put("net",r.net?:JSONObject.NULL).put("confidence",r.extractionConfidence).put("confirmed",r.confirmedByUser).put("importedAt",r.importedAtMs).put("companyId",r.companyId)
 private fun fromJson(o:JSONObject):Record?{val id=o.optString("id").takeIf{it.isNotBlank()}?:return null;val year=o.optInt("year",0).takeIf{it in 2000..2200}?:return null;val month=o.optInt("month",-1).takeIf{it in 0..11}?:return null;val uri=o.optString("uri").takeIf{it.isNotBlank()}?:return null;return Record(id,year,month,uri,o.optString("mime").takeIf{it.isNotBlank()&&it!="null"},o.opt("gross").let{(it as? Number)?.toDouble()},o.opt("net").let{(it as? Number)?.toDouble()},o.optDouble("confidence",0.0).coerceIn(0.0,1.0),o.optBoolean("confirmed",false),o.optLong("importedAt",0L),o.optString("companyId"))}
}
