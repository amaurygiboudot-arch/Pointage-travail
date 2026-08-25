package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Pause contractuelle/de base liée à chaque entreprise.
 * Elle est déduite automatiquement du temps travaillé. Les pauses saisies avec
 * le bouton Pause ou la saisie manuelle restent des pauses supplémentaires.
 */
object CompanyBasePauseSettings {
    private const val PREFS = "salary_settings"

    private fun prefix(slot: Int) = if (slot == 2) "company2_" else "company_"

    fun startMinute(context: Context, slot: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(prefix(slot) + "base_pause_start", -1)

    fun endMinute(context: Context, slot: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(prefix(slot) + "base_pause_end", -1)

    fun baseMinutes(context: Context, slot: Int): Int {
        val start = startMinute(context, slot)
        val end = endMinute(context, slot)
        if (start !in 0..1439 || end !in 0..1439 || start == end) return 0
        val duration = if (end > start) end - start else (24 * 60 - start) + end
        return duration.coerceIn(0, 240)
    }

    fun save(context: Context, slot: Int, startMinute: Int, endMinute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(prefix(slot) + "base_pause_start", startMinute.coerceIn(0, 1439))
            .putInt(prefix(slot) + "base_pause_end", endMinute.coerceIn(0, 1439))
            .apply()
    }

    fun clear(context: Context, slot: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(prefix(slot) + "base_pause_start")
            .remove(prefix(slot) + "base_pause_end")
            .apply()
    }

    fun label(context: Context, slot: Int): String {
        val start = startMinute(context, slot)
        val end = endMinute(context, slot)
        val duration = baseMinutes(context, slot)
        return if (duration <= 0) "Aucune pause de base" else
            "${format(start)} – ${format(end)}  •  ${duration} min automatiquement déduites"
    }

    private fun format(minutes: Int): String =
        String.format(Locale.FRANCE, "%02d:%02d", minutes / 60, minutes % 60)
}

class CompanyBasePauseView(context: Context) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)

    init {
        orientation = VERTICAL
        setPadding(0, dp(10), 0, dp(6))
        tag = "company_base_pause_view"
        refresh()
    }

    fun refresh() {
        removeAllViews()
        addView(TextView(context).apply {
            text = "PAUSE DE BASE ENTREPRISE"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "Cette pause est automatiquement retirée des heures travaillées. Le bouton Pause et les pauses manuelles sont comptés en supplément."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })

        addCompany(1)
        if (companyExists(2)) addCompany(2)
    }

    private fun companyExists(slot: Int): Boolean {
        val prefix = if (slot == 2) "company2_" else "company_"
        return prefs.getString(prefix + "name", "").orEmpty().isNotBlank() ||
            prefs.getString(prefix + "siret", "").orEmpty().isNotBlank()
    }

    private fun addCompany(slot: Int) {
        val prefix = if (slot == 2) "company2_" else "company_"
        val name = prefs.getString(prefix + "name", "").orEmpty()
            .ifBlank { if (slot == 1) "Entreprise principale" else "Entreprise 2" }

        val label = TextView(context).apply {
            text = "$name\n${CompanyBasePauseSettings.label(context, slot)}"
            textSize = 13f
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        val button = Button(context).apply {
            text = "RÉGLER LA PAUSE DE BASE"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { showDialog(slot, name) }
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        addView(button, LayoutParams(LayoutParams.MATCH_PARENT, dp(54)))
    }

    private fun showDialog(slot: Int, name: String) {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val start = EditText(context).apply {
            hint = "Début de pause — ex. 10:00"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
            val saved = CompanyBasePauseSettings.startMinute(context, slot)
            if (saved >= 0) setText(String.format(Locale.FRANCE, "%02d:%02d", saved / 60, saved % 60))
        }
        val end = EditText(context).apply {
            hint = "Fin de pause — ex. 10:15"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
            val saved = CompanyBasePauseSettings.endMinute(context, slot)
            if (saved >= 0) setText(String.format(Locale.FRANCE, "%02d:%02d", saved / 60, saved % 60))
        }
        box.addView(TextView(context).apply { text = "Pause de base • $name"; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        box.addView(start)
        box.addView(end)

        AlertDialog.Builder(context)
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val s = parse(start.text.toString())
                val e = parse(end.text.toString())
                if (s == null || e == null || s == e) {
                    Toast.makeText(context, "Entre deux heures valides, par exemple 10:00 et 10:15", Toast.LENGTH_LONG).show()
                } else {
                    CompanyBasePauseSettings.save(context, slot, s, e)
                    refresh()
                    Toast.makeText(context, "Pause de base enregistrée", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Supprimer") { _, _ ->
                CompanyBasePauseSettings.clear(context, slot)
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun parse(value: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

object CompanyBasePauseInstaller {
    fun install(panel: SalaryPanelView) {
        if (panel.findViewWithTag<View>("company_base_pause_view") != null) {
            panel.findViewWithTag<CompanyBasePauseView>("company_base_pause_view")?.refresh()
            return
        }
        var enterpriseIndex = -1
        for (i in 0 until panel.childCount) {
            if (panel.getChildAt(i) is EnterpriseLookupView) {
                enterpriseIndex = i
                break
            }
        }
        val view = CompanyBasePauseView(panel.context)
        val index = if (enterpriseIndex >= 0) enterpriseIndex + 1 else minOf(4, panel.childCount)
        panel.addView(view, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
