package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
        /** Part employeur de protection sociale complémentaire réintégrée au net imposable. */
        val employerProtectionTaxableAmount:Double?,
        /** Part salariale de prévoyance explicitement non déductible fiscalement. */
        val employeeProvidentNonDeductibleAmount:Double?,
        val incomeTaxRate:Double?,
        val professionalStatus:String?,
        val warnings:List<String>
    )

    fun load(context:Context,companyId:String,referenceDate:LocalDate=selectedPayrollReferenceDate(context)):Snapshot {
        val p=SalaryCompanyStore.prefs(context,companyId)
        fun number(key:String)=p.getString(key,"").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>=0.0}
        fun normalizeIdcc(raw:String?)=raw.orEmpty().filter(Char::isDigit).trimStart('0').ifBlank{null}
        val company=SalaryCompanyStore.list(context).firstOrNull{it.id==companyId}
        val idcc=normalizeIdcc(company?.idcc) ?: normalizeIdcc(p.getString("company_idcc",""))
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
        val employerProtectionTaxable=number("employer_protection_taxable_amount")
        val employeeProvidentNonDeductible=number("employee_provident_nondeductible_amount")
        val tax=number("income_tax_rate_percent")?.div(100.0)
        val professionalStatus=p.getString("professional_status","").orEmpty().trim().uppercase().takeIf{it=="CADRE"||it=="NON_CADRE"}
        val warnings=buildList {
            if(entryDate==null)add("Date d’entrée : à confirmer pour les règles liées à l’ancienneté")
            if(mutual==null)add("Mutuelle salariale : à confirmer")
            if(provident==null)add("Prévoyance salariale entreprise : à confirmer")
            if(transport==null)add("Retenue transport : à confirmer")
            if(employerProtectionTaxable==null)add("Part employeur mutuelle/prévoyance réintégrable au net imposable : à confirmer")
            if(employeeProvidentNonDeductible==null)add("Part salariale de prévoyance non déductible : à confirmer, même si elle est nulle")
            if(tax==null)add("Taux de prélèvement à la source : à confirmer")
            if(professionalStatus==null)add("Statut professionnel cadre/non-cadre : à préciser")
        }
        return Snapshot(
            companyId=companyId,
            idcc=idcc,
            entryDate=entryDate,
            seniorityMonths=seniorityMonths,
            mealAmount=number("meal_amount"),
            mutualEmployeeAmount=mutual,
            providentEmployeeAmount=provident,
            transportEmployeeAmount=transport,
            employerProtectionTaxableAmount=employerProtectionTaxable,
            employeeProvidentNonDeductibleAmount=employeeProvidentNonDeductible,
            incomeTaxRate=tax,
            professionalStatus=professionalStatus,
            warnings=warnings
        )
    }

    /** Utilise la fin du mois actuellement sélectionné dans l'espace bulletin. */
    private fun selectedPayrollReferenceDate(context:Context):LocalDate {
        val ms=context.getSharedPreferences("navigation_state",Context.MODE_PRIVATE).getLong("report_month_ms",-1L)
        val selected=if(ms>0L) Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
        return selected.withDayOfMonth(selected.lengthOfMonth())
    }
}
