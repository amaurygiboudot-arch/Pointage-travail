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
import android.widget.Toast
import com.amaury.pointage.v2.LegalAutoUpdateCoordinatorV2
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.SalaryLegacyRecovery
import com.amaury.pointage.v2.V2BackupManager

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

    private val hostContext = context
    private var accountContext = SalaryAccountScope.bindContext(hostContext)
    private val accountChanged: () -> Unit = {
        SalaryAccountUi.onMain { refresh(force = true) }
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
        if (!SalaryAccountScope.isCurrent(SalaryAccountScope.lease(accountContext))) {
            PendingSalaryCompanyAccess.clear()
            accountContext = SalaryAccountScope.bindContext(hostContext, SalaryAccountScope.capture())
            lastCompanySignature = null
            removeAllViews()
            buildUi()
        }
        refreshTheme()
        refreshCompanies(force)
    }

    /** À appeler uniquement lorsque l'onglet Salaire est réellement affiché. */
    fun consumeAuthorizedAccess() {
        if (PendingSalaryCompanyAccess.consumeRecovery()) {
            showLegacyRecovery()
            return
        }
        val id = PendingSalaryCompanyAccess.consume() ?: return
        val stored = SalaryCompanyStore.readConfirmed(accountContext)
        if (!stored.reliable) return
        stored.companies.firstOrNull { it.id == id }?.let(::openCompanySpace)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SalaryAccountScope.addListener(accountChanged)
        refresh(force = true)
    }

    override fun onDetachedFromWindow() {
        SalaryAccountScope.removeListener(accountChanged)
        super.onDetachedFromWindow()
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
            actionButton("+ AJOUTER UNE ENTREPRISE") { showEnterpriseLookup() }.apply {
                tag = "salary_add_company_button"
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(TextView(accountContext).apply {
            tag = "salary_companies_title"
            text = "MES ENTREPRISES"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(18), dp(8), dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(accountContext).apply {
            tag = "salary_companies_list"
            orientation = LinearLayout.VERTICAL
            visibility = VISIBLE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(actionButton("REPRENDRE MES ANCIENNES DONNÉES") {
            if (SalaryAccountScope.lease(accountContext).scope?.startsWith("uid:") != true) {
                showLegacyRecovery()
            } else {
                accountContext.startActivity(SalaryAccountUi.attach(
                    Intent(accountContext, SalaryAuthActivity::class.java)
                        .putExtra(SalaryAuthActivity.EXTRA_LEGACY_RECOVERY, true),
                    SalaryAccountScope.lease(accountContext)
                ))
            }
        }, buttonLp())
    }

    private fun showLegacyRecovery() {
        val originContext = accountContext
        if (!SalaryAccountUi.current(originContext)) return
        val local = SalaryLegacyRecovery.preview(originContext)
        val backups = V2BackupManager.legacySalaryRecoveries(originContext)
        val choices = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (local != null && !local.alreadyClaimed) {
            val label = "Sur cet appareil : ${local.fileCount} ensembles, ${local.entryCount} entrées"
            if (SalaryAccountScope.lease(originContext).scope?.startsWith("uid:") == true) {
                choices += label
                actions += { confirmLegacyOwnership(originContext, label) {
                    if (SalaryLegacyRecovery.claim(originContext, local.digest, ownershipConfirmed = true))
                        "Anciennes données rattachées à ce compte. Les originaux sont conservés."
                    else "Reprise refusée : source modifiée, compte changé ou données déjà présentes. Les originaux sont conservés."
                } }
            }
            if (!local.declined) {
                choices += "Ces anciennes données ne m'appartiennent pas : commencer séparément"
                actions += {
                    SalaryAccountUi.dialog(originContext).setTitle("Commencer séparément")
                        .setMessage("$label\n\nCes anciennes données ne seront ni utilisées dans ton salaire ni rattachées à ton compte. Elles restent conservées pour leur propriétaire. Confirme qu'elles ne t'appartiennent pas pour commencer un espace Salaire séparé.")
                        .setNegativeButton("ANNULER", null)
                        .setPositiveButton("CONFIRMER") { _, _ ->
                            if (SalaryAccountUi.current(originContext)) {
                                val saved = SalaryLegacyRecovery.decline(originContext, local.digest, confirmed = true)
                                refresh(force = true)
                                Toast.makeText(hostContext, if (saved) "Espace Salaire séparé ouvert ; anciennes données conservées." else "Confirmation impossible : vérifie le compte et réessaie.", Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
            }
        }
        backups.forEach { backup ->
            val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAtMs))
            val label = "Sauvegarde du $date : ${backup.fileCount} ensembles"
            choices += label
            actions += { confirmLegacyOwnership(originContext, label) {
                V2BackupManager.confirmLegacySalaryRestore(originContext, backup.id, backup.fingerprint, ownershipConfirmed = true)
                    .fold({ it.summary() }, { "Reprise refusée : ${it.message ?: "données à vérifier"}. La sauvegarde d'origine est conservée." })
            } }
        }
        if (choices.isEmpty()) {
            SalaryAccountUi.dialog(originContext).setTitle("Reprise des anciennes données")
                .setMessage("Aucune donnée salariale sans propriétaire n'est disponible pour ce compte. Les données déjà rattachées restent dans leur compte.")
                .setPositiveButton("FERMER", null).show()
            return
        }
        SalaryAccountUi.dialog(originContext).setTitle("Choisir les données à reprendre")
            .setItems(choices.toTypedArray()) { _, which ->
                if (SalaryAccountUi.current(originContext)) actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton("ANNULER", null).show()
    }

    private fun confirmLegacyOwnership(originContext: Context, description: String, claim: () -> String) {
        SalaryAccountUi.dialog(originContext).setTitle("Confirmer le propriétaire")
            .setMessage("$description\n\nJe confirme que ces données salariales sont les miennes et que je souhaite les rattacher au compte actuellement connecté.\n\nLa reprise exige un espace Salaire vide ; elle ne remplace pas des données existantes. Les originaux sont conservés.")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("JE CONFIRME") { _, _ ->
                if (!SalaryAccountUi.current(originContext)) return@setPositiveButton
                Thread {
                    val message = runCatching { claim() }.getOrElse { "Reprise interrompue : réessaie après vérification du compte. Les originaux sont conservés." }
                    post {
                        val current = SalaryAccountScope.capture()
                        if (current.scope == null || current.scope != SalaryAccountScope.lease(originContext).scope) return@post
                        refresh(force = true)
                        SalaryAccountUi.dialog(SalaryAccountScope.bindContext(hostContext, current)).setTitle("Reprise des données")
                            .setMessage(message).setPositiveButton("FERMER", null).show()
                    }
                }.start()
            }.show()
    }

    private fun refreshCompanies(force: Boolean = false) {
        val list = findViewWithTag<LinearLayout>("salary_companies_list") ?: return
        val stored = SalaryCompanyStore.readConfirmed(accountContext)
        findViewWithTag<Button>("salary_add_company_button")?.isEnabled = stored.reliable
        val statusText = salaryCompanyStorageStatusText(stored)
        val companies = stored.companies
        val signature = buildString {
            append(SalaryAccountScope.lease(accountContext))
            append(':')
            append(if (stored.reliable) "reliable" else "unreliable")
            append(':').append(stored.repairedFromBackup)
            append(':').append(statusText.orEmpty())
            append(':').append(companies.joinToString("|") { "${it.id}:${it.name}:${it.siret}:${it.address}:${it.idcc}" })
        }
        if (!force && signature == lastCompanySignature && list.childCount > 0) return
        lastCompanySignature = signature

        list.removeAllViews()
        list.visibility = VISIBLE
        if (statusText != null) {
            list.addView(TextView(accountContext).apply {
                text = statusText
                textSize = 14f
                setPadding(dp(12), dp(14), dp(12), dp(14))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (!stored.reliable) {
            list.requestLayout()
            requestLayout()
            invalidate()
            return
        }

        if (companies.isEmpty()) {
            list.addView(TextView(accountContext).apply {
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
                val row = LinearLayout(accountContext).apply {
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
        val stored = SalaryCompanyStore.readConfirmed(accountContext)
        if (!stored.reliable) return
        var dialog: AlertDialog? = null
        val lookup = V2SalaryCompanyLookupView(accountContext) {
            lastCompanySignature = null
            refresh(force = true)
            dialog?.dismiss()
        }
        dialog = themedDialog("Ajouter une entreprise", ScrollView(accountContext).apply {
            isFillViewport = true
            addView(lookup)
        })
    }

    private fun showInformationSheet(company: SalaryCompanyStore.Company) {
        val content = LinearLayout(accountContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(SalaryInformationSheetView(accountContext).bindCompany(company.id))
            addView(
                CompanyPauseSettingsV2View(accountContext, company.id),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        themedDialog("Fiche de renseignements — ${company.name.ifBlank { "Entreprise" }}", ScrollView(accountContext).apply {
            isFillViewport = true
            addView(content)
        })
    }

    private fun authenticateAndOpenCompany(company: SalaryCompanyStore.Company) {
        accountContext.startActivity(
            SalaryAccountUi.attach(
                Intent(accountContext, SalaryAuthActivity::class.java)
                    .putExtra(SalaryAuthActivity.EXTRA_COMPANY_ID, company.id),
                SalaryAccountScope.lease(accountContext)
            )
        )
    }

    private fun openCompanySpace(company: SalaryCompanyStore.Company) {
        val legalWatchStatus = TextView(accountContext).apply {
            text = "Veille juridique : vérification automatique…"
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        val box = LinearLayout(accountContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            addView(TextView(accountContext).apply {
                text = company.name.ifBlank { "Entreprise" } +
                    if (company.siret.isBlank()) "" else "\nSIRET : ${company.siret}"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
            addView(legalWatchStatus)
            addView(actionButton("FICHE DE RENSEIGNEMENTS") { showInformationSheet(company) })
            addView(actionButton("MES RÈGLES DE TRAVAIL DATÉES") { showDatedWorkRules(company) }, buttonLp())
            addView(actionButton("INFORMATIONS ENTREPRISE") { showCompanyInformation(company) }, buttonLp())
            addView(actionButton("FICHE DE SALAIRE") { showPayslipWorkspace(company) }, buttonLp())
            addView(actionButton("SOURCES LÉGALES (LEGI)") { showLegalSources() }, buttonLp())
            addView(actionButton("RÈGLES CONVENTIONNELLES (KALI)") { showKaliSources(company) }, buttonLp())
            addView(actionButton("SOURCES CONVENTIONNELLES (BOCC)") { showBoccSources(company) }, buttonLp())
            addView(actionButton("PUBLICATIONS OFFICIELLES (JORF)") { showJorfSources() }, buttonLp())
            addView(actionButton("DROITS, CONGÉS & REPOS") { showRights(company) }, buttonLp())
        }
        themedDialog("Espace entreprise", ScrollView(accountContext).apply { addView(box) })
        runLegalAutoUpdate(company, legalWatchStatus)
    }

    private fun runLegalAutoUpdate(company: SalaryCompanyStore.Company, statusView: TextView) {
        val lease = SalaryAccountScope.lease(accountContext)
        LegalAutoUpdateCoordinatorV2.sync(accountContext, company)
            .addOnSuccessListener { summary ->
                if (!SalaryAccountScope.isCurrent(lease)) return@addOnSuccessListener
                statusView.text = legalAutoStatus(summary)
            }
            .addOnFailureListener {
                if (!SalaryAccountScope.isCurrent(lease)) return@addOnFailureListener
                statusView.text = "Veille juridique : synchronisation différée."
            }
    }

    private fun legalAutoStatus(summary: LegalAutoUpdateCoordinatorV2.Summary): String {
        if (summary.warnings.any { it.contains("différée", ignoreCase = true) }) {
            return "Veille juridique : synchronisation différée."
        }
        if (summary.hadAutomaticWork) {
            val details = mutableListOf<String>()
            if (summary.kaliAuditsRun > 0) {
                details += if (summary.kaliRuleSaved) "KALI actualisé" else "KALI contrôlé"
            }
            if (summary.legiAuditsRun > 0) {
                details += "LEGI contrôlé (${summary.legiVerifiedArticles} article(s))"
            }
            if (summary.unsupportedAccoJobs > 0) details += "ACCO à approfondir"
            return "Veille juridique : ${details.joinToString(" · ")}"
        }
        if (summary.unsupportedAccoJobs > 0) {
            return "Veille juridique : accord d’entreprise détecté — analyse paie détaillée en attente."
        }
        if (summary.readyJobs == 0) return "Veille juridique : à jour."
        return "Veille juridique : mises à jour déjà analysées sur cet appareil."
    }

    private fun showDatedWorkRules(company: SalaryCompanyStore.Company) {
        themedDialog("Règles de travail — ${company.name}", ScrollView(accountContext).apply {
            addView(DatedWorkRulesEditorV2(accountContext, company.id))
        })
    }

    private fun showCompanyInformation(company: SalaryCompanyStore.Company) {
        themedDialog("Informations entreprise", ScrollView(accountContext).apply {
            addView(SalaryCompanyDetailsView(accountContext, company, {
                lastCompanySignature = null
                refresh(force = true)
            }, { confirmDelete(it) }))
        })
    }

    private fun confirmDelete(company: SalaryCompanyStore.Company) {
        val accountContext = accountContext
        SalaryAccountUi.dialog(accountContext)
            .setTitle("Supprimer l’entreprise ?")
            .setMessage("${company.name.ifBlank { "Cette entreprise" }} sera retirée de MES ENTREPRISES.")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("SUPPRIMER") { _, _ ->
                SalaryCompanyStore.remove(accountContext, company.id)
                lastCompanySignature = null
                refresh(force = true)
            }
            .show()
    }

    private fun showPayslipWorkspace(company: SalaryCompanyStore.Company) {
        themedDialog("Fiche de salaire", ScrollView(accountContext).apply {
            addView(SalaryPayslipWorkspaceView(accountContext, company))
        })
    }

    private fun showLegalSources() {
        themedDialog("Sources légales — LEGI", ScrollView(accountContext).apply {
            isFillViewport = true
            addView(V2LegalPayrollSourcesView(accountContext))
        })
    }

    private fun showKaliSources(company: SalaryCompanyStore.Company) {
        themedDialog("Règles conventionnelles — KALI", ScrollView(accountContext).apply {
            isFillViewport = true
            addView(V2KaliPayrollSourcesView(accountContext, company))
        })
    }

    private fun showBoccSources(company: SalaryCompanyStore.Company) {
        themedDialog("Sources conventionnelles — BOCC", ScrollView(accountContext).apply {
            isFillViewport = true
            addView(V2BoccPayrollSourcesView(accountContext, company))
        })
    }

    private fun showJorfSources() {
        themedDialog("Publications officielles — JORF", ScrollView(accountContext).apply {
            isFillViewport = true
            addView(V2JorfPayrollSourcesView(accountContext))
        })
    }

    private fun showRights(company: SalaryCompanyStore.Company) {
        themedDialog("Droits, congés & repos", ScrollView(accountContext).apply {
            addView(V2RightsRestView(accountContext, companyId = company.id))
        })
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(accountContext).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        val origin = SalaryAccountScope.lease(accountContext)
        setOnClickListener { if (SalaryAccountScope.isCurrent(origin)) click() else refresh(force = true) }
        applyAccessTheme(this)
    }

    private fun buttonLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun refreshTheme() {
        val theme = AppThemeCatalog.current(accountContext)
        val dark = AppThemeCatalog.useDarkPalette(accountContext)
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
        val theme = AppThemeCatalog.current(accountContext)
        val dark = AppThemeCatalog.useDarkPalette(accountContext)
        button.setTextColor(if (dark) theme.darkText else theme.lightText)
        button.background = when (theme.id) {
            "natural_carbon" -> CarbonCompositeDrawable(accountContext)
            else -> accountContext.getDrawable(R.drawable.hp_panel)?.mutate()
        }
        button.setPadding(dp(10), dp(12), dp(10), dp(12))
    }

    /**
     * Les vues juridiques sont créées avec le contexte de l'activité. Selon le thème Android,
     * certains TextView/Buttons héritent alors d'un texte sombre même quand le panneau du dialogue
     * est sombre. On ne remplace que les couleurs réellement illisibles afin de préserver les
     * accents déjà suffisamment contrastés.
     */
    private fun ensureDialogTextContrast(view: View, panel: Int) {
        if (view is TextView && AppearanceManager.contrastRatio(view.currentTextColor, panel) < 4.5) {
            view.setTextColor(AppearanceManager.bestTextColor(panel))
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                ensureDialogTextContrast(view.getChildAt(index), panel)
            }
        }
    }

    private fun themedDialog(title: String, view: View): AlertDialog {
        val theme = AppThemeCatalog.current(accountContext)
        val dark = AppThemeCatalog.useDarkPalette(accountContext)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val accent = if (dark) theme.accentLight else theme.accent
        val readableText = AppearanceManager.bestTextColor(panel)
        view.setBackgroundColor(panel)
        ensureDialogTextContrast(view, panel)
        val dialog = SalaryAccountUi.dialog(accountContext)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("FERMER", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(panel))
            val alertTitleId = resources.getIdentifier("alertTitle", "id", "android")
            if (alertTitleId != 0) dialog.findViewById<TextView>(alertTitleId)?.setTextColor(readableText)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
            ensureDialogTextContrast(view, panel)
        }
        dialog.show()
        return dialog
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
