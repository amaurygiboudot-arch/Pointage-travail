package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.RightsEngineV2
import com.amaury.pointage.v2.engine.RightsSnapshotV2
import com.amaury.pointage.v2.model.CounterV2
import org.json.JSONArray
import org.json.JSONObject

/** Stockage V2 des compteurs. Les anciens compteurs sans companyId restent lisibles. */
object V2RightsStore {
    private const val PREFS="horatrack_v2_rights"; private const val KEY_COUNTERS="counters"
    data class Balance(val id:String,val label:String,val acquired:Double?,val available:Double?,val taken:Double?,val anticipated:Double?,val remaining:Double?,val unit:String,val referenceStartMs:Long,val referenceEndMs:Long,val source:String="MANUAL",val companyId:String="")
    fun all(context:Context):List<Balance> = decode(context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_COUNTERS,"[]").orEmpty())
    fun forCompany(context:Context,companyId:String):List<Balance> = all(context).filter { it.companyId == companyId }
    fun upsert(context:Context,balance:Balance){require(balance.id.isNotBlank()){"Identifiant compteur manquant"};require(balance.referenceEndMs>=balance.referenceStartMs){"Période de référence invalide"};val list=all(context).toMutableList();val index=list.indexOfFirst{it.id==balance.id};if(index>=0)list[index]=balance else list+=balance;save(context,list)}
    fun snapshot(context:Context,nowMs:Long=System.currentTimeMillis(),companyId:String?=null):RightsSnapshotV2{val balances=if(companyId==null)all(context) else forCompany(context,companyId);val counters=balances.flatMap{b->buildList{b.acquired?.let{add(counter(b,"acquired","${b.label} — acquis",it))};b.available?.let{add(counter(b,"available","${b.label} — disponible",it))};b.taken?.let{add(counter(b,"taken","${b.label} — pris",it))};b.anticipated?.let{add(counter(b,"anticipated","${b.label} — anticipé",it))};b.remaining?.let{add(counter(b,"remaining","${b.label} — restant",it))}}};val base=RightsEngineV2.snapshot(counters,nowMs);val consistency=balances.flatMap{b->buildList{if(b.acquired!=null&&b.taken!=null&&b.remaining!=null){val expected=b.acquired+(b.anticipated?:0.0)-b.taken;if(kotlin.math.abs(expected-b.remaining)>0.01)add("${b.label} : solde déclaré différent du calcul acquis + anticipé - pris")};if(b.available!=null&&b.remaining!=null&&b.available<0.0)add("${b.label} : disponible négatif à vérifier")}};return base.copy(warnings=base.warnings+consistency)}
    private fun counter(b:Balance,suffix:String,label:String,value:Double)=CounterV2("${b.id}:$suffix",label,value,b.unit,b.referenceStartMs,b.referenceEndMs)
    private fun save(context:Context,balances:List<Balance>){val a=JSONArray();balances.forEach{b->a.put(JSONObject().put("id",b.id).put("label",b.label).put("acquired",b.acquired?:JSONObject.NULL).put("available",b.available?:JSONObject.NULL).put("taken",b.taken?:JSONObject.NULL).put("anticipated",b.anticipated?:JSONObject.NULL).put("remaining",b.remaining?:JSONObject.NULL).put("unit",b.unit).put("referenceStartMs",b.referenceStartMs).put("referenceEndMs",b.referenceEndMs).put("source",b.source).put("companyId",b.companyId))};context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_COUNTERS,a.toString()).apply()}
    private fun decode(raw:String):List<Balance> = runCatching{val a=JSONArray(raw.ifBlank{"[]"});buildList{for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;val id=o.optString("id").trim();if(id.isBlank())continue;add(Balance(id,o.optString("label",id),nullableDouble(o,"acquired"),nullableDouble(o,"available"),nullableDouble(o,"taken"),nullableDouble(o,"anticipated"),nullableDouble(o,"remaining"),o.optString("unit","jours"),o.optLong("referenceStartMs",0L),o.optLong("referenceEndMs",Long.MAX_VALUE),o.optString("source","MANUAL"),o.optString("companyId")))}}}.getOrElse{emptyList()}
    private fun nullableDouble(o:JSONObject,key:String):Double?=if(!o.has(key)||o.isNull(key))null else o.optDouble(key).takeUnless{it.isNaN()}
}
