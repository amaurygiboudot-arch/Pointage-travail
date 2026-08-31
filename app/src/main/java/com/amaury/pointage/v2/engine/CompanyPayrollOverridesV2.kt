package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Couche 4/6 — paramètres salarié/entreprise. Les valeurs absentes restent explicitement inconnues. */
object CompanyPayrollOverridesV2 {
    data class Snapshot(
        val companyId:String,
        val idcc:String?,
        val entryDate:LocalDate?,
        val seniorityMonths:Int?,
        val mealAmount:Double?,
        val mutualEmployeeAmount:Double?,
        val providentEmployeeAmount:Double?,
        val transportEmployeeAmount:Double?,
        val incomeTaxRate:Double?,
        val professionalStatus:String?,
        val warnings:List<String>
    )

    /** referenceDate doit appartenir à la période de paie concernée. */
    fun load(context:Context,companyId:String,referenceDate:LocalDate=LocalDate.now()):Snapshot {
        val p=SalaryCompanyStore.prefs(context,companyId)
        fun number(key:String)=p.getString(key,"").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>=0.0}
        val company=SalaryCompanyStore.list(context).firstOrNull{it.id==companyId}
        val idcc=company?.idcc?.ifBlank{null}
            ?:p.getString("company_idcc","").orEmpty().filter(Char::isDigit).trimStart('0').ifBlank{null}
        val entryDate=runCatching {
            p.getString("entry_date","").orEmpty().trim().takeIf{it.isNotBlank()}?.let {
                LocalDate.parse(it,DateTimeFormatter.ofPattern("dd/MM/yyyy",Locale.FRANCE))
            }
        }.getOrNull()
        val seniorityMonths=entryDate?.let { start ->
            if(start.isAfter(referenceDate)) 0 else ChronoUnit.MONTHS.between(start,referenceDate).toInt().coerceAtLeast(0)
        }
        val mutual=number("mutual_employee_amount")
        val provident=number("provident_employee_amount")
        val transport=number("transport_employee_amount")
        val tax=number("income_tax_rate_percent")?.div(100.0)
        val professionalStatus=p.getString("professional_status","").orEmpty().trim().uppercase().takeIf{it=="CADRE"||it=="NON_CADRE"}
        val warnings=buildList {
            if(entryDate==null)add("Date d’entrée : à confirmer pour les règles liées à l’ancienneté")
            if(mutual==null)add("Mutuelle salariale : à confirmer")
            if(provident==null)add("Prévoyance salariale entreprise : à confirmer")
            if(transport==null)add("Retenue transport : à confirmer")
            if(tax==null)add("Taux de prélèvement à la source : à confirmer")
            if(professionalStatus==null)add("Statut professionnel cadre/non-cadre : à préciser")
        }
        return Snapshot(companyId,idcc,entryDate,seniorityMonths,number("meal_amount"),mutual,provident,transport,tax,professionalStatus,warnings)
    }
}
