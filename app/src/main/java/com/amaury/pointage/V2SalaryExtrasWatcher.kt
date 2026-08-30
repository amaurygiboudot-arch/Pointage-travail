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

class V2SalaryExtrasWatcher @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), ViewTreeObserver.OnGlobalLayoutListener {
    companion object { const val TAG = "v2_salary_extras_watcher"; private const val ROOT_TAG = "salary_v2_reorganized_root"; private const val LEGACY_TAG = "salary_v2_legacy_container" }
    init { tag = TAG; visibility = GONE }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); rootView.viewTreeObserver.addOnGlobalLayoutListener(this); installIfPresent() }
    override fun onDetachedFromWindow() { if (rootView.viewTreeObserver.isAlive) rootView.viewTreeObserver.removeOnGlobalLayoutListener(this); super.onDetachedFromWindow() }
    override fun onGlobalLayout() = installIfPresent()
    private fun installIfPresent() {
        val content = rootView.findViewById<LinearLayout>(R.id.contentPanel) ?: return; val salary = content.findViewWithTag<SalaryPanelView>("integrated_salary_panel") ?: return
        content.findViewWithTag<SalaryInformationSheetView>(SalaryInformationSheetView.TAG)?.visibility = GONE
        var root = salary.findViewWithTag<LinearLayout>(ROOT_TAG)
        if (root == null) {
            val legacy = LinearLayout(context).apply { tag = LEGACY_TAG; orientation = LinearLayout.VERTICAL; visibility = GONE }; val old = ArrayList<View>()
            for (i in 0 until salary.childCount) old += salary.getChildAt(i); old.forEach { salary.removeView(it); legacy.addView(it) }; salary.addView(legacy, LinearLayout.LayoutParams(1, 1))
            root = buildRoot(); salary.addView(root, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }; refreshTheme(root); refreshCompanies(root)
    }
    private fun buildRoot() = LinearLayout(context).apply {
        tag = ROOT_TAG; orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(16)); addView(actionButton("+ AJOUTER UNE ENTREPRISE") { showEnterpriseLookup() }); addView(actionButton("FICHE DE RENSEIGNEMENTS") { showInformationSheet() }, buttonLp())
        addView(TextView(context).apply { tag = "salary_companies_title"; text = "MES ENTREPRISES"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), dp(18), dp(8), dp(8)) }); addView(LinearLayout(context).apply { tag = "salary_companies_list"; orientation = LinearLayout.VERTICAL })
    }
    private fun refreshCompanies(root: LinearLayout) {
        val list = root.findViewWithTag<LinearLayout>("salary_companies_list") ?: return; list.removeAllViews(); val companies = SalaryCompanyStore.list(context)
        if (companies.isEmpty()) { list.addView(TextView(context).apply { text = "Aucune entreprise ajoutée"; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(12), dp(18), dp(12), dp(18)) }); return }
        companies.forEach { c -> list.addView(actionButton(c.name.ifBlank { "Entreprise" } + if (c.siret.isBlank()) "" else "\nSIRET : ${c.siret}") { authenticateAndOpenCompany(c) }, buttonLp()) }
    }
    private fun showEnterpriseLookup() = themedDialog("Ajouter une entreprise", ScrollView(context).apply { addView(EnterpriseLookupView(context)) })
    private fun showInformationSheet() = themedDialog("Fiche de renseignements", ScrollView(context).apply { isFillViewport = true; addView(SalaryInformationSheetView(context)) })

    private fun authenticateAndOpenCompany(company: SalaryCompanyStore.Company) {
        val activity = context as? android.app.Activity ?: return; val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) { AlertDialog.Builder(context).setTitle("Sécurité Android requise").setMessage("Configure un verrouillage d’écran Android (PIN, schéma, mot de passe ou biométrie) pour ouvrir les données détaillées de l’entreprise.").setPositiveButton("OK", null).show(); return }
        @Suppress("DEPRECATION") val intent: Intent? = keyguard.createConfirmDeviceCredentialIntent("HoraTrack — accès sécurisé", "Authentifie-toi pour ouvrir les informations détaillées de l'entreprise.")
        if (intent == null) { AlertDialog.Builder(context).setMessage("L’authentification Android n’est pas disponible sur cet appareil.").setPositiveButton("OK", null).show(); return }
        // MainActivity est une Activity Android classique. L'ouverture automatique non vérifiée a été supprimée : aucune donnée détaillée n'est affichée après un simple délai.
        PendingSalaryCompanyAccess.company = company
        activity.startActivityForResult(intent, PendingSalaryCompanyAccess.REQUEST_CODE)
    }

    private fun openCompanySpace(company: SalaryCompanyStore.Company) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(12)); addView(TextView(context).apply { text = company.name.ifBlank { "Entreprise" } + if (company.siret.isBlank()) "" else "\nSIRET : ${company.siret}"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(10)) }); addView(actionButton("INFORMATIONS ENTREPRISE") { showCompanyInformation(company) }); addView(actionButton("CONTRAT") { showContract(company) }, buttonLp()); addView(actionButton("FICHE DE SALAIRE") { showPayslipWorkspace() }, buttonLp()); addView(actionButton("DROITS, CONGÉS & REPOS") { showRights() }, buttonLp()) }
        themedDialog("Espace entreprise", ScrollView(context).apply { addView(box) })
    }
    private fun showCompanyInformation(company: SalaryCompanyStore.Company) = themedDialog("Informations entreprise", ScrollView(context).apply { addView(SalaryCompanyDetailsView(context, company, { updated -> rootView.findViewWithTag<LinearLayout>(ROOT_TAG)?.let { refreshCompanies(it) } }, { target -> confirmDelete(target) })) })
    private fun confirmDelete(company: SalaryCompanyStore.Company) { AlertDialog.Builder(context).setTitle("Supprimer l’entreprise ?").setMessage("${company.name.ifBlank { "Cette entreprise" }} sera retirée de MES ENTREPRISES.").setNegativeButton("ANNULER", null).setPositiveButton("SUPPRIMER") { _, _ -> SalaryCompanyStore.remove(context, company.id); rootView.findViewWithTag<LinearLayout>(ROOT_TAG)?.let { refreshCompanies(it) } }.show() }
    private fun showContract(company: SalaryCompanyStore.Company) = themedDialog("Contrat", ScrollView(context).apply { addView(SalaryContractDetailsView(context, company)) })
    private fun showPayslipWorkspace() = themedDialog("Fiche de salaire", ScrollView(context).apply { addView(V2PayslipControlView(context)) })
    private fun showRights() = themedDialog("Droits, congés & repos", ScrollView(context).apply { addView(V2RightsRestView(context)) })

    /** Appelé par MainActivity uniquement après RESULT_OK de l'authentification Android. */
    fun consumeAuthenticatedCompanyAccess() { val c = PendingSalaryCompanyAccess.company ?: return; PendingSalaryCompanyAccess.company = null; openCompanySpace(c) }

    private fun actionButton(label: String, click: () -> Unit) = Button(context).apply { text = label; isAllCaps = false; textSize = 14f; setOnClickListener { click() }; applyAccessTheme(this) }
    private fun buttonLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(8) }
    private fun refreshTheme(root: LinearLayout) { val t = AppThemeCatalog.current(context); val d = AppThemeCatalog.useDarkPalette(context); root.findViewWithTag<TextView>("salary_companies_title")?.setTextColor(if (d) t.darkText else t.lightText); walkButtons(root) { applyAccessTheme(it) } }
    private fun walkButtons(group: ViewGroup, block: (Button) -> Unit) { for (i in 0 until group.childCount) when (val c = group.getChildAt(i)) { is Button -> block(c); is ViewGroup -> walkButtons(c, block) } }
    private fun applyAccessTheme(button: Button) { val t = AppThemeCatalog.current(context); val d = AppThemeCatalog.useDarkPalette(context); button.setTextColor(if (d) t.darkText else t.lightText); button.background = when (t.id) { "natural_carbon" -> CarbonCompositeDrawable(context); else -> context.getDrawable(R.drawable.hp_panel)?.mutate() } }
    private fun themedDialog(title: String, view: View) { val t = AppThemeCatalog.current(context); val d = AppThemeCatalog.useDarkPalette(context); val panel = if (d) t.darkPanel else t.lightPanel; val accent = if (d) t.accentLight else t.accent; view.setBackgroundColor(panel); val dialog = AlertDialog.Builder(context).setTitle(title).setView(view).setPositiveButton("FERMER", null).create(); dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(panel)); dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent) }; dialog.show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

object PendingSalaryCompanyAccess { const val REQUEST_CODE = 4716; var company: SalaryCompanyStore.Company? = null }
