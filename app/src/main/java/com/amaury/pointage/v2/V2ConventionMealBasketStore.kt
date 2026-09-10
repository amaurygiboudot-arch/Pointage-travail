package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Cache LOCAL des règles KALI paniers / indemnités repas strictement structurées. */
object V2ConventionMealBasketStore {
    private const val PREFS = "horatrack_v2_convention_meal_basket_rules"
    private const val KEY_RULES = "verified_rules"
    private const val MAX_RULES = 500
    private const val STORAGE_WARNING =
        "KALI panier repas : stockage local des règles vérifiées incohérent ; aucune règle de branche ne peut être déduite de ce stockage."

    data class ReadResult(
        val rules: List<ConventionMealBasketV2.Rule>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun readVerified(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_RULES)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_RULES, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeVerified(raw)
    }

    fun rules(context: Context, idcc: String): List<ConventionMealBasketV2.Rule> {
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (normalized.isBlank()) return emptyList()
        return stored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    fun saveVerified(context: Context, rule: ConventionMealBasketV2.Rule) {
        require(rule.structurallyValid()) { "Règle panier KALI invalide" }
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val current = stored.rules.toMutableList()
        current.removeAll { sameLegalIdentity(it, rule) }
        current += rule
        check(current.all { it.structurallyValid() }) { "KALI panier repas : historique local invalide." }
        check(!hasDuplicateLegalIdentity(current)) { "KALI panier repas : identité juridique dupliquée." }
        persist(context, current.takeLast(MAX_RULES))
    }

    internal fun acceptsVerifiedRule(rule: ConventionMealBasketV2.Rule): Boolean = rule.structurallyValid()

    internal fun sameLegalIdentity(left: ConventionMealBasketV2.Rule, right: ConventionMealBasketV2.Rule): Boolean =
        ConventionMinimumSalaryV2.normalizeIdcc(left.idcc) == ConventionMinimumSalaryV2.normalizeIdcc(right.idcc) &&
            left.conventionScopeKey == right.conventionScopeKey &&
            left.evidenceArticleIds == right.evidenceArticleIds &&
            left.benefitId == right.benefitId &&
            left.classification.normalized() == right.classification.normalized() &&
            left.professionalStatus?.trim()?.uppercase() == right.professionalStatus?.trim()?.uppercase()

    internal fun decodeVerified(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val rules = mutableListOf<ConventionMealBasketV2.Rule>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val rule = obj?.let(::decode)
            if (rule == null) {
                malformed = true
            } else {
                rules += rule
            }
        }
        if (hasDuplicateLegalIdentity(rules)) malformed = true
        return ReadResult(
            rules = rules,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun persist(context: Context, rules: List<ConventionMealBasketV2.Rule>) {
        check(rules.all { it.structurallyValid() }) { "KALI panier repas : historique local invalide." }
        check(!hasDuplicateLegalIdentity(rules)) { "KALI panier repas : identité juridique dupliquée." }
        val array = JSONArray()
        rules.forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI panier repas : stockage local des règles impossible." }
    }

    private fun hasDuplicateLegalIdentity(rules: List<ConventionMealBasketV2.Rule>): Boolean =
        rules.indices.any { leftIndex ->
            ((leftIndex + 1) until rules.size).any { rightIndex ->
                sameLegalIdentity(rules[leftIndex], rules[rightIndex])
            }
        }

    private fun encode(rule: ConventionMealBasketV2.Rule): JSONObject = JSONObject()
        .put("idcc", rule.idcc)
        .put("ruleId", rule.ruleId)
        .put("benefitId", rule.benefitId)
        .put("effectiveFrom", rule.effectiveFrom.toString())
        .put("effectiveTo", rule.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("classification", encodeClassification(rule.classification))
        .put("professionalStatus", rule.professionalStatus ?: JSONObject.NULL)
        .put("territoryCodes", JSONArray(rule.territoryCodes.toList()))
        .put("excludedEmployments", JSONArray(rule.excludedEmployments.toList()))
        .put("deliveryMode", rule.deliveryMode.name)
        .put("amount", encodeAmount(rule.amountFormula))
        .put("eligibility", JSONArray().also { groups ->
            rule.eligibilityAnyOf.forEach { group ->
                groups.put(JSONArray().also { conditions -> group.allOf.forEach { conditions.put(encodeCondition(it)) } })
            }
        })
        .put("blockers", JSONArray(rule.blockers.map { it.name }))
        .put("countingUnit", rule.countingUnit.name)
        .put("maxAwardsPerCalendarDay", rule.maxAwardsPerCalendarDay)
        .put("source", rule.source)
        .put("conventionScopeKey", rule.conventionScopeKey)
        .put("evidenceArticleIds", JSONArray(rule.evidenceArticleIds.toList()))
        .put("extensionStatus", rule.extensionStatus.name)
        .put("extensionEffectiveFrom", rule.extensionEffectiveFrom?.toString() ?: JSONObject.NULL)

    private fun decode(obj: JSONObject): ConventionMealBasketV2.Rule? = runCatching {
        ConventionMealBasketV2.Rule(
            idcc = obj.getString("idcc"),
            ruleId = obj.getString("ruleId"),
            benefitId = obj.getString("benefitId"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = obj.optString("effectiveTo").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
            classification = decodeClassification(obj.getJSONObject("classification")),
            professionalStatus = obj.optString("professionalStatus").takeIf { it.isNotBlank() && it != "null" },
            territoryCodes = strings(obj.optJSONArray("territoryCodes")),
            excludedEmployments = strings(obj.optJSONArray("excludedEmployments")),
            deliveryMode = ConventionMealBasketV2.DeliveryMode.valueOf(obj.getString("deliveryMode")),
            amountFormula = decodeAmount(obj.getJSONObject("amount")),
            eligibilityAnyOf = decodeEligibility(obj.getJSONArray("eligibility")),
            blockers = strings(obj.optJSONArray("blockers")).map { ConventionMealBasketV2.Blocker.valueOf(it) }.toSet(),
            countingUnit = ConventionMealBasketV2.CountingUnit.valueOf(obj.getString("countingUnit")),
            maxAwardsPerCalendarDay = obj.getInt("maxAwardsPerCalendarDay"),
            source = obj.getString("source"),
            conventionScopeKey = obj.getString("conventionScopeKey"),
            evidenceArticleIds = strings(obj.getJSONArray("evidenceArticleIds")),
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.valueOf(obj.getString("extensionStatus")),
            extensionEffectiveFrom = obj.optString("extensionEffectiveFrom").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)
        ).takeIf { it.structurallyValid() }
    }.getOrNull()

    private fun encodeClassification(value: ConventionClassificationV2) = JSONObject()
        .put("coefficient", value.coefficient ?: JSONObject.NULL)
        .put("level", value.level ?: JSONObject.NULL)
        .put("echelon", value.echelon ?: JSONObject.NULL)
        .put("position", value.position ?: JSONObject.NULL)
        .put("group", value.group ?: JSONObject.NULL)
        .put("category", value.category ?: JSONObject.NULL)
        .put("employment", value.employment ?: JSONObject.NULL)

    private fun decodeClassification(obj: JSONObject) = ConventionClassificationV2(
        coefficient = obj.optInt("coefficient").takeIf { !obj.isNull("coefficient") },
        level = nullableString(obj, "level"),
        echelon = nullableString(obj, "echelon"),
        position = nullableString(obj, "position"),
        group = nullableString(obj, "group"),
        category = nullableString(obj, "category"),
        employment = nullableString(obj, "employment")
    )

    private fun encodeAmount(value: ConventionMealBasketV2.AmountFormula): JSONObject = when (value) {
        is ConventionMealBasketV2.AmountFormula.FixedEuro -> JSONObject().put("type", "FIXED").put("value", value.amount)
        is ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple -> JSONObject().put("type", "MG").put("value", value.multiplier)
        ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount -> JSONObject().put("type", "EXTERNAL")
    }

    private fun decodeAmount(obj: JSONObject): ConventionMealBasketV2.AmountFormula = when (obj.getString("type")) {
        "FIXED" -> ConventionMealBasketV2.AmountFormula.FixedEuro(obj.getDouble("value"))
        "MG" -> ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(obj.getDouble("value"))
        "EXTERNAL" -> ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount
        else -> error("Formule panier inconnue")
    }

    private fun encodeCondition(value: ConventionMealBasketV2.Condition): JSONObject = when (value) {
        ConventionMealBasketV2.Condition.WorkedDay -> JSONObject().put("type", "WORKED_DAY")
        ConventionMealBasketV2.Condition.PostedShiftWorker -> JSONObject().put("type", "POSTED")
        ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal -> JSONObject().put("type", "NO_HOME")
        ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace -> JSONObject().put("type", "AWAY")
        ConventionMealBasketV2.Condition.MustEatAtWorkplace -> JSONObject().put("type", "EAT_WORK")
        ConventionMealBasketV2.Condition.ShiftEnclosesMidnight -> JSONObject().put("type", "ENCLOSES_MIDNIGHT")
        ConventionMealBasketV2.Condition.ShiftStartsAtMidnight -> JSONObject().put("type", "STARTS_MIDNIGHT")
        is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow -> JSONObject()
            .put("type", "FIXED_WINDOW").put("start", value.window.startMinute).put("end", value.window.endMinute)
            .put("minimum", value.minimumMinutes)
        is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow -> JSONObject()
            .put("type", "EMPLOYER_WINDOW").put("start", value.allowedEnvelope.startMinute).put("end", value.allowedEnvelope.endMinute)
            .put("window", value.requiredWindowMinutes).put("minimum", value.minimumEffectiveMinutes)
        is ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow -> JSONObject()
            .put("type", "START_END_WINDOW").put("start", value.window.startMinute).put("end", value.window.endMinute)
    }

    private fun decodeCondition(obj: JSONObject): ConventionMealBasketV2.Condition = when (obj.getString("type")) {
        "WORKED_DAY" -> ConventionMealBasketV2.Condition.WorkedDay
        "POSTED" -> ConventionMealBasketV2.Condition.PostedShiftWorker
        "NO_HOME" -> ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal
        "AWAY" -> ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace
        "EAT_WORK" -> ConventionMealBasketV2.Condition.MustEatAtWorkplace
        "ENCLOSES_MIDNIGHT" -> ConventionMealBasketV2.Condition.ShiftEnclosesMidnight
        "STARTS_MIDNIGHT" -> ConventionMealBasketV2.Condition.ShiftStartsAtMidnight
        "FIXED_WINDOW" -> ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
            ConventionMealBasketV2.DailyWindow(obj.getInt("start"), obj.getInt("end")), obj.getInt("minimum")
        )
        "EMPLOYER_WINDOW" -> ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow(
            ConventionMealBasketV2.DailyWindow(obj.getInt("start"), obj.getInt("end")),
            obj.getInt("window"), obj.getInt("minimum")
        )
        "START_END_WINDOW" -> ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow(
            ConventionMealBasketV2.DailyWindow(obj.getInt("start"), obj.getInt("end"))
        )
        else -> error("Condition panier inconnue")
    }

    private fun decodeEligibility(array: JSONArray): List<ConventionMealBasketV2.EligibilityGroup> = buildList {
        for (index in 0 until array.length()) {
            val conditionsArray = array.getJSONArray(index)
            val conditions = buildList {
                for (conditionIndex in 0 until conditionsArray.length()) add(decodeCondition(conditionsArray.getJSONObject(conditionIndex)))
            }
            add(ConventionMealBasketV2.EligibilityGroup(conditions))
        }
    }

    private fun strings(array: JSONArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun nullableString(obj: JSONObject, key: String): String? = obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
}
