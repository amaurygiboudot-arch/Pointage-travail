package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import org.json.JSONArray
import org.json.JSONObject

/** Accords et règles internes propres à une entreprise. Aucune règle n'est appliquée sans validation. */
object CompanyAgreementStoreV2 {
    private const val STORAGE_WARNING =
        "Accords ACCO : stockage local des métadonnées incohérent ; aucun état d'accord ne peut être déduit."
    private const val REPAIRED_WARNING =
        "Accords ACCO : stockage principal restauré depuis la dernière copie locale valide."
    private const val KEY_LAST_KNOWN_GOOD = "company_agreements_v2_last_known_good"
    private const val KEY_CORRUPT_BACKUP = "company_agreements_v2_corrupt_backup"

    enum class Status { UNKNOWN, TO_PROVIDE, IMPORTED, VERIFIED }

    data class Agreement(
        val id: String,
        val title: String,
        val effectiveFrom: String?,
        val effectiveTo: String?,
        val sourceLabel: String,
        val status: Status,
        val notes: String = "",
        val documentName: String = "",
        val documentMimeType: String = "",
        val documentSha256: String = "",
        val documentPath: String = "",
        val importedAtEpochMs: Long? = null
    )

    data class ReadResult(
        val agreements: List<Agreement>,
        val reliable: Boolean,
        val warnings: List<String>,
        val repairedFromBackup: Boolean = false
    )

    internal enum class StorageSource { PRIMARY, LAST_KNOWN_GOOD, NONE }

    internal data class StorageResolution(
        val result: ReadResult,
        val source: StorageSource
    )

    internal const val KEY = "company_agreements_v2"

