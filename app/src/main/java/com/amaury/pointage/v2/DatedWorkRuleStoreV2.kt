package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.DatedWorkRuleApplicabilityV2
import com.amaury.pointage.v2.engine.DatedWorkRuleV2
import com.amaury.pointage.v2.engine.WorkRuleConfirmationV2
import com.amaury.pointage.v2.engine.WorkRuleOwnerV2
import com.amaury.pointage.v2.engine.WorkRuleTopicV2
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Source unique locale des références de règles de travail par propriétaire exact.
 * Ne stocke aucun taux de paie : la source juridique/contractuelle demeure canonique.
 *
 * Toutes les API exigent un accountId/employeeId explicite : aucun compte courant
 * présumé, aucun premier employeur ni fallback global.
 */
object DatedWorkRuleStoreV2 {
    private const val PREFS = "dated_work_applicability_v2"
    private const val SCHEMA_VERSION = 1
    const val STORAGE_WARNING = "Règles individuelles : stockage incohérent ; aucune règle ne peut être certifiée."
    const val OWNER_WARNING = "Règles individuelles : compte, salarié, entreprise ou contrat non confirmé."

    data class ReadResult(
        val records: List<DatedWorkRuleV2>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    private val lock = Any()

    fun read(context: Context, owner: WorkRuleOwnerV2): ReadResult {
        if (!confirmedOwner(context, owner)) return ReadResult(emptyList(), false, listOf(OWNER_WARNING))
        return synchronized(lock) { readLocal(context, owner) }
    }

    /** Append-only : un doublon ou un chevauchement confirmé n'écrase aucune version. */
    fun append(context: Context, owner: WorkRuleOwnerV2, record: DatedWorkRuleV2): Boolean {
        if (!confirmedOwner(context, owner) || record.owner != owner) return false
        return synchronized(lock) {
            val current = readLocal(context, owner)
            if (!current.reliable || current.records.any { it.id == record.id }) return@synchronized false
            writeLocal(context, owner, current.records + record)
        }
    }

    /**
     * Changement explicite de règle : fermer une ancienne version ouverte ET
     * ajouter la nouvelle en une seule transaction, sans période réécrite en silence.
     */
    fun replaceOpenVersion(
        context: Context,
        owner: WorkRuleOwnerV2,
        oldRecordId: String,
        newRecord: DatedWorkRuleV2
    ): Boolean {
        if (!confirmedOwner(context, owner) || newRecord.owner != owner ||
            newRecord.confirmation != WorkRuleConfirmationV2.CONFIRMED ||
            oldRecordId.isBlank()) return false
        return synchronized(lock) {
            val current = readLocal(context, owner)
            if (!current.reliable || current.records.any { it.id == newRecord.id }) return@synchronized false
            val previous = current.records.singleOrNull { it.id == oldRecordId }
                ?: return@synchronized false
            if (previous.owner != owner || previous.topic != newRecord.topic ||
                previous.confirmation != WorkRuleConfirmationV2.CONFIRMED ||
                previous.effectiveToEpochDay != null ||
                newRecord.effectiveFromEpochDay <= previous.effectiveFromEpochDay
            ) return@synchronized false
            val updated = current.records.map {
                if (it.id == oldRecordId) it.copy(effectiveToEpochDay = newRecord.effectiveFromEpochDay)
                else it
            } + newRecord
            writeLocal(context, owner, updated)
        }
    }

    private fun confirmedOwner(context: Context, owner: WorkRuleOwnerV2): Boolean {
        if (!owner.isValid()) return false
        val companies = SalaryCompanyStore.readConfirmed(context)
        if (!companies.reliable || companies.companies.none { it.id == owner.employerId }) return false
        val contracts = V2EmploymentContractHistoryStore.readConfirmed(context)
        if (!contracts.reliable) return false
        return contracts.snapshots.count {
            it.versionId == owner.contractVersionId && it.contract.employerId == owner.employerId
        } == 1
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun ownerStorageKey(owner: WorkRuleOwnerV2): String {
        val parts = listOf(owner.accountId, owner.employeeId, owner.employerId, owner.contractVersionId)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8))
        return "owner:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun readLocal(context: Context, owner: WorkRuleOwnerV2): ReadResult {
        val p = prefs(context)
        val key = ownerStorageKey(owner)
        val primary = runCatching { p.getString(key, null) }.getOrNull()
        val backup = runCatching { p.getString(key + ":backup", null) }.getOrNull()
        if (primary == null && backup == null) {
            if (p.contains(key) || p.contains(key + ":backup")) return corrupt()
            return ReadResult(emptyList(), true, emptyList())
        }
        val decodedPrimary = primary?.let { decode(it, owner) }
        if (decodedPrimary?.reliable == true) return decodedPrimary
        val decodedBackup = backup?.let { decode(it, owner) }
        if (decodedBackup?.reliable != true) return corrupt()
        val restored = p.edit().putString(key, backup).commit()
        return if (restored) decodedBackup else corrupt()
    }

