package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Store LOCAL des règles repas ACCO liées à l'entreprise et au SIRET exacts. */
object V2CompanyMealBasketStore {
    private const val PREFS = "horatrack_v2_company_meal_basket_rules"
    private const val MAX_RULES = 300

    fun rules(context: Context, companyId: String, expectedSiret: String): List<OfficialAccoMealBasketParserV2.Rule> {
        val siret = expectedSiret.filter(Char::isDigit)
        if (companyId.isBlank() || siret.length != 14) return emptyList()
        return load(context, companyId).filter { it.siret == siret && it.structurallyValid() }
    }

    /**
     * Stocke en une seule écriture tout le paquet d'un même accord/profil.
     *
     * Aucune règle n'est rendue visible si le paquet n'est pas intégralement valide ou si le commit
     * SharedPreferences échoue. Cela interdit qu'un accord multi-règles soit consommé partiellement.
     */
    fun saveVerifiedPackage(
        context: Context,
        companyId: String,
        rules: List<OfficialAccoMealBasketParserV2.Rule>
    ): Boolean {
        if (companyId.isBlank() || rules.isEmpty() || rules.any { !it.structurallyValid() }) return false

        val agreementIds = rules.map { it.agreementId }.toSet()
        val sirets = rules.map { it.siret }.toSet()
        val classifications = rules.map { it.classification.normalized() }.toSet()
        val statuses = rules.map { it.professionalStatus }.toSet()
        if (agreementIds.size != 1 || sirets.size != 1 || classifications.size != 1 || statuses.size != 1) return false

        val current = load(context, companyId).toMutableList()
        val sample = rules.first()
        current.removeAll { stored ->
            stored.agreementId == sample.agreementId &&
                stored.siret == sample.siret &&
                stored.classification.normalized() == sample.classification.normalized() &&
                stored.professionalStatus == sample.professionalStatus
        }
        current += rules.distinctBy { it.fingerprint }
        return commit(context, companyId, current)
    }

    /**
     * Supprime le paquet d'un accord/profil quand une nouvelle consultation officielle prouve que
     * cet ACCOTEXT ne contient plus d'objet repas. L'opération est elle-même atomique.
     */
    fun removeAgreementPackage(
        context: Context,
        companyId: String,
        agreementId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): Boolean {
        val acco = agreementId.trim().uppercase()
        val siret = expectedSiret.filter(Char::isDigit)
        val status = professionalStatus.trim().uppercase()
        if (companyId.isBlank() || !acco.matches(Regex("^ACCOTEXT\\d+$")) || siret.length != 14 ||
            classification.isEmpty() || status !in setOf("CADRE", "NON_CADRE")) return false

        val current = load(context, companyId).toMutableList()
        val changed = current.removeAll { stored ->
            stored.agreementId == acco &&
                stored.siret == siret &&
                stored.classification.normalized() == classification.normalized() &&
                stored.professionalStatus == status
        }
        return if (!changed) true else commit(context, companyId, current)
    }

    /** Remplacement unitaire historique : ne supprime jamais les autres objets du même accord. */
    fun saveVerified(context: Context, companyId: String, rule: OfficialAccoMealBasketParserV2.Rule): Boolean {
        if (companyId.isBlank() || !rule.structurallyValid()) return false
        val current = load(context, companyId).toMutableList()
        current.removeAll { sameLegalIdentity(it, rule) }
        current += rule
        return commit(context, companyId, current)
    }

    internal fun sameLegalIdentity(
        left: OfficialAccoMealBasketParserV2.Rule,
        right: OfficialAccoMealBasketParserV2.Rule
    ): Boolean = left.agreementId == right.agreementId &&
        left.siret == right.siret &&
        left.classification.normalized() == right.classification.normalized() &&
        left.professionalStatus == right.professionalStatus &&
        left.benefitId == right.benefitId

    private fun commit(
        context: Context,
        companyId: String,
        rules: List<OfficialAccoMealBasketParserV2.Rule>
    ): Boolean {
        val array = JSONArray()
        rules.takeLast(MAX_RULES).forEach { array.put(encode(it)) }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(companyId, array.toString())
            .commit()
    }

    private fun load(context: Context, companyId: String): List<OfficialAccoMealBasketParserV2.Rule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(companyId, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) decode(array.optJSONObject(index) ?: continue)?.let(::add)
        }
    }

    private fun encode(rule: OfficialAccoMealBasketParserV2.Rule): JSONObject = JSONObject()
        .put("agreementId", rule.agreementId)
        .put("siret", rule.siret)
        .put("effectiveFrom", rule.effectiveFrom.toString())
        .put("effectiveTo", rule.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("classification", encodeClassification(rule.classification))
        .put("professionalStatus", rule.professionalStatus)
        .put("benefitId", rule.benefitId)
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
        .put("evidenceExcerpt", rule.evidenceExcerpt)

    private fun decode(obj: JSONObject): OfficialAccoMealBasketParserV2.Rule? = runCatching {
        OfficialAccoMealBasketParserV2.Rule(
            agreementId = obj.getString("agreementId"),
            siret = obj.getString("siret"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = nullableString(obj, "effectiveTo")?.let(LocalDate::parse),
            classification = decodeClassification(obj.getJSONObject("classification")),
            professionalStatus = obj.getString("professionalStatus"),
            benefitId = obj.getString("benefitId"),
            deliveryMode = ConventionMealBasketV2.DeliveryMode.valueOf(obj.getString("deliveryMode")),
            amountFormula = decodeAmount(obj.getJSONObject("amount")),
            eligibilityAnyOf = decodeEligibility(obj.getJSONArray("eligibility")),
            blockers = strings(obj.optJSONArray("blockers")).map { ConventionMealBasketV2.Blocker.valueOf(it) }.toSet(),
            countingUnit = ConventionMealBasketV2.CountingUnit.valueOf(obj.getString("countingUnit")),
            maxAwardsPerCalendarDay = obj.getInt("maxAwardsPerCalendarDay"),
            evidenceExcerpt = obj.getString("evidenceExcerpt")
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
        coefficient = if (obj.isNull("coefficient")) null else obj.optInt("coefficient"),
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
        else -> error("Formule repas ACCO inconnue")
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
            .put("type", "FIXED_WINDOW").put("start", value.window.startMinute).put("end", value.window.endMinute).put("minimum", value.minimumMinutes)
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
            ConventionMealBasketV2.DailyWindow(obj.getInt("start"), obj.getInt("end")), obj.getInt("window"), obj.getInt("minimum")
        )
        "START_END_WINDOW" -> ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow(
            ConventionMealBasketV2.DailyWindow(obj.getInt("start"), obj.getInt("end"))
        )
        else -> error("Condition repas ACCO inconnue")
    }

    private fun decodeEligibility(array: JSONArray): List<ConventionMealBasketV2.EligibilityGroup> = buildList {
        for (index in 0 until array.length()) {
            val raw = array.getJSONArray(index)
            val conditions = buildList {
                for (conditionIndex in 0 until raw.length()) add(decodeCondition(raw.getJSONObject(conditionIndex)))
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
