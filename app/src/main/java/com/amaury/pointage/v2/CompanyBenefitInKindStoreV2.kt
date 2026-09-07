package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyBenefitInKindResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage des avantages en nature valorisés, dans le fichier Salaire V2 de l'entreprise. */
object CompanyBenefitInKindStoreV2 {
    private const val KEY = "benefits_in_kind_v2"

    fun list(context: Context, companyId: String): List<CompanyBenefitInKindResolverV2.Record> {
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

    fun save(context: Context, companyId: String, record: CompanyBenefitInKindResolverV2.Record): Boolean {
        if (companyId.isBlank()) return false
        val items = list(context, companyId).toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) items[index] = record else items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean =
        write(context, companyId, list(context, companyId).filterNot { it.id == id })

    fun resolve(context: Context, companyId: String, period: YearMonth): CompanyBenefitInKindResolverV2.Snapshot =
        CompanyBenefitInKindResolverV2.resolve(list(context, companyId), period)

    private fun write(context: Context, companyId: String, items: List<CompanyBenefitInKindResolverV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit().putString(KEY, array.toString()).commit()
    }

    private fun toJson(record: CompanyBenefitInKindResolverV2.Record) = JSONObject()
        .put("id", record.id)
        .put("label", record.label)
        .put("grossValue", record.grossValue)
        .put("kind", record.kind.name)
        .put("effectiveFrom", record.effectiveFrom?.toString() ?: JSONObject.NULL)
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("paymentMonth", record.paymentMonth?.toString() ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): CompanyBenefitInKindResolverV2.Record? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val label = o.optString("label")
        val value = (o.opt("grossValue") as? Number)?.toDouble() ?: return null
        val kind = runCatching { CompanyBenefitInKindResolverV2.Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
        fun month(key: String): YearMonth? = o.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let {
            runCatching { YearMonth.parse(it) }.getOrNull()
        }
        return CompanyBenefitInKindResolverV2.Record(
            id = id,
            label = label,
            grossValue = value,
            kind = kind,
            effectiveFrom = month("effectiveFrom"),
            effectiveTo = month("effectiveTo"),
            paymentMonth = month("paymentMonth")
        )
    }
}
