package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.EmploymentContractHistoryV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Stockage persistant fail-closed de l'historique des contrats salariés confirmés.
 *
 * Le contrat courant n'est jamais utilisé comme fallback pour une ancienne période. Un stockage
 * absent signifie explicitement "aucun historique enregistré" ; un stockage illisible ou
 * incohérent signifie "historique non fiable" et ne peut jamais être transformé en liste vide.
 */
object V2EmploymentContractHistoryStore {
    private const val PREFS = "horatrack_v2_employment_contract_history"
    private const val KEY_CONFIRMED = "confirmed_snapshots"
    private const val KEY_BACKUP = "confirmed_snapshots_last_known_good"
    private const val MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991.0

    const val STORAGE_WARNING =
        "Contrats Salaire V2 : historique local incohérent ; aucun contrat historique ni absence de contrat ne peut être déduit de ce stockage."
    const val REPAIRED_WARNING =
        "Contrats Salaire V2 : historique local restauré depuis la dernière copie valide."

    data class ReadResult(
        val snapshots: List<EmploymentContractSnapshotV2>,
        val reliable: Boolean,
        val repairedFromBackup: Boolean,
        val warnings: List<String>
    )

    fun readConfirmed(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasPrimary = prefs.contains(KEY_CONFIRMED)
        val hasBackup = prefs.contains(KEY_BACKUP)

        if (!hasPrimary) {
            if (!hasBackup) return ReadResult(emptyList(), true, false, emptyList())
            val backupRaw = runCatching { prefs.getString(KEY_BACKUP, null) }.getOrNull()
                ?: return unreliableResult()
            val backup = decodeConfirmed(backupRaw)
            if (!backup.reliable) return unreliableResult(backup.snapshots)
            if (!writeVerified(context, KEY_CONFIRMED, backupRaw)) {
                return ReadResult(
                    snapshots = backup.snapshots,
                    reliable = false,
                    repairedFromBackup = false,
                    warnings = listOf(STORAGE_WARNING, "La copie valide a été trouvée mais sa restauration a échoué.")
                )
            }
            return ReadResult(backup.snapshots, true, true, listOf(REPAIRED_WARNING))
        }

        val primaryRaw = runCatching { prefs.getString(KEY_CONFIRMED, null) }.getOrNull()
        val primary = primaryRaw?.let(::decodeConfirmed) ?: unreliableResult()
        if (primary.reliable && primaryRaw != null) {
            if (prefs.getString(KEY_BACKUP, null) != primaryRaw) {
                writeVerified(context, KEY_BACKUP, primaryRaw)
            }
            return primary
        }

        if (!hasBackup) return primary
        val backupRaw = runCatching { prefs.getString(KEY_BACKUP, null) }.getOrNull() ?: return primary
        val backup = decodeConfirmed(backupRaw)
        if (!backup.reliable) return primary
        if (!writeVerified(context, KEY_CONFIRMED, backupRaw)) {
            return ReadResult(
                snapshots = backup.snapshots,
                reliable = false,
                repairedFromBackup = false,
                warnings = listOf(STORAGE_WARNING, "La copie valide a été trouvée mais sa restauration a échoué.")
            )
        }
        return ReadResult(backup.snapshots, true, true, listOf(REPAIRED_WARNING))
    }

    fun history(context: Context): EmploymentContractHistoryV2? = historyFrom(readConfirmed(context))

    internal fun historyFrom(stored: ReadResult): EmploymentContractHistoryV2? {
        if (!stored.reliable) return null
        return runCatching { EmploymentContractHistoryV2(stored.snapshots) }.getOrNull()
    }

