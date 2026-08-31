package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore

/** Couche 4/6 — paramètres salarié/entreprise. Les valeurs absentes restent explicitement inconnues. */
object CompanyPayrollOverridesV2 {
    data class Snapshot(
        val companyId:String,
        val mealAmount:Double?,
        val mutualEmployeeAmount:Double?,
        val providentEmployeeAmount:Double?,
        val transportEmployeeAmount:Double?,
        val incomeTaxRate:Double?,
        val warnings:List<String>
    )
    fun load(context:Context,companyId:String):Snapshot {
        val p=SalaryCompanyStore.prefs(context,companyId)
        fun number(key:String)=p.getString(key,"").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>=0.0}
        val mutual=number("mutual_employee_amount")
        val provident=number("provident_employee_amount")
        val transport=number("transport_employee_amount")
        val tax=number("income_tax_rate_percent")?.div(100.0)
        val warnings=buildList {
            if(mutual==null)add("Mutuelle salariale : à confirmer")
            if(provident==null)add("Prévoyance salariale : à confirmer")
            if(transport==null)add("Retenue transport : à confirmer")
            if(tax==null)add("Taux de prélèvement à la source : à confirmer")
        }
        return Snapshot(companyId,number("meal_amount"),mutual,provident,transport,tax,warnings)
    }
}
