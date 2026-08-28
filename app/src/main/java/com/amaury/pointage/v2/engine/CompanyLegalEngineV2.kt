package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*

/** Résout l'entreprise/contrat et les règles applicables sans inventer les données manquantes. */
object CompanyContractEngineV2 {
    data class Resolution(val employer:EmployerV2,val contract:ContractV2,val blockingFields:List<String>)
    fun resolve(employer:EmployerV2, contract:ContractV2):Resolution {
        val missing=mutableListOf<String>()
        if(employer.name.isBlank()) missing += "Entreprise"
        if(contract.employerId!=employer.id) missing += "Contrat incohérent"
        if(contract.grossHourlyRate==null || contract.grossHourlyRate<=0.0) missing += "Taux horaire brut"
        if(contract.type==ContractTypeV2.PART_TIME && (contract.contractualWeeklyMinutes==null || contract.contractualWeeklyMinutes<=0)) missing += "Durée contractuelle"
        return Resolution(employer,contract,missing)
    }
}

/** Registre versionné: une règle n'est utilisable que si sa source est connue et sa date applicable. */
class LegalEngineV2(private val sources:List<OfficialSourceV2>, private val rules:List<LegalRuleV2>) {
    data class ApplicableRule(val rule:LegalRuleV2,val source:OfficialSourceV2)
    fun applicable(domain:String, epochDay:Long):List<ApplicableRule> = rules
        .filter { it.domain==domain && it.status==DecisionStatusV2.CONFIRMED && epochDay>=it.effectiveFromEpochDay && (it.effectiveToEpochDay==null || epochDay<=it.effectiveToEpochDay!!) }
        .mapNotNull { r -> sources.firstOrNull{it.id==r.sourceId}?.let{ApplicableRule(r,it)} }

    fun trace(domain:String, epochDay:Long):List<RuleTraceV2> = applicable(domain,epochDay).map {
        RuleTraceV2(it.rule.id,it.source.id,it.rule.effectiveFromEpochDay,it.rule.note ?: it.source.title)
    }
}

/** Contrat d'intégration IA: l'IA propose/qualifie, le moteur déterministe décide uniquement sur règles confirmées. */
interface AiRuleInterpreterV2 {
    data class Candidate(val domain:String,val sourceId:String,val confidence:Double,val parameters:Map<String,Double>,val explanation:String)
    fun interpret(text:String, source:OfficialSourceV2):List<Candidate>
}
