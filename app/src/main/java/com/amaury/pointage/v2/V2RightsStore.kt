package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.RightsEngineV2
import com.amaury.pointage.v2.engine.RightsSnapshotV2
import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSubrogationV2
import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.CounterV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stockage V2 des droits et absences.
 * Les absences utilisent le même fichier de préférences afin de rester couvertes
 * par la sauvegarde V2 déjà en place pour horatrack_v2_rights.
 */
object V2RightsStore {
    private const val PREFS = "horatrack_v2_rights"
    private const val KEY_COUNTERS = "counters"
    private const val KEY_ABSENCES = "absences"

    data class Balance(
        val id:String,
        val label:String,
        val acquired:Double?,
        val available:Double?,
        val taken:Double?,
        val anticipated:Double?,
        val remaining:Double?,
        val unit:String,
        val referenceStartMs:Long,
        val referenceEndMs:Long,
        val source:String="MANUAL",
        val companyId:String=""
    )

    fun all(context:Context):List<Balance> = decode(
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getString(KEY_COUNTERS,"[]").orEmpty()
    )

    fun forCompany(context:Context,companyId:String):List<Balance> = all(context).filter { it.companyId == companyId }

    fun upsert(context:Context,balance:Balance){
        require(balance.id.isNotBlank()){"Identifiant compteur manquant"}
        require(balance.referenceEndMs>=balance.referenceStartMs){"Période de référence invalide"}
        val list=all(context).toMutableList()
        val index=list.indexOfFirst{it.id==balance.id}
        if(index>=0)list[index]=balance else list+=balance
        save(context,list)
    }

    fun absences(context:Context):List<AbsenceV2> = decodeAbsences(
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getString(KEY_ABSENCES,"[]").orEmpty()
    )

    fun absencesForCompany(context:Context,companyId:String):List<AbsenceV2> =
        absences(context).filter { it.employerId == companyId }

    fun upsertAbsence(context:Context,absence:AbsenceV2){
        require(absence.id.isNotBlank()){"Identifiant absence manquant"}
        require(absence.employerId?.isNotBlank()==true){"Entreprise de l'absence manquante"}
        require(absence.endMs>absence.startMs){"Période d'absence invalide"}
        when(absence.providentTreatment){
            AbsenceProvidentTreatmentV2.TO_CONFIRM ->
                require(absence.employerProvidentOverlapNetAmount==null){"Prévoyance à confirmer : aucun montant ne doit être figé"}
            AbsenceProvidentTreatmentV2.NONE_CONFIRMED ->
                require(absence.employerProvidentOverlapNetAmount==null || absence.employerProvidentOverlapNetAmount==0.0){"Aucune prévoyance chevauchante : montant incohérent"}
            AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED ->
                require(absence.employerProvidentOverlapNetAmount?.let{it.isFinite()&&it>=0.0}==true){"Montant net de prévoyance chevauchante manquant ou invalide"}
        }
        val list=absences(context).toMutableList()
        val index=list.indexOfFirst{it.id==absence.id}
        if(index>=0)list[index]=absence else list+=absence
        saveAbsences(context,list)
    }

    fun removeAbsence(context:Context,id:String){
        val kept=absences(context).filterNot { it.id==id }
        saveAbsences(context,kept)
    }

    fun snapshot(context:Context,nowMs:Long=System.currentTimeMillis(),companyId:String?=null):RightsSnapshotV2{
        val balances=if(companyId==null)all(context) else forCompany(context,companyId)
        val counters=balances.flatMap{b->buildList{
            b.acquired?.let{add(counter(b,"acquired","${b.label} — acquis",it))}
            b.available?.let{add(counter(b,"available","${b.label} — disponible",it))}
            b.taken?.let{add(counter(b,"taken","${b.label} — pris",it))}
            b.anticipated?.let{add(counter(b,"anticipated","${b.label} — anticipé",it))}
            b.remaining?.let{add(counter(b,"remaining","${b.label} — restant",it))}
        }}
        val base=RightsEngineV2.snapshot(counters,nowMs)
        val consistency=balances.flatMap{b->buildList{
            if(b.acquired!=null&&b.taken!=null&&b.remaining!=null){
                val expected=b.acquired+(b.anticipated?:0.0)-b.taken
                if(kotlin.math.abs(expected-b.remaining)>0.01)add("${b.label} : solde déclaré différent du calcul acquis + anticipé - pris")
            }
            if(b.available!=null&&b.remaining!=null&&b.available<0.0)add("${b.label} : disponible négatif à vérifier")
        }}
        val maintenance=companyId?.let{maintenanceWarnings(context,nowMs,it)}.orEmpty()
        return base.copy(warnings=(base.warnings+consistency+maintenance).distinct())
    }

