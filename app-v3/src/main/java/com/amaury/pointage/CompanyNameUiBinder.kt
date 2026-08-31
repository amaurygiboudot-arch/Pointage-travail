package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Remplace les libellés techniques "Entreprise 1 / Entreprise 2" par le nom réel
 * récupéré depuis le SIRET. Le fallback reste visible tant qu'aucun nom n'est connu.
 *
 * Ce binder ne s'accroche pas au layout en continu : il s'applique au resume et
 * uniquement quand le nom d'une entreprise change, afin d'éviter toute latence.
 */
object CompanyNameUiBinder : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val PREFS = "salary_settings"
    private var initialized = false
    private var currentActivity: WeakReference<Activity>? = null

    fun init(context: Context) {
        if (initialized) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(this)
        initialized = true
    }

    fun bind(activity: Activity) {
        init(activity)
        currentActivity = WeakReference(activity)
        apply(activity.window.decorView, activity)
    }

    fun name(context: Context, slot: Int): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (slot == 1) "company_name" else "company2_name"
        return prefs.getString(key, "").orEmpty().trim()
    }

    fun label(context: Context, slot: Int): String =
        name(context, slot).ifBlank { "Entreprise $slot" }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != "company_name" && key != "company2_name") return
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
