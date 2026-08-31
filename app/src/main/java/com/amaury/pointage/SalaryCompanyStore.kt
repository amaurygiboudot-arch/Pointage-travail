package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val index = all.indexOfFirst { it.id == company.id || (company.siret.isNotBlank() && it.siret == company.siret) }
        if (index >= 0) all[index] = company else all += company
        if (!save(context, all)) return false
        return read(context).any { it.id == company.id || (company.siret.isNotBlank() && it.siret == company.siret) }
    }

    fun remove(context: Context, id: String): Boolean {
        migrateLegacy(context)
        return save(context, read(context).filterNot { it.id == id })
    }

    fun prefs(context: Context, companyId: String) = context.getSharedPreferences(
        "salary_company_${companyId.replace(Regex("[^A-Za-z0-9_-]"), "_")}", Context.MODE_PRIVATE
    )

    /**
     * Identifiants employeur acceptés pour relire les anciennes sessions sans dépendre
     * de la position actuelle de l'entreprise dans MES ENTREPRISES.
     */
    fun acceptedEmployerIds(context: Context, companyId: String): Set<String> {
        val company = list(context).firstOrNull { it.id == companyId } ?: return setOf(companyId)
        val ids = linkedSetOf(company.id)
        val old = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)

        fun value(key: String): String = when (val v = old.all[key]) {
            null -> ""
            is String -> v
            is Number -> v.toString()
            else -> v.toString()
        }.trim()

        fun matches(slot: Int): Boolean {
            val prefix = if (slot == 1) "company_" else "company2_"
            val oldSiret = value("${prefix}siret").filter(Char::isDigit)
            val oldName = value("${prefix}name")
            val currentSiret = company.siret.filter(Char::isDigit)
            return when {
                currentSiret.isNotBlank() && oldSiret.isNotBlank() -> currentSiret == oldSiret
                company.name.isNotBlank() && oldName.isNotBlank() -> company.name.equals(oldName, ignoreCase = true)
                else -> false
            }
        }

        if (matches(1)) ids += "company_1"
        if (matches(2)) ids += "company_2"
        return ids
    }

    private fun read(context: Context): List<Company> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Company(o.optString("id"),o.optString("name"),o.optString("siret"),o.optString("address"),o.optString("conventionName"),o.optString("idcc"))
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, companies: List<Company>): Boolean {
        val array = JSONArray()
        companies.forEach { c -> array.put(JSONObject().apply { put("id",c.id);put("name",c.name);put("siret",c.siret);put("address",c.address);put("conventionName",c.conventionName);put("idcc",c.idcc) }) }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit()
    }

    private fun migrateLegacy(context: Context) {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (store.contains(KEY)) return
        val old = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val migrated = mutableListOf<Company>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

        fun value(key: String): String = when (val v = old.all[key]) { null -> ""; is String -> v; is Number -> v.toString(); else -> v.toString() }.trim()
        fun migrateSlot(slot: Int) {
            val p = if (slot == 1) "company_" else "company2_"
            val name = value("${p}name")
            val siret = value("${p}siret").filter(Char::isDigit)
            if (name.isBlank() && siret.isBlank()) return
            val id = if (siret.isNotBlank()) "siret_$siret" else "legacy_$slot"
            val idcc = value("${p}idcc").ifBlank { if (slot == 1) value("convention_idcc") else "" }
            val conventionName = value("${p}convention_name")
            val address = value("${p}address")
            migrated += Company(id,name,siret,address,conventionName,idcc)

            val contractType = if (slot == 1) value("contract_type") else value("company2_contract_type")
            val rate = if (slot == 1) value("hourly_rate") else value("company2_hourly_rate")
            val weekly = if (slot == 1) value("contract_weekly_hours") else value("company2_contract_weekly_hours")
            val coefficient = if (slot == 1) value("convention_coefficient") else value("company2_convention_coefficient")
            val meal = if (slot == 1) value("meal_amount") else value("company2_meal_amount").ifBlank { value("meal_amount") }
            val hireMsKey = if (slot == 1) "employment_start_date" else "company2_employment_start_date"
            val hireMs = when (val raw = old.all[hireMsKey]) { is Number -> raw.toLong(); is String -> raw.toLongOrNull() ?: 0L; else -> 0L }
            val entryDate = if (hireMs > 0L) dateFormat.format(Date(hireMs)) else value(if (slot == 1) "entry_date" else "company2_entry_date")

            val editor = prefs(context, id).edit()
                .putString("contract_type", contractType)
                .putString("hourly_rate", rate)
                .putString("contract_weekly_hours", weekly)
                .putString("meal_amount", meal)
                .putString("convention_coefficient", coefficient)
                .putString("entry_date", entryDate)
                .putString("company_idcc", idcc)
            editor.commit()
        }

        migrateSlot(1)
        migrateSlot(2)
        save(context, migrated)
    }
}
