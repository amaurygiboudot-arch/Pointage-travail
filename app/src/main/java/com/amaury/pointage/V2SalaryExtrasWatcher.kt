package com.amaury.pointage

import android.app.AlertDialog
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

class V2SalaryExtrasWatcher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), ViewTreeObserver.OnGlobalLayoutListener {

    companion object {
        const val TAG = "v2_salary_extras_watcher"
        private const val ROOT_TAG = "salary_v2_reorganized_root"
        private const val LEGACY_TAG = "salary_v2_legacy_container"
    }

    private var lastCompanySignature: String? = null

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
            val old = ArrayList<View>()
            for (i in 0 until salary.childCount) old += salary.getChildAt(i)
            old.forEach { salary.removeView(it); legacy.addView(it) }
            salary.addView(legacy, LinearLayout.LayoutParams(1, 1))
            root = buildRoot()
            salary.addView(root, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            lastCompanySignature = null
        }
        refreshTheme(root)
        refreshCompanies(root)
        consumeAuthorizedAccess()
    }

    private fun buildRoot() = LinearLayout(context).apply {
        tag = ROOT_TAG
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(16))
        addView(actionButton("+ AJOUTER UNE ENTREPRISE") { showEnterpriseLookup() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            tag = "salary_companies_title"
            text = "MES ENTREPRISES"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(18), dp(8), dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            tag = "salary_companies_list"
            orientation = LinearLayout.VERTICAL
            visibility = VISIBLE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshCompanies(root: LinearLayout, force: Boolean = false) {
        val list = root.findViewWithTag<LinearLayout>("salary_companies_list") ?: return
        val companies = SalaryCompanyStore.list(context)
        val signature = companies.joinToString("|") { "${it.id}:${it.name}:${it.siret}:${it.address}:${it.idcc}" }
        if (!force && signature == lastCompanySignature && list.childCount > 0) return
        lastCompanySignature = signature

        list.removeAllViews()
        list.visibility = VISIBLE
        if (companies.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Aucune entreprise ajoutée"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(18), dp(12), dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            companies.forEach { company ->
                val label = buildString {
                    append(company.name.ifBlank { "Entreprise" })
                    if (company.siret.isNotBlank()) append("\nSIRET : ${company.siret}")
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        actionButton(label) { authenticateAndOpenCompany(company) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        actionButton("✕") { confirmDelete(company) }.apply {
                            contentDescription = "Supprimer ${company.name.ifBlank { "cette entreprise" }}"
                            textSize = 18f
                            setPadding(dp(6), dp(12), dp(6), dp(12))
                        },
                        LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(8)
                        }
                    )
                }
                list.addView(row, buttonLp())
            }
        }

        list.requestLayout()
        root.requestLayout()
        (root.parent as? View)?.requestLayout()
        list.invalidate()
        root.invalidate()
    }

    private fun showEnterpriseLookup() {
        val salaryRoot = rootView.findViewWithTag<LinearLayout>(ROOT_TAG)
        var dialog: AlertDialog? = null
        val lookup = V2SalaryCompanyLookupView(context) {
            lastCompanySignature = null
            val target = salaryRoot ?: rootView.findViewWithTag<LinearLayout>(ROOT_TAG)
            target?.let {
                refreshCompanies(it, force = true)
                refreshTheme(it)
                it.requestLayout()
                it.invalidate()
            }
            dialog?.dismiss()
        }
        dialog = themedDialog("Ajouter une entreprise", ScrollView(context).apply { isFillViewport = true; addView(lookup) })
    }

    private fun showInformationSheet(company: SalaryCompanyStore.Company) {
        themedDialog("Fiche de renseignements — ${company.name.ifBlank { "Entreprise" }}", ScrollView(context).apply {
            isFillViewport = true
            addView(SalaryInformationSheetView(context).bindCompany(company.id))
        })
    }

    private fun authenticateAndOpenCompany(company: SalaryCompanyStore.Company) {
        context.startActivity(Intent(context, SalaryAuthActivity::class.java).putExtra(SalaryAuthActivity.EXTRA_COMPANY_ID, company.id))
    }

    private fun consumeAuthorizedAccess() {
        val id = PendingSalaryCompanyAccess.authorizedCompanyId ?: return
        PendingSalaryCompanyAccess.authorizedCompanyId = null
        SalaryCompanyStore.list(context).firstOrNull { it.id == id }?.let(::openCompanySpace)
    }

    private fun openCompanySpace(company: SalaryCompanyStore.Company) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            addView(TextView(context).apply {
                text = company.name.ifBlank { "Entreprise" } + if (company.siret.isBlank()) "" else "\nSIRET : ${company.siret}"
                textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(10))
            })
            addView(actionButton("FICHE DE RENSEIGNEMENTS") { showInformationSheet(company) })
            addView(actionButton("INFORMATIONS ENTREPRISE") { showCompanyInformation(company) }, buttonLp())
            addView(actionButton("FICHE DE SALAIRE") { showPayslipWorkspace(company) }, buttonLp())
            addView(actionButton("DROITS, CONGÉS & REPOS") { showRights(company) }, buttonLp())
        }
        themedDialog("Espace entreprise", ScrollView(context).apply { addView(box) })
    }

    private fun showCompanyInformation(company: SalaryCompanyStore.Company) {
        themedDialog("Informations entreprise", ScrollView(context).apply {
            addView(SalaryCompanyDetailsView(context, company, {
                lastCompanySignature = null
                rootView.findViewWithTag<LinearLayout>(ROOT_TAG)?.let { root -> refreshCompanies(root, force = true) }
            }, { confirmDelete(it) }))
        })
    }

    private fun confirmDelete(company: SalaryCompanyStore.Company) {
        AlertDialog.Builder(context).setTitle("Supprimer l’entreprise ?")
            .setMessage("${company.name.ifBlank { "Cette entreprise" }} sera retirée de MES ENTREPRISES.")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("SUPPRIMER") { _, _ ->
                SalaryCompanyStore.remove(context, company.id)
                lastCompanySignature = null
                rootView.findViewWithTag<LinearLayout>(ROOT_TAG)?.let { root -> refreshCompanies(root, force = true) }
            }
            .show()
    }

    private fun showContract(company: SalaryCompanyStore.Company) { themedDialog("Contrat", ScrollView(context).apply { addView(SalaryContractDetailsView(context, company)) }) }
    private fun showPayslipWorkspace(company: SalaryCompanyStore.Company) { themedDialog("Fiche de salaire", ScrollView(context).apply { addView(SalaryPayslipWorkspaceView(context, company)) }) }
    private fun showRights(company: SalaryCompanyStore.Company) { themedDialog("Droits, congés & repos", ScrollView(context).apply { addView(V2RightsRestView(context, companyId = company.id)) }) }

    private fun actionButton(label: String, click: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 14f; setOnClickListener { click() }; applyAccessTheme(this)
    }
    private fun buttonLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }

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
        button.setPadding(dp(10), dp(12), dp(10), dp(12))
    }
    private fun themedDialog(title: String, view: View): AlertDialog {
        val theme = AppThemeCatalog.current(context); val dark = AppThemeCatalog.useDarkPalette(context)
        val panel = if (dark) theme.darkPanel else theme.lightPanel; val accent = if (dark) theme.accentLight else theme.accent
        view.setBackgroundColor(panel)
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(view).setPositiveButton("FERMER", null).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(panel)); dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent) }
        dialog.show(); return dialog
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

object PendingSalaryCompanyAccess { var authorizedCompanyId: String? = null }
