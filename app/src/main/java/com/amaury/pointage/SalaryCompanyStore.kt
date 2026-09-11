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
    private const val KEY_LAST_KNOWN_GOOD = "companies_last_known_good"
    private const val KEY_CORRUPT_BACKUP = "companies_corrupt_backup"
    private const val STORAGE_WARNING =
        "Entreprises Salaire V2 : stockage local incohérent ; aucune entreprise ni absence d'entreprise ne peut être déduite de ce stockage."
    private const val REPAIRED_WARNING =
        "Entreprises Salaire V2 : stockage principal restauré depuis la dernière copie locale valide."

    data class Company(
        val id: String,
        val name: String,
        val siret: String,
        val address: String = "",
        val conventionName: String = "",
        val idcc: String = ""
    )

    data class ReadResult(
        val companies: List<Company>,
        val reliable: Boolean,
        val repairedFromBackup: Boolean = false,
        val warnings: List<String> = emptyList()
    )

    internal enum class StorageSource { PRIMARY, LAST_KNOWN_GOOD, NONE }

    internal data class StorageResolution(
        val result: ReadResult,
        val source: StorageSource
    )

    /**
     * Lit le store V2 sans jamais confondre corruption et liste vide.
     *
     * Une copie de secours est conservée à chaque écriture valide. Si le store principal devient
     * illisible ou disparaît et que cette copie est encore valide, elle est restaurée automatiquement.
     * La valeur corrompue est conservée séparément avant restauration afin de ne pas détruire une
     * éventuelle piste de récupération manuelle.
     */
    fun readConfirmed(context: Context): ReadResult {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!store.contains(KEY)) {
            val backupPresent = store.contains(KEY_LAST_KNOWN_GOOD)
            val backupRaw = runCatching { store.getString(KEY_LAST_KNOWN_GOOD, null) }.getOrNull()
            val backup = backupRaw?.let(::decodeCompanies)
            if (backup?.reliable == true) {
                val restored = runCatching {
                    store.edit().putString(KEY, backupRaw).commit()
                }.getOrDefault(false)
                return if (restored) {
                    backup.copy(repairedFromBackup = true, warnings = listOf(REPAIRED_WARNING))
                } else {
                    ReadResult(
                        companies = backup.companies,
                        reliable = false,
                        warnings = listOf(STORAGE_WARNING, "La copie valide a été trouvée mais sa restauration a échoué.")
                    )
                }
            }
            missingPrimaryBackupFailure(backupPresent, backup)?.let { return it }
            migrateLegacy(context)
        }

        if (!store.contains(KEY)) return ReadResult(emptyList(), true)

        val primaryValue = runCatching { store.all[KEY] }.getOrNull()
        val primaryRaw = primaryValue as? String
        val backupRaw = runCatching { store.getString(KEY_LAST_KNOWN_GOOD, null) }.getOrNull()
        val resolution = resolveStoredCompanies(primaryRaw, backupRaw)

        return when (resolution.source) {
            StorageSource.PRIMARY -> {
                // Actualise la dernière copie saine pour que toute corruption future soit réparable.
                if (primaryRaw != null && backupRaw != primaryRaw) {
                    runCatching {
                        store.edit().putString(KEY_LAST_KNOWN_GOOD, primaryRaw).commit()
                    }
                }
                resolution.result
            }
            StorageSource.LAST_KNOWN_GOOD -> {
                val repairedRaw = backupRaw ?: return resolution.result.copy(reliable = false)
                val corruptRaw = primaryValue?.toString()
                val editor = store.edit().putString(KEY, repairedRaw)
                if (!corruptRaw.isNullOrBlank()) editor.putString(KEY_CORRUPT_BACKUP, corruptRaw)
                val restored = runCatching { editor.commit() }.getOrDefault(false)
                if (restored) resolution.result
                else ReadResult(
                    companies = resolution.result.companies,
                    reliable = false,
                    warnings = listOf(STORAGE_WARNING, "La copie valide a été trouvée mais sa restauration a échoué.")
                )
            }
            StorageSource.NONE -> resolution.result
        }
    }

    fun list(context: Context): List<Company> {
        val stored = readConfirmed(context)
        check(stored.reliable) { stored.warnings.firstOrNull() ?: STORAGE_WARNING }
        return stored.companies
    }

    internal fun confirmedCompany(stored: ReadResult, companyId: String): Company? {
        val id = companyId.trim()
        if (!stored.reliable || id.isBlank()) return null
        return stored.companies.firstOrNull { it.id == id }
    }

    /**
     * Exécute une opération liée à une entreprise sous le même verrou que les mutations du store.
     * Une suppression ne peut donc pas se glisser entre la confirmation de l'entreprise et l'écriture.
     */
    @Synchronized
    fun <T> withConfirmedCompany(
        context: Context,
        companyId: String,
        block: (Company) -> T
    ): T? {
        val company = confirmedCompany(readConfirmed(context), companyId) ?: return null
        return block(company)
    }

    internal fun companiesAfterMutation(
        stored: ReadResult,
        company: Company,
        allowInsert: Boolean
    ): List<Company>? {
        if (!stored.reliable || company.id.isBlank()) return null
        val normalizedSiret = company.siret.filter(Char::isDigit)
        if (company.siret.isNotBlank() && normalizedSiret.length != 14) return null
        if (normalizedSiret.length == 14 && stored.companies.any {
                it.id != company.id && it.siret.filter(Char::isDigit) == normalizedSiret
            }) {
            return null
        }

        val all = stored.companies.toMutableList()
        val index = all.indexOfFirst { it.id == company.id }
        if (index >= 0) {
            all[index] = company
        } else if (allowInsert) {
            all += company
        } else {
            return null
        }
        return all
    }

    /**
     * Met à jour uniquement une entreprise qui existe encore sous son identifiant stable.
     * Cette méthode refuse volontairement toute insertion afin qu'un callback réseau ancien ne puisse
     * pas recréer une entreprise supprimée entre le départ et la réponse de la requête.
     */
    @Synchronized
    fun upsert(context: Context, company: Company): Boolean =
        persistCompanyMutation(context, company, allowInsert = false)

    /** Insertion explicite réservée au flux AJOUTER UNE ENTREPRISE. */
    @Synchronized
    fun createOrUpdate(context: Context, company: Company): Boolean =
        persistCompanyMutation(context, company, allowInsert = true)

    private fun persistCompanyMutation(
        context: Context,
        company: Company,
        allowInsert: Boolean
    ): Boolean {
        val all = companiesAfterMutation(readConfirmed(context), company, allowInsert) ?: return false
        if (!save(context, all)) return false
        return confirmedCompany(readConfirmed(context), company.id) != null
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val companyId = id.trim()
        if (companyId.isBlank()) return false
        val stored = readConfirmed(context)
        if (!stored.reliable) return false
        if (stored.companies.none { it.id == companyId }) return true
        if (!save(context, stored.companies.filterNot { it.id == companyId })) return false
        val reloaded = readConfirmed(context)
        return reloaded.reliable && reloaded.companies.none { it.id == companyId }
    }

    fun prefs(context: Context, companyId: String) = context.getSharedPreferences(
        "salary_company_${companyId.replace(Regex("[^A-Za-z0-9_-]"), "_")}", Context.MODE_PRIVATE
    )

    /** Une liste partiellement récupérée n'est jamais utilisable pour résoudre des alias employeur. */
    internal fun companiesForAliasResolution(stored: ReadResult): List<Company>? =
        stored.companies.takeIf { stored.reliable }

    /**
     * Identifiants employeur acceptés pour relire les anciennes sessions sans dépendre
     * de la position actuelle de l'entreprise dans MES ENTREPRISES.
     *
     * Si le store entreprises n'est pas fiable, seul l'identifiant explicite demandé est conservé :
     * aucun alias historique n'est déduit d'un paquet partiellement récupéré.
     */
    fun acceptedEmployerIds(context: Context, companyId: String): Set<String> {
        val requestedId = companyId.trim()
        if (requestedId.isBlank()) return emptySet()
        val companies = companiesForAliasResolution(readConfirmed(context)) ?: return setOf(requestedId)
        val company = companies.firstOrNull { it.id == requestedId } ?: return setOf(requestedId)
        return acceptedEmployerIdsForCompany(context, company)
    }

    private fun acceptedEmployerIdsForCompany(context: Context, company: Company): Set<String> {
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

    /**
     * Résout un identifiant employeur historique vers l'identifiant stable de l'entreprise.
     *
     * Une correspondance ambiguë reste volontairement inconnue : HoraTrack ne doit jamais rattacher
     * silencieusement une ancienne session ou un fait salarial à la mauvaise entreprise.
     * Un store entreprises non fiable ne permet aucune résolution canonique.
     */
    fun canonicalCompanyIdForEmployerId(context: Context, employerId: String?): String? {
        val raw = employerId?.trim().orEmpty()
        if (raw.isBlank()) return null
        val companies = companiesForAliasResolution(readConfirmed(context)) ?: return null
        companies.firstOrNull { it.id == raw }?.let { return it.id }
        return companies
            .filter { raw in acceptedEmployerIdsForCompany(context, it) }
            .map { it.id }
            .distinct()
            .singleOrNull()
    }

    internal fun decodeCompanies(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, warnings = listOf(STORAGE_WARNING))
        val companies = mutableListOf<Company>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val company = obj?.let(::decodeCompany)
            if (company == null) malformed = true else companies += company
        }
        if (companies.map { it.id }.distinct().size != companies.size) malformed = true
        return ReadResult(
            companies = companies,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    internal fun resolveStoredCompanies(primaryRaw: String?, backupRaw: String?): StorageResolution {
        val primary = primaryRaw?.let(::decodeCompanies)
            ?: ReadResult(emptyList(), false, warnings = listOf(STORAGE_WARNING))
        if (primary.reliable) return StorageResolution(primary, StorageSource.PRIMARY)

        val backup = backupRaw?.let(::decodeCompanies)
        if (backup?.reliable == true) {
            return StorageResolution(
                backup.copy(repairedFromBackup = true, warnings = listOf(REPAIRED_WARNING)),
                StorageSource.LAST_KNOWN_GOOD
            )
        }
        return StorageResolution(primary, StorageSource.NONE)
    }

    internal fun missingPrimaryBackupFailure(
        backupPresent: Boolean,
        backup: ReadResult?
    ): ReadResult? {
        if (!backupPresent || backup?.reliable == true) return null
        return ReadResult(
            companies = backup?.companies.orEmpty(),
            reliable = false,
            warnings = listOf(
                STORAGE_WARNING,
                "La copie locale de secours existe mais elle est illisible ; aucune absence d'entreprise ne peut être confirmée."
            )
        )
    }

    private fun decodeCompany(obj: JSONObject): Company? = runCatching {
        fun requiredString(key: String): String {
            val value = obj.opt(key)
            check(value is String) { "$key invalide" }
            return value
        }
        fun optionalString(key: String): String {
            if (!obj.has(key) || obj.isNull(key)) return ""
            val value = obj.opt(key)
            check(value is String) { "$key invalide" }
            return value
        }

        val id = requiredString("id").trim()
        check(id.isNotBlank()) { "id vide" }
        Company(
            id = id,
            name = optionalString("name"),
            siret = optionalString("siret"),
            address = optionalString("address"),
            conventionName = optionalString("conventionName"),
            idcc = optionalString("idcc")
        )
    }.getOrNull()

    private fun save(context: Context, companies: List<Company>): Boolean {
        if (companies.any { it.id.isBlank() } || companies.map { it.id }.distinct().size != companies.size) return false
        val raw = encodeCompanies(companies)
        val verification = decodeCompanies(raw)
        if (!verification.reliable || verification.companies.size != companies.size) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, raw)
            .putString(KEY_LAST_KNOWN_GOOD, raw)
            .commit()
    }

    private fun encodeCompanies(companies: List<Company>): String {
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
        return array.toString()
    }

    private fun migrateLegacy(context: Context) {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (store.contains(KEY)) return
        val old = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val migrated = mutableListOf<Company>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

        fun value(key: String): String = when (val v = old.all[key]) {
            null -> ""
            is String -> v
            is Number -> v.toString()
            else -> v.toString()
        }.trim()

        fun migrateSlot(slot: Int) {
            val p = if (slot == 1) "company_" else "company2_"
            val name = value("${p}name")
            val siret = value("${p}siret").filter(Char::isDigit)
            if (name.isBlank() && siret.isBlank()) return
            val id = if (siret.isNotBlank()) "siret_$siret" else "legacy_$slot"
            val idcc = value("${p}idcc").ifBlank { if (slot == 1) value("convention_idcc") else "" }
            val conventionName = value("${p}convention_name")
            val address = value("${p}address")
            migrated += Company(id, name, siret, address, conventionName, idcc)

            val contractType = if (slot == 1) value("contract_type") else value("company2_contract_type")
            val rate = if (slot == 1) value("hourly_rate") else value("company2_hourly_rate")
            val weekly = if (slot == 1) value("contract_weekly_hours") else value("company2_contract_weekly_hours")
            val coefficient = if (slot == 1) value("convention_coefficient") else value("company2_convention_coefficient")
            val meal = if (slot == 1) value("meal_amount") else value("company2_meal_amount").ifBlank { value("meal_amount") }
            val hireMsKey = if (slot == 1) "employment_start_date" else "company2_employment_start_date"
            val hireMs = when (val raw = old.all[hireMsKey]) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull() ?: 0L
                else -> 0L
            }
            val entryDate = if (hireMs > 0L) dateFormat.format(Date(hireMs)) else value(if (slot == 1) "entry_date" else "company2_entry_date")

            prefs(context, id).edit()
                .putString("contract_type", contractType)
                .putString("hourly_rate", rate)
                .putString("contract_weekly_hours", weekly)
                .putString("meal_amount", meal)
                .putString("convention_coefficient", coefficient)
                .putString("entry_date", entryDate)
                .putString("company_idcc", idcc)
                .commit()
        }

        migrateSlot(1)
        migrateSlot(2)
        save(context, migrated)
    }
}
