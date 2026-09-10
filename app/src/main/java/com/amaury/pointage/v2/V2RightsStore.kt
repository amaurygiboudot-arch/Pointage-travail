package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.RightsEngineV2
import com.amaury.pointage.v2.engine.RightsSnapshotV2
import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSourceStateV2
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
    private const val ABSENCE_STORAGE_WARNING =
        "Absences : stockage local illisible ou incohérent ; les données doivent être vérifiées avant tout calcul de paie."

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

    data class AbsenceReadResult(
        val absences:List<AbsenceV2>,
        val reliable:Boolean,
        val warnings:List<String>
    )

    private data class OptionalNumber(val valid:Boolean,val value:Double?)

    private class StoredAbsenceList(
        private val values:List<AbsenceV2>,
        override val absenceSourceReliable:Boolean,
        override val absenceSourceWarnings:List<String>
    ) : AbstractList<AbsenceV2>(), AbsenceSourceStateV2 {
        override val size:Int get()=values.size
        override fun get(index:Int):AbsenceV2=values[index]
    }

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

    /**
     * Renvoie toujours une List pour préserver l'API historique, mais la liste porte aussi l'état
     * de fiabilité du stockage via AbsenceSourceStateV2. Le moteur de paie peut ainsi distinguer
     * une liste réellement vide d'un fichier d'absences corrompu.
     */
    fun absences(context:Context):List<AbsenceV2> = asAbsenceList(readAbsences(context))

    fun absencesForCompany(context:Context,companyId:String):List<AbsenceV2> {
        val stored=readAbsences(context)
        return asAbsenceList(stored.copy(absences=stored.absences.filter { it.employerId == companyId }))
    }

    internal fun readAbsences(context:Context):AbsenceReadResult {
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(!prefs.contains(KEY_ABSENCES)) return AbsenceReadResult(emptyList(),true,emptyList())
        val raw=runCatching { prefs.getString(KEY_ABSENCES,null) }.getOrNull()
            ?:return corruptAbsenceResult()
        return decodeAbsences(raw)
    }

    internal fun asAbsenceList(result:AbsenceReadResult):List<AbsenceV2> = StoredAbsenceList(
        values=result.absences,
        absenceSourceReliable=result.reliable,
        absenceSourceWarnings=result.warnings.distinct()
    )

    fun upsertAbsence(context:Context,absence:AbsenceV2):Boolean{
        validateAbsenceForWrite(absence)
        val stored=readAbsences(context)
        if(!stored.reliable)return false
        val list=stored.absences.toMutableList()
        val index=list.indexOfFirst{it.id==absence.id}
        if(index>=0)list[index]=absence else list+=absence
        return saveAbsences(context,list)
    }

    fun removeAbsence(context:Context,id:String):Boolean{
        if(id.isBlank())return false
        val stored=readAbsences(context)
        if(!stored.reliable)return false
        val kept=stored.absences.filterNot { it.id==id }
        return saveAbsences(context,kept)
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
        val storedAbsences=absencesForCompany(context,companyId)
        val sourceState=storedAbsences as? AbsenceSourceStateV2
        if(sourceState?.absenceSourceReliable==false){
            return (sourceState.absenceSourceWarnings+ABSENCE_STORAGE_WARNING).distinct()
        }
        return storedAbsences
            .filter{it.type==AbsencePayrollImpactV2.TYPE_SICKNESS}
            .filter{Instant.ofEpochMilli(it.startMs).atZone(zone).year==currentYear}
            .sortedByDescending{it.startMs}
            .flatMap{absence->
                val result=V2PayslipStore.sicknessMaintenanceForAbsence(context,companyId,absence)
                    ?:return@flatMap emptyList()
                if(!result.applicable)return@flatMap emptyList()
                val amount=V2PayslipStore.sicknessTheoreticalNetForAbsence(context,companyId,absence)
                val relay=V2PayslipStore.sicknessProvidentRelayForAbsence(context,companyId,absence)
                val relayControl=V2PayslipStore.sicknessProvidentRelayControlForAbsence(context,companyId,absence)
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
                        append("Prévoyance Plasturgie : relais de branche à au moins 60 % du brut, après le maintien employeur")
                        relay.earliestContinuousStopDay?.let{append(" • à partir du ").append(it).append("e jour d'arrêt continu")}
                        relay.relayReached?.let{append(if(it)" • relais atteint" else " • relais non encore atteint")}
                    }
                }
                if(relayControl?.complete==true){
                    lines += buildString{
                        append("Contrôle prévoyance : minimum ")
                            .append(String.format(Locale.FRANCE,"%.2f € brut",relayControl.expectedMinimumProvidentGross))
                        append(" • observé ")
                            .append(String.format(Locale.FRANCE,"%.2f € brut",relayControl.observedProvidentGross))
                        relayControl.differenceGross?.let{append(" • écart ").append(String.format(Locale.FRANCE,"%+.2f €",it))}
                        append(if(relayControl.meetsBranchMinimum==true)" • minimum de branche respecté" else " • écart à vérifier")
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

    private fun saveAbsences(context:Context,absences:List<AbsenceV2>):Boolean{
        if(absences.groupingBy{it.id}.eachCount().any{it.value>1})return false
        absences.forEach(::validateAbsenceForWrite)
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
                .put("employerProvidentOverlapNetAmount",absence.employerProvidentOverlapNetAmount?:JSONObject.NULL)
                .put("providentRelayTargetGross60Amount",absence.providentRelayTargetGross60Amount?:JSONObject.NULL)
                .put("providentRelaySocialSecurityGrossAmount",absence.providentRelaySocialSecurityGrossAmount?:JSONObject.NULL)
                .put("providentRelayObservedGrossAmount",absence.providentRelayObservedGrossAmount?:JSONObject.NULL))
        }
        return runCatching{
            context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit().putString(KEY_ABSENCES,a.toString()).commit()
        }.getOrDefault(false)
    }

    private fun validateAbsenceForWrite(absence:AbsenceV2){
        require(absence.id.isNotBlank()){"Identifiant absence manquant"}
        require(absence.employerId?.isNotBlank()==true){"Entreprise de l'absence manquante"}
        require(absence.type.isNotBlank()){"Type d'absence manquant"}
        require(absence.startMs>=0L&&absence.endMs>absence.startMs){"Période d'absence invalide"}
        when(absence.providentTreatment){
            AbsenceProvidentTreatmentV2.TO_CONFIRM ->
                require(absence.employerProvidentOverlapNetAmount==null){"Prévoyance à confirmer : aucun montant ne doit être figé"}
            AbsenceProvidentTreatmentV2.NONE_CONFIRMED ->
                require(absence.employerProvidentOverlapNetAmount==null||absence.employerProvidentOverlapNetAmount==0.0){"Aucune prévoyance chevauchante : montant incohérent"}
            AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED ->
                require(absence.employerProvidentOverlapNetAmount?.let{it.isFinite()&&it>=0.0}==true){"Montant net de prévoyance chevauchante manquant ou invalide"}
        }
        val relayValues=listOf(
            absence.providentRelayTargetGross60Amount,
            absence.providentRelaySocialSecurityGrossAmount,
            absence.providentRelayObservedGrossAmount
        )
        val relayAny=relayValues.any{it!=null}
        val relayAll=relayValues.all{it!=null}
        require(!relayAny||relayAll){"Contrôle relais prévoyance incomplet : les trois montants doivent être renseignés ensemble"}
        if(relayAll)require(relayValues.all{it!!.isFinite()&&it>=0.0}){"Contrôle relais prévoyance : montant invalide"}
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

    internal fun decodeAbsences(raw:String):AbsenceReadResult {
        if(raw.isBlank())return corruptAbsenceResult()
        return runCatching{
            val a=JSONArray(raw)
            var malformed=false
            val parsed=buildList{
                for(i in 0 until a.length()){
                    val o=a.optJSONObject(i)
                    if(o==null){
                        malformed=true
                        continue
                    }
                    val absence=parseStoredAbsence(o)
                    if(absence==null){
                        malformed=true
                    }else{
                        add(absence)
                    }
                }
            }
            if(parsed.groupingBy{it.id}.eachCount().any{it.value>1})malformed=true
            AbsenceReadResult(
                absences=parsed,
                reliable=!malformed,
                warnings=if(malformed)listOf(ABSENCE_STORAGE_WARNING)else emptyList()
            )
        }.getOrElse{corruptAbsenceResult()}
    }

    private fun parseStoredAbsence(o:JSONObject):AbsenceV2?{
        val id=requiredString(o,"id")?:return null
        val employerId=requiredString(o,"employerId")?:return null
        val type=requiredString(o,"type")?:return null
        val start=requiredLong(o,"startMs")?.takeIf{it>=0L}?:return null
        val end=requiredLong(o,"endMs")?.takeIf{it>start}?:return null
        val treatment=requiredEnum<AbsenceSalaryTreatmentV2>(o,"salaryTreatment")?:return null
        val fullDay=requiredBoolean(o,"fullDay")?:return null
        val status=requiredEnum<DecisionStatusV2>(o,"status")?:return null
        val subrogation=requiredEnum<AbsenceSubrogationV2>(o,"subrogation")?:return null
        val provident=requiredEnum<AbsenceProvidentTreatmentV2>(o,"providentTreatment")?:return null
        val providentAmount=optionalNonNegativeDouble(o,"employerProvidentOverlapNetAmount")
        val relayTarget=optionalNonNegativeDouble(o,"providentRelayTargetGross60Amount")
        val relaySs=optionalNonNegativeDouble(o,"providentRelaySocialSecurityGrossAmount")
        val relayObserved=optionalNonNegativeDouble(o,"providentRelayObservedGrossAmount")
        if(listOf(providentAmount,relayTarget,relaySs,relayObserved).any{!it.valid})return null

        when(provident){
            AbsenceProvidentTreatmentV2.TO_CONFIRM -> if(providentAmount.value!=null)return null
            AbsenceProvidentTreatmentV2.NONE_CONFIRMED -> if(providentAmount.value!=null&&providentAmount.value!=0.0)return null
            AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED -> if(providentAmount.value==null)return null
        }
        val relayValues=listOf(relayTarget.value,relaySs.value,relayObserved.value)
        val relayCount=relayValues.count{it!=null}
        if(relayCount!=0&&relayCount!=3)return null
        val relayComplete=relayCount==3

        return AbsenceV2(
            id=id,
            employerId=employerId,
            type=type,
            startMs=start,
            endMs=end,
            salaryTreatment=treatment,
            fullDay=fullDay,
            status=status,
            subrogation=subrogation,
            providentTreatment=provident,
            employerProvidentOverlapNetAmount=if(provident==AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED)providentAmount.value else null,
            providentRelayTargetGross60Amount=relayTarget.value.takeIf{relayComplete},
            providentRelaySocialSecurityGrossAmount=relaySs.value.takeIf{relayComplete},
            providentRelayObservedGrossAmount=relayObserved.value.takeIf{relayComplete}
        )
    }

    private fun corruptAbsenceResult()=AbsenceReadResult(emptyList(),false,listOf(ABSENCE_STORAGE_WARNING))

    private fun requiredString(o:JSONObject,key:String):String?{
        if(!o.has(key)||o.isNull(key))return null
        return (o.opt(key) as? String)?.trim()?.takeIf{it.isNotBlank()&&it!="null"}
    }

    private fun requiredLong(o:JSONObject,key:String):Long?{
        if(!o.has(key)||o.isNull(key))return null
        val number=o.opt(key) as? Number?:return null
        val value=number.toDouble()
        if(!value.isFinite()||value%1.0!=0.0)return null
        return number.toLong()
    }

    private fun requiredBoolean(o:JSONObject,key:String):Boolean?{
        if(!o.has(key)||o.isNull(key))return null
        return o.opt(key) as? Boolean
    }

    private inline fun <reified T:Enum<T>> requiredEnum(o:JSONObject,key:String):T?{
        val value=requiredString(o,key)?:return null
        return enumValues<T>().firstOrNull{it.name==value}
    }

    private fun optionalNonNegativeDouble(o:JSONObject,key:String):OptionalNumber{
        if(!o.has(key)||o.isNull(key))return OptionalNumber(true,null)
        val number=o.opt(key) as? Number?:return OptionalNumber(false,null)
        val value=number.toDouble()
        return if(value.isFinite()&&value>=0.0)OptionalNumber(true,value) else OptionalNumber(false,null)
    }

    private fun nullableDouble(o:JSONObject,key:String):Double?=
        if(!o.has(key)||o.isNull(key))null else o.optDouble(key).takeUnless{it.isNaN()}
}
