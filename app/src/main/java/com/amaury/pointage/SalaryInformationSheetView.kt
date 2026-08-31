package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class SalaryInformationSheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    companion object { const val TAG = "salary_information_sheet"; private const val AWAKE_MS = 15L * 60L * 1000L }

    private val theme get() = AppThemeCatalog.current(context)
    private val dark get() = AppThemeCatalog.useDarkPalette(context)
    private val textColor get() = if (dark) theme.darkText else theme.lightText
    private val hintColor get() = if (dark) theme.darkHint else theme.lightHint
    private val accentColor get() = if (dark) theme.accentLight else theme.accent
    private val panelColor get() = if (dark) theme.darkPanel else theme.lightPanel
    private val companyLabel = TextView(context)
    private val contractType = Spinner(context)
    private val weeklyHours = field("Durée hebdomadaire contractuelle — ex. 35", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    private val hourlyRate = field("Taux horaire brut — ex. 13,70", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    private val mealAmount = field("Montant du panier — ex. 5,38", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    private val entryDate = field("Date d’entrée — JJ/MM/AAAA")
    private val status = TextView(context)
    private var selectedCompanyId: String? = null
    private var company: SalaryCompanyStore.Company? = null
    private val stopAwake = Runnable { clearKeepAwake() }

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        applyPanelBackground(this)
        buildUi()
        refresh()
    }

    /** Lie cette fiche à l'entreprise déjà sélectionnée/authentifiée. */
    fun bindCompany(companyId: String): SalaryInformationSheetView {
        selectedCompanyId = companyId
        refresh()
        return this
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); keepAwakeTemporarily() }
    override fun onDetachedFromWindow() { removeCallbacks(stopAwake); clearKeepAwake(); super.onDetachedFromWindow() }

    fun refresh() {
        val id = selectedCompanyId
        company = if (id.isNullOrBlank()) null else SalaryCompanyStore.list(context).firstOrNull { it.id == id }
        val current = company
        companyLabel.text = when {
            id.isNullOrBlank() -> "Aucune entreprise sélectionnée"
            current == null -> "Entreprise introuvable"
            current.siret.isBlank() -> current.name.ifBlank { "Entreprise" }
            else -> "${current.name.ifBlank { "Entreprise" }}\nSIRET : ${current.siret}"
        }
        if (current != null) loadCompany(current) else clearFields()
        setFormEnabled(current != null)
        applyThemeRecursively(this)
    }

    private fun buildUi() {
        addView(TextView(context).apply { text = "📋 FICHE DE RENSEIGNEMENTS"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER })
        addView(TextView(context).apply {
            text = "Cette fiche appartient uniquement à l’entreprise ouverte."
            textSize = 13f; setTextColor(hintColor); setPadding(0, dp(7), 0, dp(12))
        })
        addLabel("ENTREPRISE")
        companyLabel.apply {
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(textColor)
            background = controlBackground(); setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.CENTER_VERTICAL
        }
        addView(companyLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addLabel("CONTRAT")
        contractType.adapter = themedAdapter(listOf("Choisir le type de contrat", "Temps plein", "Temps partiel", "Forfait", "Autre"))
        contractType.background = controlBackground(); addView(contractType, rowParams()); addView(entryDate, rowParams()); addView(weeklyHours, rowParams())
        addLabel("RÉMUNÉRATION"); addView(hourlyRate, rowParams()); addView(mealAmount, rowParams())
        status.textSize = 13f; status.setPadding(0, dp(10), 0, dp(8)); addView(status)
        addView(Button(context).apply {
            tag = "information_sheet_save"
            text = "ENREGISTRER LA FICHE"; isAllCaps = false; textSize = 14f; setTextColor(accentColor); applyPanelBackground(this); setOnClickListener { save() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(5) })
    }

    private fun loadCompany(company: SalaryCompanyStore.Company) {
        val prefs = SalaryCompanyStore.prefs(context, company.id)
        contractType.setSelection(contractIndex(prefs.getString("contract_type", "").orEmpty()))
        weeklyHours.setText(prefs.getString("contract_weekly_hours", "").orEmpty().replace('.', ','))
        hourlyRate.setText(prefs.getString("hourly_rate", "").orEmpty().replace('.', ','))
        mealAmount.setText(prefs.getString("meal_amount", "").orEmpty().replace('.', ','))
        entryDate.setText(prefs.getString("entry_date", "").orEmpty())
        updateStatus(company)
    }

    private fun clearFields() {
        contractType.setSelection(0); weeklyHours.setText(""); hourlyRate.setText(""); mealAmount.setText(""); entryDate.setText("")
        status.text = "⚠️ Ouvre d’abord une entreprise depuis MES ENTREPRISES."
    }

    private fun setFormEnabled(enabled: Boolean) {
        contractType.isEnabled = enabled; weeklyHours.isEnabled = enabled; hourlyRate.isEnabled = enabled
        mealAmount.isEnabled = enabled; entryDate.isEnabled = enabled
        findViewWithTag<Button>("information_sheet_save")?.isEnabled = enabled
    }

    private fun save() {
        val current = company ?: run { Toast.makeText(context, "Ouvre d’abord une entreprise", Toast.LENGTH_LONG).show(); return }
        if (selectedCompanyId.isNullOrBlank() || current.id != selectedCompanyId) {
            status.text = "ERREUR : l’entreprise ouverte n’est plus valide."
            Toast.makeText(context, "Impossible d’enregistrer : entreprise invalide", Toast.LENGTH_LONG).show(); return
        }
        val contract = contractValue(contractType.selectedItemPosition)
        if (contract.isBlank()) { Toast.makeText(context, "Choisis le type de contrat", Toast.LENGTH_LONG).show(); return }
        val weekly = weeklyHours.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (contract != "FORFAIT" && (weekly == null || weekly <= 0.0 || weekly > 80.0)) { weeklyHours.error = "Indique la durée hebdomadaire du contrat"; return }
        val rate = hourlyRate.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (rate == null || rate <= 0.0) { hourlyRate.error = "Indique un taux horaire brut valide"; return }
        val date = entryDate.text.toString().trim()
        if (date.isBlank()) { entryDate.error = "Indique la date d’entrée dans l’entreprise"; return }
        val meal = mealAmount.text.toString().trim().replace(',', '.').toDoubleOrNull()

        val editor = SalaryCompanyStore.prefs(context, current.id).edit()
            .putString("contract_type", contract).putString("hourly_rate", rate.toString()).putString("entry_date", date)
        if (weekly != null && weekly > 0) editor.putString("contract_weekly_hours", weekly.toString()) else editor.remove("contract_weekly_hours")
        if (meal != null && meal >= 0) editor.putString("meal_amount", meal.toString()) else editor.remove("meal_amount")
        if (!editor.commit()) {
            status.text = "ERREUR : la fiche n’a pas pu être enregistrée."
            Toast.makeText(context, "Échec de l’enregistrement", Toast.LENGTH_LONG).show(); return
        }

        val reread = SalaryCompanyStore.prefs(context, current.id)
        val rereadContract = reread.getString("contract_type", "").orEmpty()
        val rereadRate = reread.getString("hourly_rate", "").orEmpty().toDoubleOrNull()
        val rereadDate = reread.getString("entry_date", "").orEmpty()
        if (rereadContract != contract || rereadRate != rate || rereadDate != date) {
            status.text = "ERREUR : la vérification de la fiche a échoué."
            Toast.makeText(context, "La fiche n’a pas été confirmée après relecture", Toast.LENGTH_LONG).show(); return
        }

        updateStatus(current); clearKeepAwake()
        Toast.makeText(context, "Fiche enregistrée pour ${current.name.ifBlank { "l’entreprise" }}", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(company: SalaryCompanyStore.Company) {
        val p = SalaryCompanyStore.prefs(context, company.id); val missing = mutableListOf<String>()
        val type = p.getString("contract_type", "").orEmpty(); if (type.isBlank()) missing += "type de contrat"
        if (type != "FORFAIT" && p.getString("contract_weekly_hours", "").orEmpty().toDoubleOrNull() == null) missing += "durée hebdomadaire"
        if (p.getString("hourly_rate", "").orEmpty().toDoubleOrNull() == null) missing += "taux horaire"
        if (p.getString("entry_date", "").isNullOrBlank()) missing += "date d’entrée"
        status.text = if (missing.isEmpty()) "✅ Fiche complète pour cette entreprise" else "⚠️ À compléter : ${missing.joinToString(", ")}"
        status.setTextColor(hintColor)
    }

    private fun keepAwakeTemporarily() {
        val activity = context as? Activity ?: return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); removeCallbacks(stopAwake); postDelayed(stopAwake, AWAKE_MS)
    }
    private fun clearKeepAwake() { (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
        this.hint = hint; inputType = type; isSingleLine = true; textSize = 14f; setTextColor(textColor); setHintTextColor(hintColor); background = controlBackground(); setPadding(dp(12), dp(7), dp(12), dp(7))
    }
    private fun addLabel(label: String) { addView(TextView(context).apply { text = label; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(accentColor); setPadding(0, dp(12), 0, 0) }) }
    private fun themedAdapter(items: List<String>) = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {
        private fun style(v: View): View { (v as? TextView)?.apply { setTextColor(textColor); setBackgroundColor(panelColor); setPadding(dp(12), dp(8), dp(12), dp(8)) }; return v }
        override fun getView(p: Int, c: View?, parent: ViewGroup): View = style(super.getView(p, c, parent))
        override fun getDropDownView(p: Int, c: View?, parent: ViewGroup): View = style(super.getDropDownView(p, c, parent))
    }
    private fun applyPanelBackground(view: View) { view.background = when (theme.id) { "natural_carbon" -> CarbonCompositeDrawable(context); else -> context.getDrawable(R.drawable.hp_panel)?.mutate() } }
    private fun controlBackground() = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(panelColor); setStroke(dp(1), accentColor); cornerRadius = dp(16).toFloat() }
    private fun applyThemeRecursively(view: View) {
        when (view) { is EditText -> { view.setTextColor(textColor); view.setHintTextColor(hintColor); view.background = controlBackground() }; is TextView -> if (view !== status) view.setTextColor(textColor) }
        if (view is ViewGroup) for (i in 0 until view.childCount) applyThemeRecursively(view.getChildAt(i))
    }
    private fun rowParams() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
    private fun contractIndex(v: String) = when (v.uppercase()) { "FULL_TIME" -> 1; "PART_TIME" -> 2; "FORFAIT" -> 3; "OTHER" -> 4; else -> 0 }
    private fun contractValue(i: Int) = when (i) { 1 -> "FULL_TIME"; 2 -> "PART_TIME"; 3 -> "FORFAIT"; 4 -> "OTHER"; else -> "" }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
