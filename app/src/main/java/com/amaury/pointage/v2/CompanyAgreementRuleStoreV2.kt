package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import org.json.JSONArray
import org.json.JSONObject

/** Stockage séparé des règles candidates extraites des accords. */
object CompanyAgreementRuleStoreV2 {
    private const val STORAGE_WARNING =
        "Règles ACCO : stockage local incohérent ; les règles d'entreprise ne peuvent pas être utilisées pour la paie."
    private const val REPAIRED_WARNING =
        "Règles ACCO : stockage principal restauré depuis la dernière copie locale valide."
    internal const val KEY_LAST_KNOWN_GOOD = "company_agreement_rule_candidates_v2_last_known_good"
    private const val KEY_CORRUPT_BACKUP = "company_agreement_rule_candidates_v2_corrupt_backup"

    data class StoredCandidate(
        val agreementId: String,
        val category: CompanyAgreementRuleExtractorV2.Category,
        val excerpt: String,
        val confidence: Double,
        val verified: Boolean = false,
        val effectiveFrom: String? = null,
        val effectiveTo: String? = null,
        val scope: String? = null,
        val calculationValueVerified: Boolean = false
    )

    data class ReadResult(
        val records: List<StoredCandidate>,
        val reliable: Boolean,
        val warnings: List<String>,
        val repairedFromBackup: Boolean = false
    )

    internal enum class StorageSource { PRIMARY, LAST_KNOWN_GOOD, NONE }

    internal data class StorageResolution(
        val result: ReadResult,
        val source: StorageSource
    )

    internal const val KEY = "company_agreement_rule_candidates_v2"

    /**
     * Lit les règles ACCO sans jamais transformer une corruption en liste vide fiable.
     * Une dernière copie saine est maintenue automatiquement et restaurée si le stockage principal
     * devient illisible. La valeur corrompue est conservée séparément avant restauration.
     */
    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("Règles ACCO : entreprise non identifiée.")
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
                        records = backup.records,
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
        val resolution = resolveStoredRecords(primaryRaw, backupRaw)

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
                        records = resolution.result.records,
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

    fun replaceForAgreement(
        context: Context,
        companyId: String,
        agreementId: String,
        candidates: List<CompanyAgreementRuleExtractorV2.Candidate>
    ): Boolean {
        if (companyId.isBlank() || agreementId.isBlank()) return false
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        return save(
            context,
            companyId,
            mergePreservingValidation(stored.records, agreementId, candidates)
        )
    }

    internal fun mergePreservingValidation(
        existing: List<StoredCandidate>,
        agreementId: String,
        candidates: List<CompanyAgreementRuleExtractorV2.Candidate>
    ): List<StoredCandidate> {
        if (candidates.isEmpty()) return existing
        val previous = existing.filter { it.agreementId == agreementId }.associateBy { it.category to it.excerpt }
        val kept = existing.filterNot { it.agreementId == agreementId }
        val refreshed = candidates.map { candidate ->
            val old = previous[candidate.category to candidate.excerpt]
            StoredCandidate(
                agreementId = agreementId,
                category = candidate.category,
                excerpt = candidate.excerpt,
                confidence = candidate.confidence,
                verified = old?.verified ?: false,
                effectiveFrom = old?.effectiveFrom,
                effectiveTo = old?.effectiveTo,
                scope = old?.scope,
                calculationValueVerified = old?.calculationValueVerified ?: false
            )
        }
        return kept + refreshed
    }

    fun list(context: Context, companyId: String): List<StoredCandidate> = read(context, companyId).records

