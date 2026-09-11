package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionClassificationV2

/** Classification conventionnelle propre à une entreprise/situation salariée. */
object ConventionClassificationStoreV2 {
    private const val KEY_COEFFICIENT = "convention_coefficient"
    private const val KEY_LEVEL = "convention_level"
    private const val KEY_ECHELON = "convention_echelon"
    private const val KEY_POSITION = "convention_position"
    private const val KEY_GROUP = "convention_group"
    private const val KEY_CATEGORY = "convention_category"
    private const val KEY_EMPLOYMENT = "convention_employment"

    internal fun unavailableClassification(): ConventionClassificationV2 = ConventionClassificationV2()

    fun load(context: Context, companyId: String): ConventionClassificationV2 {
        if (companyId.isBlank()) return unavailableClassification()
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            loadConfirmed(context, companyId)
        } ?: unavailableClassification()
    }

    fun save(context: Context, companyId: String, value: ConventionClassificationV2): Boolean {
        if (companyId.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) { confirmed ->
            val prefs = SalaryCompanyStore.prefs(context, confirmed.id)
            val editor = prefs.edit()
            fun putOrRemove(key: String, text: String?) {
                val normalized = text.orEmpty().trim()
                if (normalized.isBlank()) editor.remove(key) else editor.putString(key, normalized)
            }
            if (value.coefficient == null) editor.remove(KEY_COEFFICIENT)
            else editor.putString(KEY_COEFFICIENT, value.coefficient.toString())
            putOrRemove(KEY_LEVEL, value.level)
            putOrRemove(KEY_ECHELON, value.echelon)
            putOrRemove(KEY_POSITION, value.position)
            putOrRemove(KEY_GROUP, value.group)
            putOrRemove(KEY_CATEGORY, value.category)
            putOrRemove(KEY_EMPLOYMENT, value.employment)
            editor.commit()
        } == true
    }

    private fun loadConfirmed(context: Context, companyId: String): ConventionClassificationV2 {
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        fun text(key: String): String? = prefs.getString(key, "").orEmpty().trim().takeIf { it.isNotBlank() }
        return ConventionClassificationV2(
            coefficient = text(KEY_COEFFICIENT)?.toIntOrNull()?.takeIf { it > 0 },
            level = text(KEY_LEVEL),
            echelon = text(KEY_ECHELON),
            position = text(KEY_POSITION),
            group = text(KEY_GROUP),
            category = text(KEY_CATEGORY),
            employment = text(KEY_EMPLOYMENT)
        )
    }
}
