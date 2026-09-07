package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerUnemploymentAgsV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage chômage/AGS employeur, isolé et sauvegardé avec chaque entreprise Salaire V2. */
object CompanyUnemploymentAgsStoreV2 {
    private const val KEY = "employer_unemployment_ags_v2"

    fun list(context: Context, companyId: String): List<EmployerUnemploymentAgsV2.Record> {
        if (companyId.isBlank()) return emptyList()
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) fromJson(array.optJSONObject(i))?.let(::add)
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, companyId: String, record: EmployerUnemploymentAgsV2.Record): Boolean {
        if (companyId.isBlank()) return false
        val items = list(context, companyId).toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) items[index] = record else items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean =
        write(context, companyId, list(context, companyId).filterNot { it.id == id })

    fun resolve(context: Context, companyId: String, period: YearMonth): EmployerUnemploymentAgsV2.Snapshot =
        EmployerUnemploymentAgsV2.resolve(list(context, companyId), period)

    private fun write(context: Context, companyId: String, items: List<EmployerUnemploymentAgsV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId).edit().putString(KEY, array.toString()).commit()
    }

    private fun toJson(record: EmployerUnemploymentAgsV2.Record) = JSONObject()
        .put("id", record.id)
        .put("unemploymentRate", record.unemploymentRate)
        .put("agsRate", record.agsRate)
        .put("effectiveFrom", record.effectiveFrom.toString())
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source)

    private fun fromJson(o: JSONObject?): EmployerUnemploymentAgsV2.Record? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val unemploymentRate = (o.opt("unemploymentRate") as? Number)?.toDouble() ?: return null
        val agsRate = (o.opt("agsRate") as? Number)?.toDouble() ?: return null
        val from = o.optString("effectiveFrom").takeIf { it.isNotBlank() }?.let {
            runCatching { YearMonth.parse(it) }.getOrNull()
        } ?: return null
        val to = o.optString("effectiveTo").takeIf { it.isNotBlank() && it != "null" }?.let {
            runCatching { YearMonth.parse(it) }.getOrNull()
        }
        val source = o.optString("source")
        return EmployerUnemploymentAgsV2.Record(id, unemploymentRate, agsRate, from, to, source)
    }
}
