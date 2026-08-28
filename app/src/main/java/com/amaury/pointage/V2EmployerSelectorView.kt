package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.amaury.pointage.v2.V2ProfileStore

/** Sélecteur affiché seulement lorsqu'une seconde entreprise existe. */
class V2EmployerSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object { const val TAG = "v2_employer_selector" }
    private val salary = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
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
            setOnClickListener { switchCompany() }
        }
        addView(label)
        addView(button, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(3) })
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        salary.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    override fun onDetachedFromWindow() {
        salary.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key?.startsWith("company") == true) post { refresh() }
    }

    fun refresh() {
        val company2Exists = salary.getString("company2_name", "").orEmpty().isNotBlank() ||
            salary.getString("company2_siret", "").orEmpty().isNotBlank()
        visibility = if (company2Exists) View.VISIBLE else View.GONE
        if (!company2Exists) {
            V2ProfileStore.setActiveCompanySlot(context, 1)
            return
        }
        val slot = V2ProfileStore.activeCompanySlot(context)
        button.text = if (slot == 2) companyName(2) else companyName(1)
    }

    private fun switchCompany() {
        val next = if (V2ProfileStore.activeCompanySlot(context) == 1) 2 else 1
        V2ProfileStore.setActiveCompanySlot(context, next)
        refresh()
    }

    private fun companyName(slot: Int): String {
        val prefix = if (slot == 2) "company2_" else "company_"
        return salary.getString(prefix + "name", "").orEmpty().ifBlank { "Entreprise $slot" }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
