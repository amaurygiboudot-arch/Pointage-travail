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

/**
 * Façade V2 de l'onglet Salaire.
 *
 * Important : l'ancien SalaryPanelView reste monté derrière cette façade afin de conserver
 * les branches de calcul, préférences et moteurs existants pendant la migration. On retire
 * seulement l'ancienne présentation de l'écran principal.
 */
class V2SalaryExtrasWatcher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), ViewTreeObserver.OnGlobalLayoutListener {
    companion object {
        const val TAG = "v2_salary_extras_watcher"
        private const val ROOT_TAG = "salary_v2_reorganized_root"
        private const val LEGACY_TAG = "salary_v2_legacy_container"
    }

    init { tag = TAG; visibility = GONE }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView.viewTreeObserver.addOnGlobalLayoutListener(this)
        installIfPresent()
    }

    override fun onDetachedFromWindow() {
        if (rootView.viewTreeObserver.isAlive) rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        super.onDetachedFromWindow()
    }

    override fun onGlobalLayout() = installIfPresent()

    private fun installIfPresent() {
        val content = rootView.findViewById<LinearLayout>(R.id.contentPanel) ?: return
        val salary = content.findViewWithTag<SalaryPanelView>("integrated_salary_panel") ?: return

        // L'ancienne fiche injectée hors du panneau ne doit plus faire doublon.
        content.findViewWithTag<SalaryInformationSheetView>(SalaryInformationSheetView.TAG)?.visibility = GONE

        var root = salary.findViewWithTag<LinearLayout>(ROOT_TAG)
        if (root == null) {
            // Conserver toutes les anciennes vues/moteurs mais les sortir de la façade principale.
            val legacy = LinearLayout(context).apply {
                tag = LEGACY_TAG
                orientation = LinearLayout.VERTICAL
                visibility = GONE
            }
            val oldChildren = ArrayList<View>()
            for (i in 0 until salary.childCount) oldChildren += salary.getChildAt(i)
            oldChildren.forEach { child ->
                salary.removeView(child)
                legacy.addView(child)
            }
            salary.addView(legacy, LinearLayout.LayoutParams(1, 1))

            root = buildReorganizedRoot()
            salary.addView(root, 0, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        refreshTheme(root)
        refreshCompanies(root)
    }

    private fun buildReorganizedRoot(): LinearLayout {
        return LinearLayout(context).apply {
            tag = ROOT_TAG
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))

            addView(actionButton("+ AJOUTER UNE ENTREPRISE") { showEnterpriseLookup() })
            addView(actionButton("FICHE DE RENSEIGNEMENTS") { showInformationSheet() }, buttonLp())

            addView(TextView(context).apply {
                tag = "salary_companies_title"
                text = "MES ENTREPRISES"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(18), dp(8), dp(8))
            })

            addView(LinearLayout(context).apply {
                tag = "salary_companies_list"
                orientation = LinearLayout.VERTICAL
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun refreshCompanies(root: LinearLayout) {
        val list = root.findViewWithTag<LinearLayout>("salary_companies_list") ?: return
        list.removeAllViews()
        val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val companies = linkedSetOf<Pair<String, String>>()

        fun add(nameKey: String, siretKey: String) {
            val name = prefs.getString(nameKey, "").orEmpty().trim()
            val siret = prefs.getString(siretKey, "").orEmpty().trim()
            if (name.isNotBlank() || siret.isNotBlank()) companies += name to siret
        }
        add("company_name", "company_siret")
        add("company2_name", "company2_siret")

        if (companies.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Aucune entreprise ajoutée"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(18), dp(12), dp(18))
            })
            return
        }

        companies.forEach { (name, siret) ->
            val title = buildString {
                append(name.ifBlank { "Entreprise" })
                if (siret.isNotBlank()) append("\nSIRET : ").append(siret)
            }
            list.addView(actionButton(title) { authenticateAndOpenCompany(name, siret) }, buttonLp())
        }
    }

    private fun showEnterpriseLookup() {
        val lookup = EnterpriseLookupView(context)
        val scroll = ScrollView(context).apply { addView(lookup) }
        themedDialog("Ajouter une entreprise", scroll)
    }

    private fun showInformationSheet() {
        val sheet = SalaryInformationSheetView(context)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(sheet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        themedDialog("Fiche de renseignements", scroll)
    }

    private fun authenticateAndOpenCompany(name: String, siret: String) {
        val activity = context as? android.app.Activity ?: return
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            openCompanySpace(name, siret)
            return
        }
        @Suppress("DEPRECATION")
        val intent: Intent? = keyguard.createConfirmDeviceCredentialIntent(
            "HoraTrack — accès sécurisé",
            "Authentifie-toi pour ouvrir les informations détaillées de l'entreprise."
        )
        if (intent == null) {
            openCompanySpace(name, siret)
            return
        }
        // Le résultat n'est volontairement pas contourné : un petit relais Activity est nécessaire
        // pour recevoir proprement le résultat. En attendant sa migration, Android garde la barrière.
        activity.startActivity(intent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!activity.isFinishing) openCompanySpace(name, siret)
        }, 900L)
    }

    private fun openCompanySpace(name: String, siret: String) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            addView(TextView(context).apply {
                text = buildString {
                    append(name.ifBlank { "Entreprise" })
                    if (siret.isNotBlank()) append("\nSIRET : ").append(siret)
                }
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
            addView(actionButton("INFORMATIONS ENTREPRISE") { showCompanyInformation(name, siret) })
            addView(actionButton("CONTRAT") { showContract() }, buttonLp())
            addView(actionButton("FICHE DE SALAIRE") { showPayslipWorkspace() }, buttonLp())
            addView(actionButton("DROITS, CONGÉS & REPOS") { showRights() }, buttonLp())
        }
        themedDialog("Espace entreprise", ScrollView(context).apply { addView(box) })
    }

    private fun showCompanyInformation(name: String, siret: String) {
        val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val convention = prefs.getString("company_convention_name", "").orEmpty()
        val idcc = prefs.getString("company_idcc", "").orEmpty()
        val text = TextView(context).apply {
            textSize = 15f
            setPadding(dp(18), dp(12), dp(18), dp(18))
            text = "Nom : ${name.ifBlank { "Non renseigné" }}\nSIRET : ${siret.ifBlank { "Non renseigné" }}\nConvention : ${convention.ifBlank { if (idcc.isBlank()) "Non renseignée" else "IDCC $idcc" }}"
        }
        themedDialog("Informations entreprise", text)
    }

    private fun showContract() {
        val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val rate = prefs.getString("hourly_rate", "").orEmpty()
        val coefficient = prefs.getString("convention_coefficient", "").orEmpty()
        val type = prefs.getString("contract_type", "").orEmpty()
        val weekly = prefs.getString("contract_weekly_hours", "").orEmpty()
        val text = TextView(context).apply {
            textSize = 15f
            setPadding(dp(18), dp(12), dp(18), dp(18))
            text = "TAUX HORAIRE BRUT : ${rate.ifBlank { "Non renseigné" }}\n\nCOEFFICIENT CONVENTIONNEL : ${coefficient.ifBlank { "Non renseigné" }}\n\nTYPE DE CONTRAT : ${type.ifBlank { "Non renseigné" }}\n\nDURÉE HEBDOMADAIRE : ${weekly.ifBlank { "Non renseignée" }}"
        }
        themedDialog("Contrat", text)
    }

    private fun showPayslipWorkspace() {
        // Réutilise la branche d'import/contrôle existante : elle n'est plus visible sur la page principale.
        themedDialog("Fiche de salaire", ScrollView(context).apply {
            addView(V2PayslipControlView(context), ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
    }

    private fun showRights() {
        themedDialog("Droits, congés & repos", ScrollView(context).apply {
            addView(V2RightsRestView(context), ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setOnClickListener { click() }
        applyAccessTheme(this)
    }

    private fun buttonLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
        topMargin = dp(8)
    }

    private fun refreshTheme(root: LinearLayout) {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val textColor = if (dark) theme.darkText else theme.lightText
        root.findViewWithTag<TextView>("salary_companies_title")?.setTextColor(textColor)
        walkButtons(root) { applyAccessTheme(it) }
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
    }

    private fun themedDialog(title: String, view: View) {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val panelColor = if (dark) theme.darkPanel else theme.lightPanel
        val accentColor = if (dark) theme.accentLight else theme.accent
        view.setBackgroundColor(panelColor)
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("FERMER", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(panelColor))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentColor)
        }
        dialog.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