    private fun maintenanceWarnings(context:Context,nowMs:Long,companyId:String):List<String>{
        val zone=ZoneId.systemDefault()
        val currentYear=Instant.ofEpochMilli(nowMs).atZone(zone).year
        val display=DateTimeFormatter.ofPattern("dd/MM/yyyy",Locale.FRANCE)
        return absencesForCompany(context,companyId)
            .filter{it.type==AbsencePayrollImpactV2.TYPE_SICKNESS}
            .filter{Instant.ofEpochMilli(it.startMs).atZone(zone).year==currentYear}
            .sortedByDescending{it.startMs}
            .flatMap{absence->
                val result=V2PayslipStore.sicknessMaintenanceForAbsence(context,companyId,absence)
                    ?:return@flatMap emptyList()
                if(!result.applicable)return@flatMap emptyList()
                val amount=V2PayslipStore.sicknessTheoreticalNetForAbsence(context,companyId,absence)
                val relay=V2PayslipStore.sicknessProvidentRelayForAbsence(context,companyId,absence)
                val start=Instant.ofEpochMilli(absence.startMs).atZone(zone).toLocalDate()
                val lines=when{
                    !result.eligibilityConfirmed -> listOf(result.warnings.firstOrNull()
                        ?:"Maintien Plasturgie du ${start.format(display)} : éligibilité à confirmer.")
                    result.annualLimitDays==0 -> listOf(result.warnings.firstOrNull()
                        ?:"Maintien Plasturgie du ${start.format(display)} : aucun maintien conventionnel ouvert.")
                    else -> {
                        val bands=if(result.bands.isEmpty())"aucun jour conventionnel restant" else result.bands.joinToString(" + "){band->
                            "${band.calendarDays} j à ${(band.targetNetRate*100).toInt()} % du net de référence"
                        }
                        listOf(buildString{
                            append("Maintien Plasturgie — arrêt du ").append(start.format(display)).append(" : ")
                            append(bands)
                            result.employerWaitingDays?.let{append(" • carence employeur ").append(it).append(" j")}
                            result.annualLimitDays?.let{limit->append(" • plafond annuel ").append(limit).append(" j")}
                            result.alreadyConsumedIndemnifiedDays?.let{used->append(" • déjà consommés ").append(used).append(" j")}
                            amount?.theoreticalIndemnifiableNet?.let{
                                append(" • base nette théorique ").append(String.format(Locale.FRANCE,"%.2f €",it))
                            }
                            amount?.targetMaintenanceNet?.let{
                                append(" • cible conventionnelle ").append(String.format(Locale.FRANCE,"%.2f €",it))
                            }
                            amount?.ijssNetDeductedOnce?.let{
                                append(" • IJSS nettes déduites une fois ").append(String.format(Locale.FRANCE,"%.2f €",it))
                            }
                            amount?.employerComplementBeforeProvidentNet?.let{
                                append(" • complément employeur avant prévoyance ").append(String.format(Locale.FRANCE,"%.2f €",it))
                            } ?: append(" • complément employeur en euros encore incomplet")
                            if(amount?.finalComplementReliable==true){
                                amount.employerProvidentNetDeducted?.let{
                                    append(" • prévoyance chevauchante déduite ").append(String.format(Locale.FRANCE,"%.2f €",it))
                                }
                                amount.employerComplementFinalNet?.let{
                                    append(" • complément employeur final ").append(String.format(Locale.FRANCE,"%.2f € net avant PAS",it))
                                }
                            }else if(amount?.employerComplementBeforeProvidentNet!=null){
                                append(" • complément final à confirmer (prévoyance chevauchante non confirmée)")
                            }
                        })
                    }
                }.toMutableList()
                if(relay?.potentiallyCovered==true){
                    lines += buildString{
                        append("Prévoyance Plasturgie : relais de branche potentiel à au moins 60 % du brut, après le maintien employeur")
                        relay.earliestContinuousStopDay?.let{append(" • à partir du ").append(it).append("e jour d'arrêt continu")}
                        if(!relay.eligibilityConfirmed)append(" • catégorie ANI 2.1/2.2 à confirmer")
                    }
                }
                lines
            }
    }

    private fun counter(b:Balance,suffix:String,label:String,value:Double)=
        CounterV2("${b.id}:$suffix",label,value,b.unit,b.referenceStartMs,b.referenceEndMs)

