package com.amaury.pointage.v2

import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.DatedWorkRuleApplicabilityV2
import com.amaury.pointage.v2.engine.DatedWorkRuleContractScopeV2
import com.amaury.pointage.v2.engine.EmploymentContractHistoryV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.engine.WorkRuleOwnerV2
import org.json.JSONObject

/** The newly transferable sources keep their domain validations and conservative merge rules. */
internal object SalaryBackupPayloadsV2 {
    const val CONTRACTS = "horatrack_v2_employment_contract_history"
    const val SICKNESS = "salary_confirmed_sickness_cash_v2"
    const val DATED_RULES = "dated_work_applicability_v2"

    fun valid(name: String, values: Map<String, *>): Boolean = runCatching {
        when (name) {
            CONTRACTS -> contracts(values)
            SICKNESS -> values.forEach { (key, value) ->
                val parts = key.split('|')
                require(parts.size == 2 && value is String)
                require(ConfirmedMonthlySicknessCashV2.decode(value, parts[0], parts[1]).reliable)
            }
            DATED_RULES -> values.forEach { (key, value) -> datedOwner(key, value) }
        }
    }.isSuccess

    fun contracts(values: Map<String, *>): List<EmploymentContractSnapshotV2> {
        require(values.keys.all { it in setOf("confirmed_snapshots", "confirmed_snapshots_last_known_good") })
        fun decode(key: String): List<EmploymentContractSnapshotV2>? = values[key]?.let { value ->
            require(value is String)
            val decoded = V2EmploymentContractHistoryStore.decodeConfirmed(value)
            require(decoded.reliable) { "Historique contractuel sauvegardé incohérent" }
            decoded.snapshots
        }
        val primary = decode("confirmed_snapshots")
        val backup = decode("confirmed_snapshots_last_known_good")
        // A last-known-good copy may legitimately be older; retain the valid primary authority.
        return primary ?: backup ?: emptyList()
    }

    fun mergeContracts(local: Map<String, *>, saved: Map<String, *>): Map<String, Any?> {
        val merged = contracts(local).toMutableList()
        contracts(saved).forEach { candidate ->
            val old = merged.singleOrNull { it.contract.employerId == candidate.contract.employerId && it.versionId == candidate.versionId }
            require(old == null || old == candidate) { "Une version contractuelle diffère de la sauvegarde" }
            if (old == null) merged += candidate
        }
        EmploymentContractHistoryV2(merged)
        if (merged.isEmpty() && local.isEmpty() && saved.isEmpty()) return emptyMap()
        val encoded = V2EmploymentContractHistoryStore.encodeConfirmed(merged)
        return mapOf("confirmed_snapshots" to encoded, "confirmed_snapshots_last_known_good" to encoded)
    }

    private fun datedOwner(key: String, value: Any?): WorkRuleOwnerV2 {
        require(value is String)
        val item = JSONObject(value)
        val owner = WorkRuleOwnerV2(item.getString("accountId"), item.getString("employeeId"),
            item.getString("employerId"), item.getString("contractVersionId"))
        require(owner.isValid())
        val expected = DatedWorkRuleStoreV2.ownerStorageKey(owner)
        require(key == expected || key == "$expected:backup")
        require(DatedWorkRuleStoreV2.decode(value, owner).reliable)
        return owner
    }

    fun validateOwnedRules(values: Map<String, *>, scope: String) {
        values.forEach { (key, value) ->
            val owner = datedOwner(key, value)
            require(scope == "uid:${owner.accountId}") { "Règle individuelle d'un autre compte" }
        }
    }

    fun mergeRules(local: Map<String, *>, saved: Map<String, *>, scope: String): Map<String, Any?> {
        validateOwnedRules(local, scope)
        validateOwnedRules(saved, scope)
        val owners = (local + saved).map { datedOwner(it.key, it.value) }.distinct()
        val output = linkedMapOf<String, Any?>()
        owners.forEach { owner ->
            val key = DatedWorkRuleStoreV2.ownerStorageKey(owner)
            fun records(values: Map<String, *>) = (values[key] ?: values["$key:backup"])?.let {
                DatedWorkRuleStoreV2.decode(it as String, owner).records
            }.orEmpty()
            val merged = records(local).toMutableList()
            records(saved).forEach { candidate ->
                val old = merged.singleOrNull { it.id == candidate.id }
                require(old == null || old == candidate) { "Une règle individuelle diffère de la sauvegarde" }
                if (old == null) merged += candidate
            }
            require(DatedWorkRuleApplicabilityV2.validTimeline(owner, merged)) { "Règles individuelles en conflit" }
            val raw = DatedWorkRuleStoreV2.encode(owner, merged)
            output[key] = raw
            output["$key:backup"] = raw
        }
        return output
    }

    fun validateDependencies(
        rules: Map<String, *>, scope: String,
        contractValues: Map<String, *>, companyValues: Map<String, *>
    ) {
        validateOwnedRules(rules, scope)
        val timeline = contracts(contractValues)
        val companiesRaw = companyValues["companies"] ?: companyValues["companies_last_known_good"]
        val companies = if (companiesRaw == null) emptyList() else {
            require(companiesRaw is String)
            val decoded = SalaryCompanyStore.decodeCompanies(companiesRaw)
            require(decoded.reliable)
            decoded.companies
        }
        rules.forEach { (key, value) ->
            val owner = datedOwner(key, value)
            require(companies.any { it.id == owner.employerId }) { "Entreprise de règle individuelle absente" }
            val contract = timeline.singleOrNull {
                it.contract.employerId == owner.employerId && it.versionId == owner.contractVersionId
            } ?: error("Contrat de règle individuelle absent")
            require(DatedWorkRuleStoreV2.decode(value as String, owner).records.all {
                DatedWorkRuleContractScopeV2.verifiedApplicability(it, contract)
            }) { "Règle individuelle hors du contrat confirmé" }
        }
    }

    fun mergeSickness(local: Map<String, *>, saved: Map<String, *>): Map<String, Any?> {
        require(valid(SICKNESS, local) && valid(SICKNESS, saved)) { "IJSS sauvegardées incohérentes" }
        saved.forEach { (key, value) ->
            val parts = key.split('|')
            val old = local[key]
            require(old == null || ConfirmedMonthlySicknessCashV2.decode(old as String, parts[0], parts[1]).record ==
                ConfirmedMonthlySicknessCashV2.decode(value as String, parts[0], parts[1]).record) {
                "Une IJSS confirmée diffère de la sauvegarde"
            }
        }
        return (local + saved).mapValues { it.value }
    }
}
