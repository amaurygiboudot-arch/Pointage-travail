package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout

/** Observe uniquement les changements de layout pour équiper le panneau Salaire dès sa création. */
class V2SalaryExtrasWatcher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), ViewTreeObserver.OnGlobalLayoutListener {
    companion object { const val TAG = "v2_salary_extras_watcher" }

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
}