    fun saveConfirmed(context: Context, snapshot: EmploymentContractSnapshotV2): Boolean {
        val candidate = normalizeSnapshot(snapshot)
        if (runCatching { EmploymentContractHistoryV2(listOf(candidate)) }.isFailure) return false

        val stored = readConfirmed(context)
        if (!stored.reliable) return false

        val current = stored.snapshots
            .filterNot {
                it.contract.employerId.trim() == candidate.contract.employerId &&
                    it.versionId.trim() == candidate.versionId
            }
            .toMutableList()
            .apply { add(candidate) }

        if (runCatching { EmploymentContractHistoryV2(current) }.isFailure) return false
        val raw = encodeConfirmed(current)
        if (!writeVerified(context, KEY_CONFIRMED, raw)) return false
        if (!writeVerified(context, KEY_BACKUP, raw)) return false

        val reloaded = readConfirmed(context)
        return reloaded.reliable && reloaded.snapshots.contains(candidate)
    }

    internal fun decodeConfirmed(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return unreliableResult()
        val snapshots = mutableListOf<EmploymentContractSnapshotV2>()
        var malformed = false

        for (index in 0 until array.length()) {
            val objectValue = array.opt(index) as? JSONObject
            val snapshot = objectValue?.let(::decodeSnapshot)
            if (snapshot == null) {
                malformed = true
            } else {
                snapshots += normalizeSnapshot(snapshot)
            }
        }

        if (!malformed && runCatching { EmploymentContractHistoryV2(snapshots) }.isFailure) {
            malformed = true
        }
        return ReadResult(
            snapshots = snapshots,
            reliable = !malformed,
            repairedFromBackup = false,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    internal fun encodeConfirmed(snapshots: List<EmploymentContractSnapshotV2>): String {
        val sorted = snapshots.map(::normalizeSnapshot).sortedWith(
            compareBy<EmploymentContractSnapshotV2> { it.contract.employerId }
                .thenBy { it.effectiveFromEpochDay }
                .thenBy { it.versionId }
        )
        return JSONArray().apply { sorted.forEach { put(encodeSnapshot(it)) } }.toString()
    }

    private fun encodeSnapshot(snapshot: EmploymentContractSnapshotV2): JSONObject = JSONObject()
        .put("versionId", snapshot.versionId)
        .put("sourceId", snapshot.sourceId)
        .put("effectiveFromEpochDay", snapshot.effectiveFromEpochDay)
        .put("effectiveToEpochDay", snapshot.effectiveToEpochDay ?: JSONObject.NULL)
        .put("checkedAtMs", snapshot.checkedAtMs)
        .put("note", snapshot.note ?: JSONObject.NULL)
        .put("contract", encodeContract(snapshot.contract))

    private fun encodeContract(contract: ContractV2): JSONObject = JSONObject()
        .put("id", contract.id)
        .put("employerId", contract.employerId)
        .put("type", contract.type.name)
        .put("contractualWeeklyMinutes", contract.contractualWeeklyMinutes ?: JSONObject.NULL)
        .put("grossHourlyRate", contract.grossHourlyRate ?: JSONObject.NULL)
        .put("hireDateEpochDay", contract.hireDateEpochDay ?: JSONObject.NULL)
        .put("payrollCutoffDay", contract.payrollCutoffDay ?: JSONObject.NULL)
        .put("forfaitHoursPeriod", contract.forfaitHoursPeriod?.name ?: JSONObject.NULL)
        .put("forfaitHours", contract.forfaitHours ?: JSONObject.NULL)
        .put("forfaitAnnualDays", contract.forfaitAnnualDays ?: JSONObject.NULL)
        .put("monthlyGrossSalary", contract.monthlyGrossSalary ?: JSONObject.NULL)

    private fun decodeSnapshot(obj: JSONObject): EmploymentContractSnapshotV2? = runCatching {
        val contractObject = obj.opt("contract") as? JSONObject ?: error("Contrat absent")
        EmploymentContractSnapshotV2(
            versionId = requiredString(obj, "versionId"),
            sourceId = requiredString(obj, "sourceId"),
            effectiveFromEpochDay = requiredLong(obj, "effectiveFromEpochDay"),
            effectiveToEpochDay = optionalLong(obj, "effectiveToEpochDay"),
            contract = decodeContract(contractObject),
            checkedAtMs = requiredLong(obj, "checkedAtMs"),
            note = optionalString(obj, "note")
        )
    }.getOrNull()

    private fun decodeContract(obj: JSONObject): ContractV2 {
        val type = runCatching { ContractTypeV2.valueOf(requiredString(obj, "type")) }
            .getOrElse { error("Type de contrat invalide") }
        val period = optionalString(obj, "forfaitHoursPeriod")?.let {
            runCatching { ForfaitHoursPeriodV2.valueOf(it) }.getOrElse { error("Période de forfait invalide") }
        }
        return ContractV2(
            id = requiredString(obj, "id"),
            employerId = requiredString(obj, "employerId"),
            type = type,
            contractualWeeklyMinutes = optionalInt(obj, "contractualWeeklyMinutes"),
            grossHourlyRate = optionalDouble(obj, "grossHourlyRate"),
            hireDateEpochDay = optionalLong(obj, "hireDateEpochDay"),
            payrollCutoffDay = optionalInt(obj, "payrollCutoffDay"),
            forfaitHoursPeriod = period,
            forfaitHours = optionalDouble(obj, "forfaitHours"),
            forfaitAnnualDays = optionalDouble(obj, "forfaitAnnualDays"),
            monthlyGrossSalary = optionalDouble(obj, "monthlyGrossSalary")
        )
    }

    private fun normalizeSnapshot(snapshot: EmploymentContractSnapshotV2): EmploymentContractSnapshotV2 {
        val contract = snapshot.contract.copy(
            id = snapshot.contract.id.trim(),
            employerId = snapshot.contract.employerId.trim()
        )
        return snapshot.copy(
            versionId = snapshot.versionId.trim(),
            sourceId = snapshot.sourceId.trim(),
            contract = contract,
            note = snapshot.note?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun requiredString(obj: JSONObject, key: String): String {
        val raw = obj.opt(key)
        if (raw !is String) error("$key invalide")
        val value = raw.trim()
        if (value.isEmpty()) error("$key vide")
        return value
    }

    private fun optionalString(obj: JSONObject, key: String): String? {
        val raw = obj.opt(key)
        if (raw == null || raw === JSONObject.NULL) return null
        if (raw !is String) error("$key invalide")
        return raw.trim().takeIf { it.isNotEmpty() }
    }

    private fun requiredLong(obj: JSONObject, key: String): Long = strictLong(obj.opt(key), key)

    private fun optionalLong(obj: JSONObject, key: String): Long? {
        val raw = obj.opt(key)
        if (raw == null || raw === JSONObject.NULL) return null
        return strictLong(raw, key)
    }

    private fun optionalInt(obj: JSONObject, key: String): Int? {
        val raw = obj.opt(key)
        if (raw == null || raw === JSONObject.NULL) return null
        val value = strictLong(raw, key)
        return value.toInt().takeIf { it.toLong() == value } ?: error("$key hors plage")
    }

    private fun optionalDouble(obj: JSONObject, key: String): Double? {
        val raw = obj.opt(key)
        if (raw == null || raw === JSONObject.NULL) return null
        if (raw !is Number) error("$key invalide")
        val value = raw.toDouble()
        if (!value.isFinite()) error("$key non fini")
        return value
    }

    private fun strictLong(raw: Any?, key: String): Long {
        if (raw !is Number) error("$key invalide")
        val value = raw.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || abs(value) > MAX_EXACT_JSON_INTEGER) {
            error("$key entier invalide")
        }
        return value.toLong()
    }

    private fun writeVerified(context: Context, key: String, value: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val committed = runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)
        if (!committed) return false
        return runCatching { prefs.getString(key, null) == value }.getOrDefault(false)
    }

    private fun unreliableResult(snapshots: List<EmploymentContractSnapshotV2> = emptyList()) = ReadResult(
        snapshots = snapshots,
        reliable = false,
        repairedFromBackup = false,
        warnings = listOf(STORAGE_WARNING)
    )
}
