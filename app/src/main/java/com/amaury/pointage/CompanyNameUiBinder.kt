package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import com.amaury.pointage.v2.HoraTrackV2
import java.util.WeakHashMap
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
    private val bindings = WeakHashMap<TextView, Pair<LabelBinding, LabelBinding>>()
    private var initialized = false
    private var currentActivity: WeakReference<Activity>? = null

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        app.getSharedPreferences(preferenceFileName(HoraTrackV2.ENABLED), Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(this)
        // Historical identities remain dependencies of alias resolution, never name fallbacks.
        if (HoraTrackV2.ENABLED) app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(this)
        initialized = true
    }

    fun bind(activity: Activity) {
        init(activity)
        currentActivity = WeakReference(activity)
        apply(activity.window.decorView, activity)
    }

    internal fun preferenceFileName(v2Enabled: Boolean): String =
        if (v2Enabled) V2_PREFS else LEGACY_PREFS

    internal fun shouldRefreshForPreference(v2Enabled: Boolean, key: String?): Boolean =
        key == null || key == "company_name" || key == "company2_name" ||
            (v2Enabled && key in setOf("companies", "companies_last_known_good", "company_siret", "company2_siret"))

    fun name(context: Context, slot: Int): String {
        if (slot !in 1..2) return ""
        if (HoraTrackV2.ENABLED) {
            val stored = SalaryCompanyStore.readConfirmed(context)
            return companyNameFromV2(stored, slot) { alias ->
                SalaryCompanyStore.canonicalCompanyIdForEmployerId(context, alias)
            }
        }
        val key = if (slot == 1) "company_name" else "company2_name"
        return context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(key, "").orEmpty().trim()
    }

    internal fun companyNameFromV2(
        stored: SalaryCompanyStore.ReadResult,
        slot: Int,
        canonicalId: (String) -> String?
    ): String {
        if (!stored.reliable || slot !in 1..2) return ""
        val id = canonicalId("company_$slot") ?: return ""
        return stored.companies.singleOrNull { it.id == id }?.name.orEmpty().trim()
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
        applyLabels(view, name(context, 1), name(context, 2))
    }

    private fun applyLabels(view: View, company1: String, company2: String) {
        // Never rewrite user-entered company names or other editable business data.
        if (view is TextView && view !is EditText) {
            val state = bindings.getOrPut(view) { LabelBinding() to LabelBinding() }
            val text = view.text?.toString().orEmpty()
            val updated = state.first.render(text, company1, company2)
            if (updated != text) view.text = updated
            val hint = view.hint?.toString().orEmpty()
            val updatedHint = state.second.render(hint, company1, company2)
            if (updatedHint != hint) view.hint = updatedHint
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyLabels(view.getChildAt(i), company1, company2)
        }
    }

    /** Keep the template while our own rendered text is present; adopt external updates. */
    internal class LabelBinding {
        private var template: String? = null
        private var lastRendered: String? = null

        fun render(current: String, company1: String, company2: String): String {
            if (template == null || current != lastRendered) template = current
            return replaceCompanyLabels(requireNotNull(template), company1, company2)
                .also { lastRendered = it }
        }
    }

    fun replaceCompanyLabels(text: String, context: Context): String =
        replaceCompanyLabels(text, name(context, 1), name(context, 2))

    internal fun replaceCompanyLabels(text: String, company1: String, company2: String): String =
        Regex("\\bEntreprise\\s*([12])\\b", RegexOption.IGNORE_CASE).replace(text) { match ->
            val name = if (match.groupValues[1] == "1") company1 else company2
            name.ifBlank { match.value }
        }
}
