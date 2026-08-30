package com.amaury.pointage

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Façade V2 Salaire. Les moteurs historiques restent montés mais masqués pendant la migration. */
class V2SalaryExtrasWatcher @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), ViewTreeObserver.OnGlobalLayoutListener {
    companion object {
        const val TAG = "v2_salary_extras_watcher"
        private const val ROOT_TAG = "salary_v2_reorganized_root"
        private const val LEGACY_TAG = "salary_v2_legacy_container"
    }

    init { tag = TAG; visibility = GONE }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); rootView.viewTreeObserver.addOnGlobalLayoutListener(this); installIfPresent() }
    override fun onDetachedFromWindow() { if (rootView.viewTreeObserver.isAlive) rootView.viewTreeObserver.removeOnGlobalLayoutListener(this); super.onDetachedFromWindow() }
    override fun onGlobalLayout() = installIfPresent()

    private fun installIfPresent() {
        val content = rootView.findViewById<LinearLayout>(R.id.contentPanel) ?: return
        val salary = content.findViewWithTag<SalaryPanelView>("integrated_salary_panel") ?: return
        content.findViewWithTag<SalaryInformationSheetView>(SalaryInformationSheetView.TAG)?.visibility = GONE
        var root = salary.findViewWithTag<LinearLayout>(ROOT_TAG)
        if (root == null) {
            val legacy = LinearLayout(context).apply { tag = LEGACY_TAG; orientation = LinearLayout.VERTICAL; visibility = GONE }
            val oldChildren = ArrayList<View>()
            for (i in 0 until salary.childCount) oldChildren += salary.getChildAt(i)
            oldChildren.forEach { salary.removeView(it); legacy.addView(it) }
            salary.addView(legacy, LinearLayout.LayoutParams(1, 1))
            root = buildReorganizedRoot()
            salary.addView(root, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshTheme(root); refreshCompanies(root)
    }

    private fun buildReorganizedRoot() = LinearLayout(context).apply {
        tag = ROOT_TAG; orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(16))
        addView(actionButton("+ AJOUTER UNE ENTREPRISE") { showEnterpriseLookup() })
        addView(actionButton("FICHE DE RENSEIGNEMENTS") { showInformationSheet() }, buttonLp())
        addView(TextView(context).apply {
            tag = "salary_companies_title"; text = "MES ENTREPRISES"; textSize = 18f
            setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), dp(18), dp(8), dp(8))
        })
        addView(LinearLayout(context).apply { tag = "salary_companies_list"; orientation = LinearLayout.VERTICAL })
    }

    private fun refreshCompanies(root: LinearLayout) {
        val list = root.findViewWithTag<LinearLayout>("salary_companies_list") ?: return
        list.removeAllViews()
        val companies = SalaryCompanyStore.list(context)
        if (companies.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Aucune entreprise ajoutée"; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(12), dp(18), dp(12), dp(18))
            }); return
        }
        companies.forEach { company ->
            val title = buildString {
                append(company.name.ifBlank { "Entreprise" })
                if (company.siret.isNotBlank()) append("\nSIRET : ").append(company.siret)
            }
            list.addView(actionButton(title) { authenticateAndOpenCompany(company) }, buttonLp())
        }
    }

    private fun showEnterpriseLookup() {
        val lookup = EnterpriseLookupView(context)
        themedDialog("Ajouter une entreprise", ScrollView(context).apply { addView(lookup) })
    }

    private fun showInformationSheet() {
        val sheet = SalaryInformationSheetView(context)
        themedDialog("Fiche de renseignements", ScrollView(context).apply {
            isFillViewport = true; addView(sheet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun authenticateAndOpenCompany(company: SalaryCompanyStore.Company) {
        val activity = context as? android.app.Activity ?: return
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) { openCompanySpace(company); return }
        @Suppress("DEPRECATION")
        val intent: Intent? = keyguard.createConfirmDeviceCredentialIntent(
            "HoraTrack — accès sécurisé", "Authentifie-toi pour ouvrir les informations détaillées de l'entreprise."
        )
        if (intent == null) { openCompanySpace(company); return }
        activity.startActivity(intent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!activity.isFinishing) openCompanySpace(company) }, 900L)
    }

    private fun openCompanySpace(company: SalaryCompanyStore.Company) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(12))
            addView(TextView(context).apply {
                text = company.name.ifBlank { "Entreprise" } + if (company.siret.isBlank()) "" else "\nSIRET : ${company.siret}"
                textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(10))
            })
            addView(actionButton("INFORMATIONS ENTREPRISE") { showCompanyInformation(company) })
            addView(actionButton("CONTRAT") { showContract(company) }, buttonLp())
            addView(actionButton("FICHE DE SALAIRE") { showPayslipWorkspace() }, buttonLp())
            addView(actionButton("DROITS, CONGÉS & REPOS") { showRights() }, buttonLp())
        }
        themedDialog("Espace entreprise", ScrollView(context).apply { addView(box) })
    }

    private fun showCompanyInformation(company: SalaryCompanyStore.Company) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(18))
            addView(TextView(context).apply {
                textSize = 15f
                text = "Nom : ${company.name.ifBlank { "Non renseigné" }}\nSIRET : ${company.siret.ifBlank { "Non renseigné" }}\nAdresse : ${company.address.ifBlank { "Non renseignée" }}\nConvention : ${company.conventionName.ifBlank { if (company.idcc.isBlank()) "Non renseignée" else "IDCC ${company.idcc}" }}"
            })
            addView(actionButton("SUPPRIMER L’ENTREPRISE") { confirmDelete(company) }, buttonLp())
        }
        themedDialog("Informations entreprise", box)
    }

    private fun confirmDelete(company: SalaryCompanyStore.Company) {
        AlertDialog.Builder(context)
            .setTitle("Supprimer l’entreprise ?")
            .setMessage("${company.name.ifBlank { "Cette entreprise" }} sera retirée de MES ENTREPRISES. Les anciens moteurs HoraTrack ne sont pas supprimés.")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("SUPPRIMER") { _, _ ->
                SalaryCompanyStore.remove(context, company.id)
                rootView.findViewWithTag<LinearLayout>(ROOT_TAG)?.let { refreshCompanies(it) }
            }.show()
    }

    private fun showContract(company: SalaryCompanyStore.Company) {
        val prefs = SalaryCompanyStore.prefs(context, company.id)
        val rate = prefs.getString("hourly_rate", "").orEmpty(); val coefficient = prefs.getString("convention_coefficient", "").orEmpty()
        val type = prefs.getString("contract_type", "").orEmpty(); val weekly = prefs.getString("contract_weekly_hours", "").orEmpty()
        themedDialog("Contrat", TextView(context).apply {
            textSize = 15f; setPadding(dp(18), dp(12), dp(18), dp(18))
            text = "TAUX HORAIRE BRUT : ${rate.ifBlank { "Non renseigné" }}\n\nCOEFFICIENT CONVENTIONNEL : ${coefficient.ifBlank { "Non renseigné" }}\n\nTYPE DE CONTRAT : ${type.ifBlank { "Non renseigné" }}\n\nDURÉE HEBDOMADAIRE : ${weekly.ifBlank { "Non renseignée" }}"
        })
    }

    private fun showPayslipWorkspace() = themedDialog("Fiche de salaire", ScrollView(context).apply {
        addView(V2PayslipControlView(context), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    })
    private fun showRights() = themedDialog("Droits, congés & repos", ScrollView(context).apply {
        addView(V2RightsRestView(context), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    })

    private fun actionButton(label: String, click: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 14f; setOnClickListener { click() }; applyAccessTheme(this)
    }
    private fun buttonLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(8) }
    private fun refreshTheme(root: LinearLayout) {
        val theme = AppThemeCatalog.current(context); val dark = AppThemeCatalog.useDarkPalette(context)
        root.findViewWithTag<TextView>("salary_companies_title")?.setTextColor(if (dark) theme.darkText else theme.lightText)
        walkButtons(root) { applyAccessTheme(it) }
    }
    private fun walkButtons(group: ViewGroup, block: (Button) -> Unit) { for (i in 0 until group.childCount) when (val child = group.getChildAt(i)) { is Button -> block(child); is ViewGroup -> walkButtons(child, block) } }
    private fun applyAccessTheme(button: Button) {
        val theme = AppThemeCatalog.current(context); val dark = AppThemeCatalog.useDarkPalette(context)
        button.setTextColor(if (dark) theme.darkText else theme.lightText)
        button.background = when (theme.id) { "natural_carbon" -> CarbonCompositeDrawable(context); else -> context.getDrawable(R.drawable.hp_panel)?.mutate() }
    }
    private fun themedDialog(title: String, view: View) {
        val theme = AppThemeCatalog.current(context); val dark = AppThemeCatalog.useDarkPalette(context)
        val panelColor = if (dark) theme.darkPanel else theme.lightPanel; val accentColor = if (dark) theme.accentLight else theme.accent
        view.setBackgroundColor(panelColor)
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(view).setPositiveButton("FERMER", null).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(panelColor)); dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentColor) }
        dialog.show()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