    private fun writeLocal(context: Context, owner: WorkRuleOwnerV2, records: List<DatedWorkRuleV2>): Boolean {
        if (!DatedWorkRuleApplicabilityV2.validTimeline(owner, records)) return false
        val raw = encode(owner, records)
        val key = ownerStorageKey(owner)
        val saved = prefs(context).edit()
            .putString(key, raw)
            .putString(key + ":backup", raw)
            .commit()
        if (!saved) return false
        val reread = readLocal(context, owner)
        return reread.reliable && reread.records == records
    }

    internal fun encode(owner: WorkRuleOwnerV2, records: List<DatedWorkRuleV2>): String {
        val json = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("accountId", owner.accountId)
            .put("employeeId", owner.employeeId)
            .put("employerId", owner.employerId)
            .put("contractVersionId", owner.contractVersionId)
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject()
                .put("id", r.id)
                .put("topic", r.topic.name)
                .put("fromDay", r.effectiveFromEpochDay)
                .put("toDayExclusive", r.effectiveToEpochDay ?: JSONObject.NULL)
                .put("sourceId", r.sourceId)
                .put("ruleReference", r.ruleReference)
                .put("checkedAtMs", r.checkedAtMs)
                .put("confirmation", r.confirmation.name)
                .put("notApplicable", r.explicitlyNotApplicable))
        }
        return json.put("rules", array).toString()
    }

    internal fun decode(raw: String, owner: WorkRuleOwnerV2): ReadResult = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schema", -1) != SCHEMA_VERSION ||
            json.optString("accountId") != owner.accountId ||
            json.optString("employeeId") != owner.employeeId ||
            json.optString("employerId") != owner.employerId ||
            json.optString("contractVersionId") != owner.contractVersionId) return corrupt()
        val array = json.getJSONArray("rules")
        val records = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val toDay = if (item.isNull("toDayExclusive")) null
                    else readLong(item, "toDayExclusive")
                if (!item.isNull("toDayExclusive") && toDay == null) error("Date invalide")
                val notApplicable = item.opt("notApplicable") as? Boolean
                    ?: error("Qualification invalide")
                add(DatedWorkRuleV2(
                    id = item.getString("id"),
                    owner = owner,
                    topic = WorkRuleTopicV2.valueOf(item.getString("topic")),
                    effectiveFromEpochDay = readLong(item, "fromDay") ?: error("Date invalide"),
                    effectiveToEpochDay = toDay,
                    sourceId = item.getString("sourceId"),
                    ruleReference = item.getString("ruleReference"),
                    checkedAtMs = readLong(item, "checkedAtMs") ?: error("Date de vérification invalide"),
                    confirmation = WorkRuleConfirmationV2.valueOf(item.getString("confirmation")),
                    explicitlyNotApplicable = notApplicable
                ))
            }
        }
        if (!DatedWorkRuleApplicabilityV2.validTimeline(owner, records)) return corrupt()
        ReadResult(records, true, emptyList())
    }.getOrElse { corrupt() }

    private fun readLong(item: JSONObject, key: String): Long? {
        val value = item.opt(key)
        return when (value) {
            is Number -> value.toString().takeIf { it.matches(Regex("-?[0-9]+")) }?.toLongOrNull()
            else -> null
        }
    }

    private fun corrupt() = ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
}
