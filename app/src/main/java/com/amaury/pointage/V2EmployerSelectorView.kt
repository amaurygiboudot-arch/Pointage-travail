package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.amaury.pointage.v2.V2ProfileStore

/** Sélecteur V2 de l'entreprise utilisée pour les nouveaux pointages. */
class V2EmployerSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object { const val TAG = "v2_employer_selector" }

    private val companiesPrefs = context.getSharedPreferences("salary_companies_v2", Context.MODE_PRIVATE)
    private val integrationPrefs = context.getSharedPreferences("horatrack_v2_integration", Context.MODE_PRIVATE)
    private val label = TextView(context)
    private val button = Button(context)

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        label.apply { text = "Entreprise du pointage"; textSize = 12f }
        button.apply {
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { chooseCompany() }
        }
        addView(label)
        addView(button, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(3) })
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        companiesPrefs.registerOnSharedPreferenceChangeListener(this)
        integrationPrefs.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    override fun onDetachedFromWindow() {
        companiesPrefs.unregisterOnSharedPreferenceChangeListener(this)
        integrationPrefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        post { refresh() }
    }

    fun refresh() {
        val companies = SalaryCompanyStore.list(context)
        when {
            companies.isEmpty() -> {
                visibility = View.GONE
                button.text = "Aucune entreprise"
            }
            companies.size == 1 -> {
                V2ProfileStore.setActiveCompanyId(context, companies.first().id)
                visibility = View.GONE
                button.text = companies.first().name.ifBlank { "Entreprise" }
            }
            else -> {
                visibility = View.VISIBLE
                val activeId = V2ProfileStore.activeCompanyId(context)
                val active = companies.firstOrNull { it.id == activeId } ?: companies.first()
                if (active.id != activeId) V2ProfileStore.setActiveCompanyId(context, active.id)
                button.text = active.name.ifBlank { "Entreprise" }
            }
        }
    }

    private fun chooseCompany() {
        val companies = SalaryCompanyStore.list(context)
        if (companies.size <= 1) return
        val activeId = V2ProfileStore.activeCompanyId(context)
        val selected = companies.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        val labels = companies.map { company ->
            buildString {
                append(company.name.ifBlank { "Entreprise" })
                if (company.siret.isNotBlank()) append(" — SIRET ${company.siret}")
            }
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Entreprise du pointage")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val company = companies.getOrNull(which) ?: return@setSingleChoiceItems
                V2ProfileStore.setActiveCompanyId(context, company.id)
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("ANNULER", null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