    /**
     * Lit les métadonnées ACCO sans confondre corruption et absence d'accord.
     * Une dernière copie saine est conservée et peut restaurer automatiquement le stockage principal.
     * La valeur corrompue est sauvegardée séparément avant toute restauration.
     */
    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(
                agreements = emptyList(),
                reliable = false,
                warnings = listOf("Accords ACCO : entreprise non identifiée.")
            )
        }

        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.contains(KEY)) {
            val backupRaw = runCatching { prefs.getString(KEY_LAST_KNOWN_GOOD, null) }.getOrNull()
            val backup = backupRaw?.let(::decodeRecords)
            if (backup?.reliable == true) {
                val restored = runCatching {
                    prefs.edit().putString(KEY, backupRaw).commit()
                }.getOrDefault(false)
                return if (restored) {
                    backup.copy(
                        warnings = listOf(REPAIRED_WARNING),
                        repairedFromBackup = true
                    )
                } else {
                    ReadResult(
                        agreements = backup.agreements,
                        reliable = false,
                        warnings = listOf(
                            STORAGE_WARNING,
                            "La dernière copie ACCO valide a été trouvée mais sa restauration a échoué."
                        )
                    )
                }
            }
            return ReadResult(emptyList(), true, emptyList())
        }

        val primaryValue = runCatching { prefs.all[KEY] }.getOrNull()
        val primaryRaw = primaryValue as? String
        val backupRaw = runCatching { prefs.getString(KEY_LAST_KNOWN_GOOD, null) }.getOrNull()
        val resolution = resolveStoredAgreements(primaryRaw, backupRaw)

        return when (resolution.source) {
            StorageSource.PRIMARY -> {
                if (primaryRaw != null && backupRaw != primaryRaw) {
                    runCatching {
                        prefs.edit().putString(KEY_LAST_KNOWN_GOOD, primaryRaw).commit()
                    }
                }
                resolution.result
            }
            StorageSource.LAST_KNOWN_GOOD -> {
                val repairedRaw = backupRaw ?: return resolution.result.copy(reliable = false)
                val corruptRaw = primaryValue?.toString()
                val editor = prefs.edit().putString(KEY, repairedRaw)
                if (!corruptRaw.isNullOrBlank()) {
                    editor.putString(KEY_CORRUPT_BACKUP, corruptRaw)
                }
                val restored = runCatching { editor.commit() }.getOrDefault(false)
                if (restored) {
                    resolution.result
                } else {
                    ReadResult(
                        agreements = resolution.result.agreements,
                        reliable = false,
                        warnings = listOf(
                            STORAGE_WARNING,
                            "La dernière copie ACCO valide a été trouvée mais sa restauration a échoué."
                        )
                    )
                }
            }
            StorageSource.NONE -> resolution.result
        }
    }

    fun list(context: Context, companyId: String): List<Agreement> = read(context, companyId).agreements

    fun save(context: Context, companyId: String, agreements: List<Agreement>): Boolean {
        if (companyId.isBlank()) return false
        val current = read(context, companyId)
        if (!current.reliable || !validAgreementSet(agreements)) return false
        val raw = encode(agreements)
        val verification = decodeRecords(raw)
        if (!verification.reliable || verification.agreements.size != agreements.size) return false
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, raw)
            .putString(KEY_LAST_KNOWN_GOOD, raw)
            .commit()
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val agreements = mutableListOf<Agreement>()
        var malformed = false
        for (i in 0 until array.length()) {
            val agreement = fromJson(array.opt(i) as? JSONObject)
            if (agreement == null) malformed = true else agreements += agreement
        }
        if (agreements.groupingBy { it.id }.eachCount().any { it.value > 1 }) malformed = true
        ReadResult(
            agreements = agreements,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }.getOrElse {
        ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
    }

    internal fun resolveStoredAgreements(primaryRaw: String?, backupRaw: String?): StorageResolution {
        val primary = primaryRaw?.let(::decodeRecords)
            ?: ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        if (primary.reliable) return StorageResolution(primary, StorageSource.PRIMARY)

        val backup = backupRaw?.let(::decodeRecords)
        if (backup?.reliable == true) {
            return StorageResolution(
                result = backup.copy(
                    warnings = listOf(REPAIRED_WARNING),
                    repairedFromBackup = true
                ),
                source = StorageSource.LAST_KNOWN_GOOD
            )
        }
        return StorageResolution(primary, StorageSource.NONE)
    }

    internal fun encode(agreements: List<Agreement>): String {
        val a = JSONArray()
        agreements.forEach { x ->
            a.put(JSONObject().apply {
                put("id", x.id); put("title", x.title)
                put("effectiveFrom", x.effectiveFrom ?: ""); put("effectiveTo", x.effectiveTo ?: "")
                put("sourceLabel", x.sourceLabel); put("status", x.status.name); put("notes", x.notes)
                put("documentName", x.documentName); put("documentMimeType", x.documentMimeType)
                put("documentSha256", x.documentSha256); put("documentPath", x.documentPath)
                put("importedAtEpochMs", x.importedAtEpochMs ?: 0L)
            })
        }
        return a.toString()
    }

    fun hasVerifiedAgreement(context: Context, companyId: String): Boolean {
        val stored = read(context, companyId)
        return stored.reliable && stored.agreements.any { it.status == Status.VERIFIED }
    }

    private fun fromJson(o: JSONObject?): Agreement? {
        o ?: return null
        val id = storedString(o, "id")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val title = storedString(o, "title") ?: return null
        val effectiveFrom = storedString(o, "effectiveFrom")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val effectiveTo = storedString(o, "effectiveTo")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val sourceLabel = storedString(o, "sourceLabel") ?: return null
        val statusRaw = storedString(o, "status")?.trim().orEmpty()
        val status = if (statusRaw.isBlank()) {
            Status.UNKNOWN
        } else {
            runCatching { Status.valueOf(statusRaw) }.getOrNull() ?: return null
        }
        val notes = storedString(o, "notes") ?: return null
        val documentName = storedString(o, "documentName") ?: return null
        val documentMimeType = storedString(o, "documentMimeType") ?: return null
        val documentSha256 = storedString(o, "documentSha256") ?: return null
        val documentPath = storedString(o, "documentPath") ?: return null
        val importedAt = storedEpochMillis(o, "importedAtEpochMs") ?: return null

        return Agreement(
            id = id,
            title = title,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            sourceLabel = sourceLabel,
            status = status,
            notes = notes,
            documentName = documentName,
            documentMimeType = documentMimeType,
            documentSha256 = documentSha256,
            documentPath = documentPath,
            importedAtEpochMs = importedAt.value
        ).takeIf(::validAgreement)
    }

    /**
     * Les anciennes versions n'enregistraient pas nécessairement tous les champs facultatifs.
     * Un champ absent reste donc compatible et vaut chaîne vide ; un type non textuel est une corruption.
     */
    private fun storedString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return ""
        return o.opt(key) as? String
    }

    private data class OptionalEpochMillis(val value: Long?)

    private fun storedEpochMillis(o: JSONObject, key: String): OptionalEpochMillis? {
        if (!o.has(key) || o.isNull(key)) return OptionalEpochMillis(null)
        val raw = o.opt(key) as? Number ?: return null
        val asDouble = raw.toDouble()
        if (!asDouble.isFinite() || asDouble < 0.0) return null
        val asLong = raw.toLong()
        if (asLong.toDouble() != asDouble) return null
        return OptionalEpochMillis(asLong.takeIf { it > 0L })
    }

    private fun validAgreementSet(agreements: List<Agreement>): Boolean {
        if (agreements.any { !validAgreement(it) }) return false
        return agreements.groupingBy { it.id }.eachCount().none { it.value > 1 }
    }

    private fun validAgreement(agreement: Agreement): Boolean {
        if (agreement.id.isBlank()) return false
        if (agreement.importedAtEpochMs != null && agreement.importedAtEpochMs <= 0L) return false
        return true
    }
}