    fun setVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        category: CompanyAgreementRuleExtractorV2.Category,
        excerpt: String,
        verified: Boolean
    ): Boolean {
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        var matched = false
        val updated = stored.records.map { candidate ->
            if (!matched && candidate.agreementId == agreementId && candidate.category == category && candidate.excerpt == excerpt) {
                matched = true
                candidate.copy(
                    verified = verified,
                    calculationValueVerified = if (verified) candidate.calculationValueVerified else false
                )
            } else candidate
        }
        return matched && save(context, companyId, updated)
    }

    fun setCalculationValueVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        category: CompanyAgreementRuleExtractorV2.Category,
        excerpt: String,
        verified: Boolean
    ): Boolean {
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        var matched = false
        val updated = stored.records.map { candidate ->
            if (!matched && candidate.agreementId == agreementId && candidate.category == category && candidate.excerpt == excerpt) {
                matched = true
                candidate.copy(calculationValueVerified = candidate.verified && verified)
            } else candidate
        }
        return matched && save(context, companyId, updated)
    }

    fun setApplicability(
        context: Context,
        companyId: String,
        agreementId: String,
        category: CompanyAgreementRuleExtractorV2.Category,
        excerpt: String,
        effectiveFrom: String?,
        effectiveTo: String?,
        scope: String?
    ): Boolean {
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        var matched = false
        val updated = stored.records.map { candidate ->
            if (!matched && candidate.agreementId == agreementId && candidate.category == category && candidate.excerpt == excerpt) {
                matched = true
                candidate.copy(
                    effectiveFrom = effectiveFrom?.trim()?.takeIf { it.isNotBlank() },
                    effectiveTo = effectiveTo?.trim()?.takeIf { it.isNotBlank() },
                    scope = scope?.trim()?.takeIf { it.isNotBlank() }
                )
            } else candidate
        }
        return matched && save(context, companyId, updated)
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<StoredCandidate>()
        var malformed = false
        for (i in 0 until array.length()) {
            val record = fromJson(array.opt(i) as? JSONObject)
            if (record == null) malformed = true else records += record
        }
        val duplicateIdentity = records
            .groupingBy { Triple(it.agreementId, it.category, it.excerpt) }
            .eachCount()
            .any { it.value > 1 }
        if (duplicateIdentity) malformed = true
        ReadResult(
            records = records,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }.getOrElse {
        ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
    }

    internal fun resolveStoredRecords(primaryRaw: String?, backupRaw: String?): StorageResolution {
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

    internal fun snapshotEntries(values: List<StoredCandidate>): Map<String, String>? {
        if (values.any { !validStorageCandidate(it) }) return null
        if (values.groupingBy { Triple(it.agreementId, it.category, it.excerpt) }.eachCount().any { it.value > 1 }) {
            return null
        }
        val raw = encode(values)
        val verification = decodeRecords(raw)
        if (!verification.reliable || verification.records.size != values.size) return null
        return linkedMapOf(
            KEY to raw,
            KEY_LAST_KNOWN_GOOD to raw
        )
    }

    private fun save(context: Context, companyId: String, values: List<StoredCandidate>): Boolean {
        if (companyId.isBlank()) return false
        val entries = snapshotEntries(values) ?: return false
        val editor = SalaryCompanyStore.prefs(context, companyId).edit()
        entries.forEach { (key, value) -> editor.putString(key, value) }
        return editor.commit()
    }

    internal fun encode(values: List<StoredCandidate>): String {
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject().apply {
                put("agreementId", value.agreementId)
                put("category", value.category.name)
                put("excerpt", value.excerpt)
                put("confidence", value.confidence)
                put("verified", value.verified)
                put("effectiveFrom", value.effectiveFrom ?: "")
                put("effectiveTo", value.effectiveTo ?: "")
                put("scope", value.scope ?: "")
                put("calculationValueVerified", value.calculationValueVerified)
            })
        }
        return array.toString()
    }

    private fun fromJson(o: JSONObject?): StoredCandidate? {
        o ?: return null
        val agreementId = (o.opt("agreementId") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val categoryRaw = o.opt("category") as? String ?: return null
        val category = runCatching { CompanyAgreementRuleExtractorV2.Category.valueOf(categoryRaw) }.getOrNull()
            ?: return null
        val excerpt = (o.opt("excerpt") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val confidence = (o.opt("confidence") as? Number)?.toDouble() ?: return null
        val verified = optionalBoolean(o, "verified") ?: return null
        val effectiveFrom = optionalString(o, "effectiveFrom") ?: return null
        val effectiveTo = optionalString(o, "effectiveTo") ?: return null
        val scope = optionalString(o, "scope") ?: return null
        val calculationValueVerified = optionalBoolean(o, "calculationValueVerified") ?: return null
        val candidate = StoredCandidate(
            agreementId = agreementId,
            category = category,
            excerpt = excerpt,
            confidence = confidence,
            verified = verified,
            effectiveFrom = effectiveFrom.value,
            effectiveTo = effectiveTo.value,
            scope = scope.value,
            calculationValueVerified = calculationValueVerified
        )
        return candidate.takeIf(::validStorageCandidate)
    }

    private data class OptionalString(val value: String?)

    private fun optionalString(o: JSONObject, key: String): OptionalString? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalString(null)
        is String -> OptionalString(raw.trim().takeIf { it.isNotBlank() })
        else -> null
    }

    private fun optionalBoolean(o: JSONObject, key: String): Boolean? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> false
        is Boolean -> raw
        else -> null
    }

    private fun validStorageCandidate(candidate: StoredCandidate): Boolean {
        if (candidate.agreementId.isBlank() || candidate.excerpt.isBlank()) return false
        if (!candidate.confidence.isFinite() || candidate.confidence < 0.0 || candidate.confidence > 1.0) return false
        if (candidate.calculationValueVerified && !candidate.verified) return false
        return true
    }
}
