package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stockage V2 multi-entreprises, avec écriture synchrone et vérifiable. */
object SalaryCompanyStore {
    private const val PREFS = "salary_companies_v2"
    private const val KEY = "companies"

    data class Company(
        val id: String,
        val name: String,
        val siret: String,
        val address: String = "",
        val conventionName: String = "",
        val idcc: String = ""
    )

    fun list(context: Context): List<Company> {
        migrateLegacy(context)
        return read(context)
    }

    /** Retourne true uniquement si l'entreprise est relue après écriture. */
    fun upsert(context: Context, company: Company): Boolean {
        migrateLegacy(context)
        val all = read(context).toMutableList()
        val index = all.indexOfFirst {
            it.id == company.id || (company.siret.isNotBlank() && it.siret == company.siret)
        }
        if (index >= 0) all[index] = company else all += company
        if (!save(context, all)) return false
        return read(context).any {
            it.id == company.id || (company.siret.isNotBlank() && it.siret == company.siret)
        }
    }

    fun remove(context: Context, id: String): Boolean {
        migrateLegacy(context)
        return save(context, read(context).filterNot { it.id == id })
    }

    fun prefs(context: Context, companyId: String) =
        context.getSharedPreferences(
            "salary_company_${companyId.replace(Regex("[^A-Za-z0-9_-]"), "_")}",
            Context.MODE_PRIVATE
        )

    private fun read(context: Context): List<Company> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Company(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    siret = o.optString("siret"),
                    address = o.optString("address"),
                    conventionName = o.optString("conventionName"),
                    idcc = o.optString("idcc")
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, companies: List<Company>): Boolean {
        val array = JSONArray()
        companies.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("siret", c.siret)
                put("address", c.address)
                put("conventionName", c.conventionName)
                put("idcc", c.idcc)
            })
        }
        // commit() est volontaire : l'UI ne doit jamais annoncer un succès avant
        // que la donnée soit réellement écrite et relisible.
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun migrateLegacy(context: Context) {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (store.contains(KEY)) return

        val old = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val migrated = mutableListOf<Company>()

        fun add(nameKey: String, siretKey: String, suffix: String) {
            val name = old.getString(nameKey, "").orEmpty().trim()
            val siret = old.getString(siretKey, "").orEmpty().trim()
            if (name.isBlank() && siret.isBlank()) return
            val id = if (siret.isNotBlank()) "siret_$siret" else "legacy_$suffix"
            migrated += Company(
                id = id,
                name = name,
                siret = siret,
                conventionName = old.getString("company_convention_name", "").orEmpty(),
                idcc = old.getString("company_idcc", "").orEmpty()
            )
            prefs(context, id).edit()
                .putString("contract_type", old.getString("contract_type", ""))
                .putString("hourly_rate", old.getString("hourly_rate", ""))
                .putString("contract_weekly_hours", old.getString("contract_weekly_hours", ""))
                .putString("meal_amount", old.getString("meal_amount", ""))
                .putString("convention_coefficient", old.getString("convention_coefficient", ""))
                .commit()
        }

        add("company_name", "company_siret", "1")
        add("company2_name", "company2_siret", "2")
        save(context, migrated)
    }
}