    private fun save(context:Context,balances:List<Balance>){
        val a=JSONArray()
        balances.forEach{b->a.put(JSONObject()
            .put("id",b.id)
            .put("label",b.label)
            .put("acquired",b.acquired?:JSONObject.NULL)
            .put("available",b.available?:JSONObject.NULL)
            .put("taken",b.taken?:JSONObject.NULL)
            .put("anticipated",b.anticipated?:JSONObject.NULL)
            .put("remaining",b.remaining?:JSONObject.NULL)
            .put("unit",b.unit)
            .put("referenceStartMs",b.referenceStartMs)
            .put("referenceEndMs",b.referenceEndMs)
            .put("source",b.source)
            .put("companyId",b.companyId))}
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit().putString(KEY_COUNTERS,a.toString()).apply()
    }

    private fun saveAbsences(context:Context,absences:List<AbsenceV2>){
        val a=JSONArray()
        absences.sortedBy { it.startMs }.forEach { absence ->
            a.put(JSONObject()
                .put("id",absence.id)
                .put("employerId",absence.employerId?:JSONObject.NULL)
                .put("type",absence.type)
                .put("startMs",absence.startMs)
                .put("endMs",absence.endMs)
                .put("salaryTreatment",absence.salaryTreatment.name)
                .put("fullDay",absence.fullDay)
                .put("status",absence.status.name)
                .put("subrogation",absence.subrogation.name)
                .put("providentTreatment",absence.providentTreatment.name)
                .put("employerProvidentOverlapNetAmount",absence.employerProvidentOverlapNetAmount?:JSONObject.NULL))
        }
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit().putString(KEY_ABSENCES,a.toString()).apply()
    }

    private fun decode(raw:String):List<Balance> = runCatching{
        val a=JSONArray(raw.ifBlank{"[]"})
        buildList{
            for(i in 0 until a.length()){
                val o=a.optJSONObject(i)?:continue
                val id=o.optString("id").trim()
                if(id.isBlank())continue
                add(Balance(
                    id,
                    o.optString("label",id),
                    nullableDouble(o,"acquired"),
                    nullableDouble(o,"available"),
                    nullableDouble(o,"taken"),
                    nullableDouble(o,"anticipated"),
                    nullableDouble(o,"remaining"),
                    o.optString("unit","jours"),
                    o.optLong("referenceStartMs",0L),
                    o.optLong("referenceEndMs",Long.MAX_VALUE),
                    o.optString("source","MANUAL"),
                    o.optString("companyId")
                ))
            }
        }
    }.getOrElse{emptyList()}

    private fun decodeAbsences(raw:String):List<AbsenceV2> = runCatching{
        val a=JSONArray(raw.ifBlank{"[]"})
        buildList{
            for(i in 0 until a.length()){
                val o=a.optJSONObject(i)?:continue
                val id=o.optString("id").trim()
                val employerId=o.optString("employerId").trim().takeIf { it.isNotBlank() && it!="null" }
                val start=o.optLong("startMs",-1L)
                val end=o.optLong("endMs",-1L)
                if(id.isBlank()||employerId==null||start<0L||end<=start)continue
                val treatment=runCatching{
                    AbsenceSalaryTreatmentV2.valueOf(o.optString("salaryTreatment",AbsenceSalaryTreatmentV2.TO_CONFIRM.name))
                }.getOrDefault(AbsenceSalaryTreatmentV2.TO_CONFIRM)
                val status=runCatching{
                    DecisionStatusV2.valueOf(o.optString("status",DecisionStatusV2.CONFIRMED.name))
                }.getOrDefault(DecisionStatusV2.TO_CONFIRM)
                val subrogation=runCatching{
                    AbsenceSubrogationV2.valueOf(o.optString("subrogation",AbsenceSubrogationV2.TO_CONFIRM.name))
                }.getOrDefault(AbsenceSubrogationV2.TO_CONFIRM)
                val provident=runCatching{
                    AbsenceProvidentTreatmentV2.valueOf(o.optString("providentTreatment",AbsenceProvidentTreatmentV2.TO_CONFIRM.name))
                }.getOrDefault(AbsenceProvidentTreatmentV2.TO_CONFIRM)
                val providentAmount=nullableDouble(o,"employerProvidentOverlapNetAmount")?.takeIf{it>=0.0}
                add(AbsenceV2(
                    id=id,
                    employerId=employerId,
                    type=o.optString("type","ABSENCE"),
                    startMs=start,
                    endMs=end,
                    salaryTreatment=treatment,
                    fullDay=o.optBoolean("fullDay",false),
                    status=status,
                    subrogation=subrogation,
                    providentTreatment=provident,
                    employerProvidentOverlapNetAmount=if(provident==AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED)providentAmount else null
                ))
            }
        }
    }.getOrElse{emptyList()}

    private fun nullableDouble(o:JSONObject,key:String):Double?=
        if(!o.has(key)||o.isNull(key))null else o.optDouble(key).takeUnless{it.isNaN()}
}
