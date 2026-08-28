package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.EmployerV2
import java.util.Calendar
import java.util.Locale

object V2ProfileStore {
 private const val PREFS="salary_settings";@Volatile private var boundContext:Context?=null
 data class Profile(val employer:EmployerV2?,val contract:ContractV2?,val companySlot:Int,val missing:List<String>)
 fun bind(context:Context){boundContext=context.applicationContext};fun loadBound(companySlot:Int=1):Profile?=boundContext?.let{load(it,companySlot)}
 fun load(context:Context,companySlot:Int=1):Profile{bind(context);val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE);fun text(key:String)=when(val v=prefs.all[key]){null->"";is String->v;is Number->v.toString();is Boolean->v.toString();else->v.toString()}.trim();val prefix=if(companySlot==2)"company2_" else "company_";val name=text(prefix+"name");val siret=text(prefix+"siret").filter(Char::isDigit);val idcc=text(prefix+"idcc").ifBlank{if(companySlot==1)text("convention_idcc") else ""};val employerId="company_$companySlot";val employer=if(name.isNotBlank()||siret.isNotBlank())EmployerV2(employerId,name.ifBlank{"Entreprise $companySlot"},siret.takeIf{it.length==14},idcc.takeIf{it.isNotBlank()})else null;val type=parseContractType(if(companySlot==1)text("contract_type")else text("company2_contract_type"));val weeklyRaw=if(companySlot==1)text("contract_weekly_hours")else text("company2_contract_weekly_hours");val rateRaw=if(companySlot==1)text("hourly_rate")else text("company2_hourly_rate");val weeklyMinutes=weeklyRaw.replace(',','.').toDoubleOrNull()?.takeIf{it>0.0}?.let{(it*60.0).toInt()};val rate=rateRaw.replace(',','.').toDoubleOrNull()?.takeIf{it>0.0};val hireDateMs=safeLong(prefs.all[if(companySlot==1)"employment_start_date" else "company2_employment_start_date"]);val missing=mutableListOf<String>();if(employer==null)missing+="employeur";if(type==null)missing+="type de contrat";if(weeklyMinutes==null&&type!=ContractTypeV2.FORFAIT)missing+="durée hebdomadaire";if(rate==null)missing+="taux horaire";val contract=if(employer!=null&&type!=null&&(weeklyMinutes!=null||type==ContractTypeV2.FORFAIT)&&rate!=null)ContractV2("contract_$companySlot",employerId,type,weeklyMinutes,rate,hireDateMs?.takeIf{it>0}?.let(::localEpochDay))else null;return Profile(employer,contract,companySlot,missing)}
 fun primaryEmployerId(context:Context)=load(context,1).employer?.id
 fun activeCompanySlot(context:Context):Int{bind(context);val p=context.applicationContext.getSharedPreferences("horatrack_v2_integration",Context.MODE_PRIVATE);return safeLong(p.all["active_company_slot"])?.toInt()?.coerceIn(1,2)?:1}
 fun setActiveCompanySlot(context:Context,slot:Int){bind(context);context.applicationContext.getSharedPreferences("horatrack_v2_integration",Context.MODE_PRIVATE).edit().putInt("active_company_slot",slot.coerceIn(1,2)).apply()}
 private fun parseContractType(value:String)=when(value.trim().uppercase(Locale.ROOT)){"FULL_TIME"->ContractTypeV2.FULL_TIME;"PART_TIME"->ContractTypeV2.PART_TIME;"FORFAIT"->ContractTypeV2.FORFAIT;"OTHER"->ContractTypeV2.OTHER;else->null}
 private fun safeLong(value:Any?):Long?=when(value){is Number->value.toLong();is String->value.toLongOrNull();else->null}
 private fun localEpochDay(ms:Long):Long{val c=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=ms;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};return Math.floorDiv(c.timeInMillis+c.timeZone.getOffset(c.timeInMillis).toLong(),86_400_000L)}
}
