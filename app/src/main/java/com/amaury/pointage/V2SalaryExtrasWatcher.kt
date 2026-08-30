package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/** Observe uniquement les changements de layout pour équiper le panneau Salaire dès sa création. */
class V2SalaryExtrasWatcher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), ViewTreeObserver.OnGlobalLayoutListener {
    companion object {
        const val TAG = "v2_salary_extras_watcher"
        private const val INFO_ACCESS_TAG = "salary_information_access"
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

    override fun onGlobalLayout() { installIfPresent() }

    private fun installIfPresent() {
        val content = rootView.findViewById<LinearLayout>(R.id.contentPanel) ?: return
        val salary = content.findViewWithTag<SalaryPanelView>("integrated_salary_panel") ?: return

        content.findViewWithTag<SalaryInformationSheetView>(SalaryInformationSheetView.TAG)?.visibility = GONE

        val access = salary.findViewWithTag<Button>(INFO_ACCESS_TAG)
        if (access == null) {
            salary.addView(
                Button(context).apply {
                    tag = INFO_ACCESS_TAG
                    text = "FICHE DE RENSEIGNEMENTS"
                    isAllCaps = false
                    textSize = 14f
                    applyAccessTheme(this)
                    setOnClickListener { showInformationSheet() }
                },
                0,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                    bottomMargin = dp(8)
                }
            )
        } else {
            applyAccessTheme(access)
        }

        val payslip = salary.findViewWithTag<V2PayslipControlView>(V2PayslipControlView.TAG)
        if (payslip == null) {
            salary.addView(
                V2PayslipControlView(context),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        } else payslip.refresh()

        val rights = salary.findViewWithTag<V2RightsRestView>(V2RightsRestView.TAG)
        if (rights == null) {
            salary.addView(
                V2RightsRestView(context),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        } else rights.refresh()
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

    private fun showInformationSheet() {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val panelColor = if (dark) theme.darkPanel else theme.lightPanel
        val accentColor = if (dark) theme.accentLight else theme.accent

        val sheet = SalaryInformationSheetView(context)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(panelColor)
            addView(
                sheet,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Fiche de renseignements")
            .setView(scroll)
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
