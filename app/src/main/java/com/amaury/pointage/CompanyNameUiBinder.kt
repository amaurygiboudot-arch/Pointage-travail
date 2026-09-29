package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.amaury.pointage.v2.HoraTrackV2
import java.lang.ref.WeakReference

/**
 * Remplace les libellés techniques "Entreprise 1 / Entreprise 2" par le nom réel
 * récupéré depuis le SIRET. Le fallback reste visible tant qu'aucun nom n'est connu.
 *
 * Ce binder ne s'accroche pas au layout en continu : il s'applique au resume et
 * uniquement quand le nom d'une entreprise change, afin d'éviter toute latence.
 */
object CompanyNameUiBinder : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val LEGACY_PREFS = "salary_settings"
    private const val V2_PREFS = "salary_companies_v2"
    private var initialized = false
    private var currentActivity: WeakReference<Activity>? = null

    fun init(context: Context) {
        if (initialized) return
        context.applicationContext
            .getSharedPreferences(preferenceFileName(HoraTrackV2.ENABLED), Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(this)
        initialized = true
    }

    internal fun preferenceFileName(v2Enabled: Boolean): String =
        if (v2Enabled) V2_PREFS else LEGACY_PREFS

    internal fun shouldRefreshForPreference(v2Enabled: Boolean, key: String?): Boolean =
        if (v2Enabled) key == "companies"
        else key == "company_name" || key == "company2_name"

    fun bind(activity: Activity) {
        init(activity)
        currentActivity = WeakReference(activity)
        apply(activity.window.decorView, activity)
    }

    fun name(context: Context, slot: Int): String {
        val normalizedSlot = slot.coerceIn(1, 2)
        if (HoraTrackV2.ENABLED) {
            return companyNameFromV2(SalaryCompanyStore.readConfirmed(context), normalizedSlot)
        }
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val key = if (normalizedSlot == 1) "company_name" else "company2_name"
        return prefs.getString(key, "").orEmpty().trim()
    }

    internal fun companyNameFromV2(stored: SalaryCompanyStore.ReadResult, slot: Int): String {
        if (!stored.reliable || slot !in 1..2) return ""
        return stored.companies.getOrNull(slot - 1)?.name.orEmpty().trim()
    }

    fun label(context: Context, slot: Int): String =
        name(context, slot).ifBlank { "Entreprise $slot" }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!shouldRefreshForPreference(HoraTrackV2.ENABLED, key)) return
        val activity = currentActivity?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread { apply(activity.window.decorView, activity) }
    }

    private fun apply(view: View, context: Context) {
        if (view is TextView) {
            val original = view.text?.toString().orEmpty()
            if (original.isNotBlank()) {
                val updated = replaceCompanyLabels(original, context)
                if (updated != original) view.text = updated
            }
            val hint = view.hint?.toString().orEmpty()
            if (hint.isNotBlank()) {
                val updatedHint = replaceCompanyLabels(hint, context)
                if (updatedHint != hint) view.hint = updatedHint
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) apply(view.getChildAt(i), context)
        }
    }

    fun replaceCompanyLabels(text: String, context: Context): String {
        var result = text
        val company1 = name(context, 1)
        val company2 = name(context, 2)
        if (company1.isNotBlank()) {
            result = result.replace(Regex("Entreprise\\s*1", RegexOption.IGNORE_CASE), company1)
        }
        if (company2.isNotBlank()) {
            result = result.replace(Regex("Entreprise\\s*2", RegexOption.IGNORE_CASE), company2)
        }
        return result
    }
}
