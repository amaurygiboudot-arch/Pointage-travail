package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerApprenticeshipTaxV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage de la taxe d'apprentissage, sauvegardé avec les données de l'entreprise Salaire V2. */
object CompanyApprenticeshipTaxStoreV2 {
    private const val KEY = "employer_apprenticeship_tax_v2"

    fun list(context: Context, companyId: String): List<EmployerApprenticeshipTaxV2.Record> {
        if (companyId.isBlank()) return emptyList()
        val raw = SalaryCompanyStore.prefs(context,companyId).getString(KEY,"[]").orEmpty()
        return runCatching {
            val array=JSONArray(raw)
            buildList { for(i in 0 until array.length()) fromJson(array.optJSONObject(i))?.let(::add) }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, companyId: String, record: EmployerApprenticeshipTaxV2.Record): Boolean {
        if(companyId.isBlank()) return false
        val items=list(context,companyId).toMutableList()
        val index=items.indexOfFirst { it.id==record.id }
        if(index>=0) items[index]=record else items+=record
        return write(context,companyId,items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean =
        write(context,companyId,list(context,companyId).filterNot { it.id==id })

    fun resolve(context: Context, companyId: String, period: YearMonth): EmployerApprenticeshipTaxV2.Snapshot =
        EmployerApprenticeshipTaxV2.resolve(list(context,companyId),period)

    private fun write(context: Context, companyId: String, items: List<EmployerApprenticeshipTaxV2.Record>): Boolean {
        val array=JSONArray();items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context,companyId).edit().putString(KEY,array.toString()).commit()
    }

    private fun toJson(r: EmployerApprenticeshipTaxV2.Record)=JSONObject()
        .put("id",r.id).put("principalRate",r.principalRate).put("balanceRate",r.balanceRate)
        .put("effectiveFrom",r.effectiveFrom.toString()).put("effectiveTo",r.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source",r.source)

    private fun fromJson(o: JSONObject?): EmployerApprenticeshipTaxV2.Record? {
        o ?: return null
        val id=o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val principal=(o.opt("principalRate") as? Number)?.toDouble() ?: return null
        val balance=(o.opt("balanceRate") as? Number)?.toDouble() ?: return null
        val from=o.optString("effectiveFrom").takeIf { it.isNotBlank() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return null
        val to=o.optString("effectiveTo").takeIf { it.isNotBlank() && it!="null" }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        return EmployerApprenticeshipTaxV2.Record(id,principal,balance,from,to,o.optString("source"))
    }
}
