package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyEmployeeDeductionResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage local, daté et strictement séparé par entreprise des retenues de bulletin. */
object CompanyEmployeeDeductionStoreV2 {
    private const val KEY = "employee_deductions_v2"

    fun list(context: Context, companyId: String): List<CompanyEmployeeDeductionResolverV2.Record> {
        if (companyId.isBlank()) return emptyList()
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    fromJson(array.optJSONObject(i))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(
        context: Context,
        companyId: String,
        record: CompanyEmployeeDeductionResolverV2.Record
    ): Boolean {
        if (companyId.isBlank()) return false
        val items = list(context, companyId).toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) items[index] = record else items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean =
        write(context, companyId, list(context, companyId).filterNot { it.id == id })

    fun resolve(
        context: Context,
        companyId: String,
        period: YearMonth
    ): CompanyEmployeeDeductionResolverV2.Snapshot =
        CompanyEmployeeDeductionResolverV2.resolve(list(context, companyId), period)

    private fun write(
        context: Context,
        companyId: String,
        items: List<CompanyEmployeeDeductionResolverV2.Record>
    ): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: CompanyEmployeeDeductionResolverV2.Record) = JSONObject()
        .put("id", record.id)
        .put("kind", record.kind.name)
        .put("amount", record.amount)
        .put("effectiveFrom", record.effectiveFrom?.toString() ?: JSONObject.NULL)
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): CompanyEmployeeDeductionResolverV2.Record? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val kind = runCatching {
            CompanyEmployeeDeductionResolverV2.Kind.valueOf(o.optString("kind"))
        }.getOrNull() ?: return null
        val amount = (o.opt("amount") as? Number)?.toDouble() ?: return null
        fun month(key: String): YearMonth? = o.optString(key)
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        return CompanyEmployeeDeductionResolverV2.Record(
            id = id,
            kind = kind,
            amount = amount,
            effectiveFrom = month("effectiveFrom"),
            effectiveTo = month("effectiveTo"),
            source = o.optString("source").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
