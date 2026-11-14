package com.amaury.pointage

import com.amaury.pointage.v2.SalaryAccountScope

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

    private var companiesPrefs: SharedPreferences? = null
    private var integrationPrefs: SharedPreferences? = null
    private var observedLease: SalaryAccountScope.Lease? = null
    private var selectionDialog: AlertDialog? = null
    private val accountListener: () -> Unit = { post {
        selectionDialog?.dismiss()
        observeAccount()
        refresh()
    }; Unit }
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
        SalaryAccountScope.addListener(accountListener)
        observeAccount()
        refresh()
    }

    override fun onDetachedFromWindow() {
        SalaryAccountScope.removeListener(accountListener)
        companiesPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        integrationPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        selectionDialog?.dismiss()
        observedLease = null
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        post { refresh() }
    }

    private fun observeAccount() {
        val lease = SalaryAccountScope.lease(context)
        if (lease == observedLease) return
        companiesPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        integrationPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        observedLease = lease
        val ownerContext = SalaryAccountScope.bindContext(context, lease)
        companiesPrefs = SalaryAccountScope.preferences(ownerContext, "salary_companies_v2")
        integrationPrefs = SalaryAccountScope.preferences(ownerContext, "horatrack_v2_integration")
        companiesPrefs?.registerOnSharedPreferenceChangeListener(this)
        integrationPrefs?.registerOnSharedPreferenceChangeListener(this)
    }

    fun refresh() {
        val context = SalaryAccountScope.bindContext(this.context)
        val stored = SalaryCompanyStore.readConfirmed(context)
        if (!stored.reliable) {
            visibility = View.VISIBLE
            label.text = "Entreprise du pointage — données incohérentes"
            button.text = "Stockage entreprises à récupérer"
            button.isEnabled = false
            return
        }

        label.text = "Entreprise du pointage"
        button.isEnabled = true
        val companies = stored.companies
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
        val ownerContext = SalaryAccountScope.bindContext(context)
        val stored = SalaryCompanyStore.readConfirmed(ownerContext)
        if (!stored.reliable) return
        val companies = stored.companies
        if (companies.size <= 1) return
        val activeId = V2ProfileStore.activeCompanyId(context)
        val selected = companies.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        val labels = companies.map { company ->
            buildString {
                append(company.name.ifBlank { "Entreprise" })
                if (company.siret.isNotBlank()) append(" — SIRET ${company.siret}")
            }
        }.toTypedArray()

        selectionDialog = SalaryAccountUi.dialog(ownerContext)
            .setTitle("Entreprise du pointage")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                if (!SalaryAccountScope.available(ownerContext)) { dialog.dismiss(); refresh(); return@setSingleChoiceItems }
                val company = companies.getOrNull(which) ?: return@setSingleChoiceItems
                V2ProfileStore.setActiveCompanyId(ownerContext, company.id)
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("ANNULER", null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
