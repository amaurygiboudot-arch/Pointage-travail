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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Racine autonome de l'onglet Salaire V2.
 *
 * Elle ne dépend pas de SalaryPanelView : l'ancienne interface reste disponible séparément
 * pendant la migration et pourra être supprimée uniquement après validation complète de V2.
 */
class SalaryV2RootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        const val TAG = "salary_v2_root"
    }

    private var lastCompanySignature: String? = null

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(16))
        buildUi()
        refresh()
    }

    fun refresh(force: Boolean = false) {
        refreshTheme()
        refreshCompanies(force)
    }

    /** À appeler uniquement lorsque l'onglet Salaire est réellement affiché. */
    fun consumeAuthorizedAccess() {
        val id = PendingSalaryCompanyAccess.authorizedCompanyId ?: return
        PendingSalaryCompanyAccess.authorizedCompanyId = null
        SalaryCompanyStore.list(context).firstOrNull { it.id == id }?.let(::openCompanySpace)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && visibility == View.VISIBLE) {
            refresh()
            consumeAuthorizedAccess()
        }
    }

    private fun buildUi() {
        addView(
            actionButton("+ AJOUTER UNE ENTREPRISE") { showEnterpriseLookup() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
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

    private fun refreshCompanies(force: Boolean = false) {
        val list = findViewWithTag<LinearLayout>("salary_companies_list") ?: return
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
        requestLayout()
        invalidate()
    }

    private fun showEnterpriseLookup() {
        var dialog: AlertDialog? = null
        val lookup = V2SalaryCompanyLookupView(context) {
            lastCompanySignature = null
            refresh(force = true)
            dialog?.dismiss()
        }
        dialog = themedDialog("Ajouter une entreprise", ScrollView(context).apply {
            isFillViewport = true
            addView(lookup)
        })
    }

    private fun showInformationSheet(company: SalaryCompanyStore.Company) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(SalaryInformationSheetView(context).bindCompany(company.id))
            addView(
                CompanyPauseSettingsV2View(context, company.id),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        themedDialog("Fiche de renseignements — ${company.name.ifBlank { "Entreprise" }}", ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        })
    }

    private fun authenticateAndOpenCompany(company: SalaryCompanyStore.Company) {
        context.startActivity(
            Intent(context, SalaryAuthActivity::class.java)
                .putExtra(SalaryAuthActivity.EXTRA_COMPANY_ID, company.id)
        )
    }

    private fun openCompanySpace(company: SalaryCompanyStore.Company) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            addView(TextView(context).apply {
                text = company.name.ifBlank { "Entreprise" } +
                    if (company.siret.isBlank()) "" else "\nSIRET : ${company.siret}"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
            addView(actionButton("FICHE DE RENSEIGNEMENTS") { showInformationSheet(company) })
            addView(actionButton("INFORMATIONS ENTREPRISE") { showCompanyInformation(company) }, buttonLp())
            addView(actionButton("FICHE DE SALAIRE") { showPayslipWorkspace(company) }, buttonLp())
            addView(actionButton("SOURCES LÉGALES (LEGI)") { showLegalSources() }, buttonLp())
            addView(actionButton("RÈGLES CONVENTIONNELLES (KALI)") { showKaliSources(company) }, buttonLp())
            addView(actionButton("SOURCES CONVENTIONNELLES (BOCC)") { showBoccSources(company) }, buttonLp())
            addView(actionButton("PUBLICATIONS OFFICIELLES (JORF)") { showJorfSources() }, buttonLp())
            addView(actionButton("DROITS, CONGÉS & REPOS") { showRights(company) }, buttonLp())
        }
        themedDialog("Espace entreprise", ScrollView(context).apply { addView(box) })
    }

    private fun showCompanyInformation(company: SalaryCompanyStore.Company) {
        themedDialog("Informations entreprise", ScrollView(context).apply {
            addView(SalaryCompanyDetailsView(context, company, {
                lastCompanySignature = null
                refresh(force = true)
            }, { confirmDelete(it) }))
        })
    }

    private fun confirmDelete(company: SalaryCompanyStore.Company) {
        AlertDialog.Builder(context)
            .setTitle("Supprimer l’entreprise ?")
            .setMessage("${company.name.ifBlank { "Cette entreprise" }} sera retirée de MES ENTREPRISES.")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("SUPPRIMER") { _, _ ->
                SalaryCompanyStore.remove(context, company.id)
                lastCompanySignature = null
                refresh(force = true)
            }
            .show()
    }

    private fun showPayslipWorkspace(company: SalaryCompanyStore.Company) {
        themedDialog("Fiche de salaire", ScrollView(context).apply {
            addView(SalaryPayslipWorkspaceView(context, company))
        })
    }

    private fun showLegalSources() {
        themedDialog("Sources légales — LEGI", ScrollView(context).apply {
            isFillViewport = true
            addView(V2LegalPayrollSourcesView(context))
        })
    }

    private fun showKaliSources(company: SalaryCompanyStore.Company) {
        themedDialog("Règles conventionnelles — KALI", ScrollView(context).apply {
            isFillViewport = true
            addView(V2KaliPayrollSourcesView(context, company))
        })
    }

    private fun showBoccSources(company: SalaryCompanyStore.Company) {
        themedDialog("Sources conventionnelles — BOCC", ScrollView(context).apply {
            isFillViewport = true
            addView(V2BoccPayrollSourcesView(context, company))
        })
    }

    private fun showJorfSources() {
        themedDialog("Publications officielles — JORF", ScrollView(context).apply {
            isFillViewport = true
            addView(V2JorfPayrollSourcesView(context))
        })
    }

    private fun showRights(company: SalaryCompanyStore.Company) {
        themedDialog("Droits, congés & repos", ScrollView(context).apply {
            addView(V2RightsRestView(context, companyId = company.id))
        })
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setOnClickListener { click() }
        applyAccessTheme(this)
    }

    private fun buttonLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun refreshTheme() {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        findViewWithTag<TextView>("salary_companies_title")
            ?.setTextColor(if (dark) theme.darkText else theme.lightText)
        walkButtons(this) { applyAccessTheme(it) }
    }

    private fun walkButtons(group: ViewGroup, block: (Button) -> Unit) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is Button -> block(child)
                is ViewGroup -> walkButtons(child, block)
            }
        }
    }

    private fun applyAccessTheme(button: Button) {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        button.setTextColor(if (dark) theme.darkText else theme.lightText)
        button.background = when (theme.id) {
            "natural_carbon" -> CarbonCompositeDrawable(context)
            else -> context.getDrawable(R.drawable.hp_panel)?.mutate()
        }
        button.setPadding(dp(10), dp(12), dp(10), dp(12))
    }

    private fun themedDialog(title: String, view: View): AlertDialog {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val accent = if (dark) theme.accentLight else theme.accent
        view.setBackgroundColor(panel)
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("FERMER", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(panel))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        }
        dialog.show()
        return dialog
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
