package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerReductionAdjustmentV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

object CompanyEmployerReductionStoreV2 {
    private const val KEY = "employer_reductions_v2"

    fun list(context: Context, companyId: String): List<EmployerReductionAdjustmentV2.Record> {
        if (companyId.isBlank()) return emptyList()
        val raw=SalaryCompanyStore.prefs(context,companyId).getString(KEY,"[]").orEmpty()
        return runCatching {
            val a=JSONArray(raw)
            buildList { for(i in 0 until a.length()) fromJson(a.optJSONObject(i))?.let(::add) }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, companyId: String, record: EmployerReductionAdjustmentV2.Record): Boolean {
        if(companyId.isBlank()) return false
        val items=list(context,companyId).filterNot { it.month==record.month }.toMutableList()
        items+=record
        return write(context,companyId,items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean =
        write(context,companyId,list(context,companyId).filterNot { it.id==id })

    fun resolve(context: Context, companyId: String, month: YearMonth): EmployerReductionAdjustmentV2.Snapshot =
        EmployerReductionAdjustmentV2.resolve(list(context,companyId),month)

    private fun write(context:Context,companyId:String,items:List<EmployerReductionAdjustmentV2.Record>):Boolean{
        val a=JSONArray();items.forEach{a.put(toJson(it))}
        return SalaryCompanyStore.prefs(context,companyId).edit().putString(KEY,a.toString()).commit()
    }

    private fun toJson(r:EmployerReductionAdjustmentV2.Record)=JSONObject()
        .put("id",r.id).put("month",r.month.toString()).put("amount",r.totalReductionAmount)
        .put("source",r.source).put("note",r.note)

    private fun fromJson(o:JSONObject?):EmployerReductionAdjustmentV2.Record?{
        o?:return null
        val id=o.optString("id").takeIf{it.isNotBlank()}?:return null
        val month=o.optString("month").let{runCatching{YearMonth.parse(it)}.getOrNull()}?:return null
        val amount=(o.opt("amount") as? Number)?.toDouble()?:return null
        return EmployerReductionAdjustmentV2.Record(id,month,amount,o.optString("source"),o.optString("note"))
    }
}
