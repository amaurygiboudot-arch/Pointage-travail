package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ManualHoursButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Button(context, attrs) {

    init {
        setOnClickListener { showManualDialog() }
    }

    private fun showManualDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val selectedDate = Calendar.getInstance(Locale.FRANCE)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)

        val company1Name = salaryPrefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise 1" }
        val company2Name = salaryPrefs.getString("company2_name", "").orEmpty().ifBlank { "Entreprise 2" }
        val company1Exists = salaryPrefs.getString("company_siret", "").orEmpty().isNotBlank() ||
            salaryPrefs.getString("company_name", "").orEmpty().isNotBlank()
        val company2Exists = salaryPrefs.getString("company2_siret", "").orEmpty().isNotBlank() ||
            salaryPrefs.getString("company2_name", "").orEmpty().isNotBlank()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dateButton = Button(context).apply {
            text = "Date : ${dateFormat.format(selectedDate.time)}"
            setBackgroundResource(R.drawable.hp_panel)
        }
        dateButton.setOnClickListener {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDate.set(Calendar.YEAR, year)
                    selectedDate.set(Calendar.MONTH, month)
                    selectedDate.set(Calendar.DAY_OF_MONTH, day)
                    dateButton.text = "Date : ${dateFormat.format(selectedDate.time)}"
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val startInput = EditText(context).apply {
            hint = "Heure de début — ex. 08:00"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
        }
        val endInput = EditText(context).apply {
            hint = "Heure de fin — ex. 16:30"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
        }

        val companyLabel = TextView(context).apply {
            text = "Entreprise"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        val companyGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val company1 = RadioButton(context).apply {
            id = View.generateViewId()
            text = company1Name
            isEnabled = company1Exists
        }
        val company2 = RadioButton(context).apply {
            id = View.generateViewId()
            text = company2Name
            isEnabled = company2Exists
        }
        val noCompany = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Sans entreprise / autre"
        }
        companyGroup.addView(company1)
        companyGroup.addView(company2)
        companyGroup.addView(noCompany)
        if (company1Exists) company1.isChecked = true else if (company2Exists) company2.isChecked = true else noCompany.isChecked = true

        val savedAddresses = gpsPrefs.getString("address", "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        val placeInput = EditText(context).apply {
            hint = if (savedAddresses.isEmpty()) "Lieu / client (facultatif)" else "Lieu / client ou adresse (facultatif)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }

        listOf(dateButton, startInput, endInput, companyLabel, companyGroup, placeInput).forEach(container::addView)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Saisie manuelle des heures")
            .setMessage("Ajoute une journée ou une plage horaire oubliée. Elle sera comptée dans l'historique, les analyses et le salaire.")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Ajouter", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val start = parseTime(selectedDate, startInput.text.toString())
                val end = parseTime(selectedDate, endInput.text.toString())
                if (start == null) {
                    startInput.error = "Format attendu : HH:mm"
                    return@setOnClickListener
                }
                if (end == null) {
                    endInput.error = "Format attendu : HH:mm"
                    return@setOnClickListener
                }
                if (end <= start) {
                    endInput.error = "L'heure de fin doit être après le début"
                    return@setOnClickListener
                }

                val companySlot = when (companyGroup.checkedRadioButtonId) {
                    company1.id -> 1
                    company2.id -> 2
                    else -> 0
                }
                val companyName = when (companySlot) {
                    1 -> company1Name
                    2 -> company2Name
                    else -> ""
                }
                val place = placeInput.text.toString().trim()
                val label = when {
                    place.isNotBlank() && companyName.isNotBlank() -> "$place — $companyName"
                    place.isNotBlank() -> place
                    companyName.isNotBlank() -> companyName
                    else -> "Saisie manuelle"
                }

                val data = PointageStore.load(context)
                val item = JSONObject()
                    .put("entry", start)
                    .put("exit", end)
                    .put("zoneAddress", label)
                    .put("manual", true)
                if (companySlot > 0) item.put("companySlot", companySlot)
                data.put(item)
                PointageStore.save(context, data)
                PointageWidgetProvider.updateAll(context)
                DriveBackupManager.syncCurrentMonthAsync(context)

                Toast.makeText(context, "Heures ajoutées : ${formatDuration(end - start)}", Toast.LENGTH_LONG).show()
                dialog.dismiss()
                (context as? Activity)?.recreate()
            }
        }
        dialog.show()
    }

    private fun parseTime(day: Calendar, value: String): Long? {
        val match = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }
}

class CompanyControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)

    init {
        orientation = VERTICAL
        setPadding(0, dp(8), 0, 0)
        refresh()
    }

    fun refresh() {
        removeAllViews()
        addCompanyDeleteButton(1)
        addCompanyDeleteButton(2)
    }

    private fun addCompanyDeleteButton(slot: Int) {
        val prefix = if (slot == 1) "company_" else "company2_"
        val name = prefs.getString(prefix + "name", "").orEmpty()
        val siret = prefs.getString(prefix + "siret", "").orEmpty()
        val exists = name.isNotBlank() || siret.isNotBlank()

        val button = Button(context).apply {
            text = if (exists) "SUPPRIMER ${name.ifBlank { "ENTREPRISE $slot" }.uppercase(Locale.FRANCE)}" else "ENTREPRISE $slot — AUCUNE DONNÉE"
            isEnabled = exists
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { confirmDelete(slot, name.ifBlank { "Entreprise $slot" }) }
        }
        addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun confirmDelete(slot: Int, name: String) {
        AlertDialog.Builder(context)
            .setTitle("Supprimer $name ?")
            .setMessage("Les informations de cette entreprise seront supprimées. Les lieux et l'historique de pointage resteront conservés, mais les lieux ne seront plus associés à cette entreprise.")
            .setPositiveButton("Supprimer") { _, _ -> deleteCompany(slot) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteCompany(slot: Int) {
        val prefix = if (slot == 1) "company_" else "company2_"
        val editor = prefs.edit()
        listOf("siret", "siren", "name", "address", "ape", "idcc", "convention_name", "agreement_summary")
            .forEach { editor.remove(prefix + it) }
        if (slot == 1) editor.remove("convention_idcc")
        editor.apply()

        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val companyMap = runCatching {
            JSONObject(gpsPrefs.getString("address_company_slots", "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        val cleaned = JSONObject()
        val keys = companyMap.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (companyMap.optInt(key, 0) != slot) cleaned.put(key, companyMap.opt(key))
        }
        gpsPrefs.edit().putString("address_company_slots", cleaned.toString()).apply()

        Toast.makeText(context, "Entreprise $slot supprimée", Toast.LENGTH_LONG).show()
        (context as? Activity)?.recreate()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
