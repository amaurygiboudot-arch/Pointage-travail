package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import org.json.JSONArray

/** Conserve uniquement les métadonnées KALI vérifiées, jamais une règle supposée. */
object OfficialConventionResultStoreV2 {
    private const val KEY_VERIFIED = "official_kali_verified"
    private const val KEY_IDCC = "official_kali_idcc"
    private const val KEY_CONTAINER_ID = "official_kali_container_id"
    private const val KEY_TITLE = "official_kali_title"
    private const val KEY_BASE_TEXT_IDS = "official_kali_base_text_ids"
    private const val KEY_CHECKED_AT = "official_kali_checked_at"

    fun save(
        context: Context,
        companyId: String,
        convention: OfficialConventionContainerParserV2.VerifiedConvention
    ): Boolean {
        val baseIds = JSONArray().apply { convention.baseTextIds.forEach { put(it) } }
        return SalaryCompanyStore.prefs(context, companyId).edit()
            .putBoolean(KEY_VERIFIED, true)
            .putString(KEY_IDCC, convention.idcc)
            .putString(KEY_CONTAINER_ID, convention.containerId)
            .putString(KEY_TITLE, convention.title)
            .putString(KEY_BASE_TEXT_IDS, baseIds.toString())
            .putLong(KEY_CHECKED_AT, convention.checkedAtMs)
            .commit()
    }

    fun load(
        context: Context,
        companyId: String
    ): OfficialConventionContainerParserV2.VerifiedConvention? {
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.getBoolean(KEY_VERIFIED, false)) return null
        val idcc = prefs.getString(KEY_IDCC, null).orEmpty()
        val containerId = prefs.getString(KEY_CONTAINER_ID, null).orEmpty()
        val title = prefs.getString(KEY_TITLE, null).orEmpty()
        val checkedAtMs = prefs.getLong(KEY_CHECKED_AT, 0L)
        val baseTextIds = runCatching {
            val array = JSONArray(prefs.getString(KEY_BASE_TEXT_IDS, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.startsWith("KALITEXT") }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        if (OfficialConventionCatalogParserV2.normalizeIdcc(idcc) == null ||
            !containerId.startsWith("KALICONT") || title.isBlank() || checkedAtMs <= 0L
        ) return null
        return OfficialConventionContainerParserV2.VerifiedConvention(
            idcc = idcc,
            containerId = containerId,
            title = title,
            baseTextIds = baseTextIds,
            checkedAtMs = checkedAtMs
        )
    }
}
