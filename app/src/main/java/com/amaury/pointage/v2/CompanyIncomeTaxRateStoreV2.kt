package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyIncomeTaxRateResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage local et daté du taux personnel de prélèvement à la source pour une entreprise. */
object CompanyIncomeTaxRateStoreV2 {
    private const val KEY = "income_tax_rates_v2"

    fun list(context: Context, companyId: String): List<CompanyIncomeTaxRateResolverV2.Record> {
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

    fun save(context: Context, companyId: String, record: CompanyIncomeTaxRateResolverV2.Record): Boolean {
        if (companyId.isBlank()) return false
        val items = list(context, companyId).toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) items[index] = record else items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean =
        write(context, companyId, list(context, companyId).filterNot { it.id == id })

    fun resolve(context: Context, companyId: String, period: YearMonth): CompanyIncomeTaxRateResolverV2.Snapshot =
        CompanyIncomeTaxRateResolverV2.resolve(list(context, companyId), period)

    private fun write(context: Context, companyId: String, items: List<CompanyIncomeTaxRateResolverV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: CompanyIncomeTaxRateResolverV2.Record) = JSONObject()
        .put("id", record.id)
        .put("ratePercent", record.ratePercent)
        .put("effectiveFrom", record.effectiveFrom?.toString() ?: JSONObject.NULL)
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): CompanyIncomeTaxRateResolverV2.Record? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val ratePercent = (o.opt("ratePercent") as? Number)?.toDouble() ?: return null
        fun month(key: String): YearMonth? = o.optString(key)
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        return CompanyIncomeTaxRateResolverV2.Record(
            id = id,
            ratePercent = ratePercent,
            effectiveFrom = month("effectiveFrom"),
            effectiveTo = month("effectiveTo"),
            source = o.optString("source").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
