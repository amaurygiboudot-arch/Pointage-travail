package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ManualSessionWriter
import com.amaury.pointage.v2.V2ProfileStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/** Remplace le vieux flux de saisie manuelle par une écriture directe V2. */
object V2ManualEntryInstaller {
    private val wired = Collections.newSetFromMap(WeakHashMap<ManualHoursButton, Boolean>())

    fun install(activity: Activity) {
        if (!HoraTrackV2.ENABLED) return
        wireRecursive(activity.window.decorView, activity)
    }

    private fun wireRecursive(view: View, activity: Activity) {
        if (view is ManualHoursButton && wired.add(view)) {
            view.setOnClickListener { showDialog(activity) }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) wireRecursive(view.getChildAt(i), activity)
    }

    private fun showDialog(activity: Activity) {
        val selectedDate = Calendar.getInstance(Locale.FRANCE)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val companyList = SalaryCompanyStore.list(activity)
        val activeCompanyId = V2ProfileStore.activeCompanyId(activity)

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 8))
        }
        body.addView(TextView(activity).apply {
            text = "SAISIE MANUELLE V2"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F3A64A"))
        })
        val dateButton = Button(activity).apply {
            text = "Date : ${dateFormat.format(selectedDate.time)}"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
        }
        val start = timeInput(activity, "Début — ex. 05:00")
        val end = timeInput(activity, "Fin — ex. 13:00")
        val place = EditText(activity).apply {
            hint = "Lieu / client (facultatif)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        val companies = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val companyByButtonId = linkedMapOf<Int, String>()
        companyList.forEachIndexed { index, company ->
            val button = RadioButton(activity).apply {
                id = View.generateViewId()
                text = buildString {
                    append(company.name.ifBlank { "Entreprise ${index + 1}" })
                    if (company.siret.isNotBlank()) append(" — SIRET ${company.siret}")
                }
                isChecked = company.id == activeCompanyId || (activeCompanyId == null && index == 0)
            }
            companyByButtonId[button.id] = company.id
            companies.addView(button)
        }

        body.addView(dateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52)))
        body.addView(start)
        body.addView(end)
        body.addView(TextView(activity).apply { text = "Entreprise"; textSize = 14f; setPadding(0, dp(activity, 8), 0, 0) })
        if (companyList.isEmpty()) {
            body.addView(TextView(activity).apply {
                text = "Aucune entreprise configurée — la plage restera sans employeur associé."
                textSize = 13f
            })
        } else {
            body.addView(companies)
        }
        body.addView(place)

        dateButton.setOnClickListener {
            DatePickerDialog(activity, { _, y, m, d ->
                selectedDate.set(y, m, d)
                dateButton.text = "Date : ${dateFormat.format(selectedDate.time)}"
            }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(body)
            .setPositiveButton("Ajouter", null)
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val startMs = parseTime(selectedDate, start.text.toString())
                val endMs0 = parseTime(selectedDate, end.text.toString())
                if (startMs == null) { start.error = "Format HH:mm"; return@setOnClickListener }
                if (endMs0 == null) { end.error = "Format HH:mm"; return@setOnClickListener }
                val endMs = if (endMs0 <= startMs) endMs0 + 24L * 60L * 60L * 1000L else endMs0
                val selectedCompanyId = companyByButtonId[companies.checkedRadioButtonId]
                val ok = if (selectedCompanyId != null) {
                    V2ProfileStore.setActiveCompanyId(activity, selectedCompanyId)
                    V2ManualSessionWriter.addForCompany(
                        activity,
                        startMs,
                        endMs,
                        selectedCompanyId,
                        place.text.toString().trim()
                    )
                } else {
                    // Compatibilité : sans entreprise configurée, le comportement historique reste possible.
                    V2ManualSessionWriter.add(activity, startMs, endMs, 1, place.text.toString().trim())
                }
                Toast.makeText(activity, if (ok) "Heures ajoutées dans HoraTrack V2" else "Cette plage existe déjà ou est invalide", Toast.LENGTH_LONG).show()
                if (ok) {
                    PointageWidgetProvider.updateAll(activity)
                    QuickActionsWidgetProvider.updateAll(activity)
                    dialog.dismiss()
                    activity.recreate()
                }
            }
        }
        dialog.show()
    }

    private fun timeInput(context: Context, hintText: String) = EditText(context).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        isSingleLine = true
    }

    private fun parseTime(day: Calendar, raw: String): Long? {
        val m = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
