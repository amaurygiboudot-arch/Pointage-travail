package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Ce compte suit son propre salarié. Un outil de gestion multi-salariés
 * nécessitera une attribution vérifiée distincte, jamais une saisie d'ID libre.
 */
object DatedWorkRuleOwnerAuthorizationV2 {
    fun ownerForSelf(
        signedInUid: String?,
        employerId: String,
        contractVersionId: String
    ): WorkRuleOwnerV2? {
        val uid = signedInUid?.trim()?.takeIf { it.isNotEmpty() && '\u0000' !in it } ?: return null
        val owner = WorkRuleOwnerV2(uid, uid, employerId.trim(), contractVersionId.trim())
        return owner.takeIf { it.isValid() }
    }

    fun authorizes(owner: WorkRuleOwnerV2, signedInUid: String?): Boolean {
        val uid = signedInUid?.trim() ?: return false
        return owner.isValid() && uid.isNotEmpty() &&
            owner.accountId == uid && owner.employeeId == uid
    }
}

/**
 * Rattachement de la règle à la version contractuelle datée.
 * Une source textuelle libre peut être conservée en brouillon TO_CONFIRM,
 * mais ne prouve jamais seule le barème d'un domaine de paie.
 */
object DatedWorkRuleContractScopeV2 {
    fun containedInContract(
        record: DatedWorkRuleV2,
        contract: EmploymentContractSnapshotV2
    ): Boolean {
        if (!DatedWorkRuleApplicabilityV2.validRecord(record) ||
            record.owner.employerId != contract.contract.employerId ||
            record.owner.contractVersionId != contract.versionId ||
            record.effectiveFromEpochDay < contract.effectiveFromEpochDay) return false
        val lastContractDay = contract.effectiveToEpochDay ?: return true
        val lastExclusive = lastContractDay + 1L
        return record.effectiveToEpochDay != null &&
            record.effectiveToEpochDay <= lastExclusive
    }

    /**
     * CONFIRMED est réservé à la provenance du contrat temporel lui-même :
     * cela n'atteste PAS un taux salarial, une pause payée ou une majoration.
     * Tout autre sujet reste à confirmer jusqu'à validation juridique dédiée.
     */
    fun verifiedApplicability(record: DatedWorkRuleV2, contract: EmploymentContractSnapshotV2): Boolean {
        if (!containedInContract(record, contract)) return false
        if (record.confirmation == WorkRuleConfirmationV2.TO_CONFIRM) return true
        return record.topic == WorkRuleTopicV2.TIME_ACCOUNTING &&
            contract.checkedAtMs > 0L &&
            record.sourceId == contract.sourceId &&
            record.ruleReference == "contract:${contract.versionId}" &&
            record.checkedAtMs == contract.checkedAtMs &&
            !record.explicitlyNotApplicable
    }
}
