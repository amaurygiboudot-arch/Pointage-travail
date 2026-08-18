package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private data class HpDialogColors(
    val background: Int,
    val panel: Int,
    val text: Int,
    val secondary: Int,
    val gold: Int,
    val goldLight: Int
)

private fun hpDialogColors(context: Context): HpDialogColors {
    val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
    val mode = prefs.getString("mode", "auto") ?: "auto"
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val dark = mode == "dark" || (mode == "auto" && systemDark)
    return HpDialogColors(
        background = Color.parseColor(if (dark) "#0B0B0B" else "#F7F3EA"),
        panel = Color.parseColor(if (dark) "#181818" else "#FFFFFF"),
        text = Color.parseColor(if (dark) "#F4EFE3" else "#17130D"),
        secondary = Color.parseColor(if (dark) "#CFC7B8" else "#625B50"),
        gold = Color.parseColor("#D6A84B"),
        goldLight = Color.parseColor(if (dark) "#F3D58A" else "#795600")
    )
}

private fun rounded(context: Context, color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable {
    val d = context.resources.displayMetrics.density
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * d
        if (stroke != null) setStroke((1 * d).toInt(), stroke)
    }
}

class ManualHoursButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Button(context, attrs) {

    init { setOnClickListener { showManualDialog() } }

    private fun showManualDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val colors = hpDialogColors(context)

        val selectedDate = Calendar.getInstance(Locale.FRANCE)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)

        val company1Name = salaryPrefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise 1" }
        val company2Name = salaryPrefs.getString("company2_name", "").orEmpty().ifBlank { "Entreprise 2" }
        val company1Exists = salaryPrefs.getString("company_siret", "").orEmpty().isNotBlank() || salaryPrefs.getString("company_name", "").orEmpty().isNotBlank()
        val company2Exists = salaryPrefs.getString("company2_siret", "").orEmpty().isNotBlank() || salaryPrefs.getString("company2_name", "").orEmpty().isNotBlank()

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(context, colors.background, 24, colors.gold)
        }

        body.addView(TextView(context).apply {
            text = "♛  SAISIE MANUELLE"
            gravity = Gravity.CENTER
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.goldLight)
        })
        body.addView(TextView(context).apply {
            text = "Ajoute une journée ou une plage horaire oubliée. Elle sera comptée dans l'historique, les analyses et le salaire."
            textSize = 14f
            setTextColor(colors.secondary)
            setPadding(0, dp(8), 0, dp(14))
        })

        val dateButton = Button(context).apply {
            text = "Date : ${dateFormat.format(selectedDate.time)}"
            isAllCaps = false
            setTextColor(colors.goldLight)
            background = rounded(context, colors.panel, 16, colors.gold)
        }
        dateButton.setOnClickListener {
            val picker = DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    dateButton.text = "Date : ${dateFormat.format(selectedDate.time)}"
                },
                selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)
            )
            picker.setOnShowListener {
                picker.getButton(DatePickerDialog.BUTTON_POSITIVE)?.setTextColor(colors.goldLight)
                picker.getButton(DatePickerDialog.BUTTON_NEGATIVE)?.setTextColor(colors.goldLight)
            }
            picker.show()
        }

        fun themedInput(hintText: String) = EditText(context).apply {
            hint = hintText
            isSingleLine = true
            setTextColor(colors.text)
            setHintTextColor(colors.secondary)
            backgroundTintList = ColorStateList.valueOf(colors.gold)
            setPadding(dp(6), dp(12), dp(6), dp(8))
        }

        val startInput = themedInput("Heure de début — ex. 08:00").apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val endInput = themedInput("Heure de fin — ex. 16:30").apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val companyLabel = TextView(context).apply {
            text = "Entreprise"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.goldLight)
            setPadding(0, dp(12), 0, dp(4))
        }
        val companyGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        fun companyRadio(label: String, enabled: Boolean = true) = RadioButton(context).apply {
            id = View.generateViewId()
            text = label
            isEnabled = enabled
            setTextColor(if (enabled) colors.text else colors.secondary)
            buttonTintList = ColorStateList.valueOf(colors.gold)
        }
        val company1 = companyRadio(company1Name, company1Exists)
        val company2 = companyRadio(company2Name, company2Exists)
        val noCompany = companyRadio("Sans entreprise / autre")
        companyGroup.addView(company1); companyGroup.addView(company2); companyGroup.addView(noCompany)
        if (company1Exists) company1.isChecked = true else if (company2Exists) company2.isChecked = true else noCompany.isChecked = true

        val savedAddresses = gpsPrefs.getString("address", "").orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }
        val placeInput = themedInput(if (savedAddresses.isEmpty()) "Lieu / client (facultatif)" else "Lieu / client ou adresse (facultatif)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        body.addView(dateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        body.addView(startInput); body.addView(endInput); body.addView(companyLabel); body.addView(companyGroup); body.addView(placeInput)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        val cancel = Button(context).apply {
            text = "Annuler"; isAllCaps = false; setTextColor(colors.secondary); background = rounded(context, colors.panel, 14)
        }
        val add = Button(context).apply {
            text = "Ajouter"; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#15100A")); background = rounded(context, colors.goldLight, 14)
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        buttons.addView(add, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        body.addView(buttons)

        val scroll = ScrollView(context).apply { addView(body) }
        val dialog = AlertDialog.Builder(context).setView(scroll).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        cancel.setOnClickListener { dialog.dismiss() }
        add.setOnClickListener {
            val start = parseTime(selectedDate, startInput.text.toString())
            val end = parseTime(selectedDate, endInput.text.toString())
            if (start == null) { startInput.error = "Format attendu : HH:mm"; return@setOnClickListener }
            if (end == null) { endInput.error = "Format attendu : HH:mm"; return@setOnClickListener }
            if (end <= start) { endInput.error = "L'heure de fin doit être après le début"; return@setOnClickListener }

            val companySlot = when (companyGroup.checkedRadioButtonId) { company1.id -> 1; company2.id -> 2; else -> 0 }
            val companyName = when (companySlot) { 1 -> company1Name; 2 -> company2Name; else -> "" }
            val place = placeInput.text.toString().trim()
            val label = when {
                place.isNotBlank() && companyName.isNotBlank() -> "$place — $companyName"
                place.isNotBlank() -> place
                companyName.isNotBlank() -> companyName
                else -> "Saisie manuelle"
            }
            val data = PointageStore.load(context)
            val item = JSONObject().put("entry", start).put("exit", end).put("zoneAddress", label).put("manual", true)
            if (companySlot > 0) item.put("companySlot", companySlot)
            data.put(item)
            PointageStore.save(context, data)
            PointageWidgetProvider.updateAll(context)
            DriveBackupManager.syncCurrentMonthAsync(context)
            Toast.makeText(context, "Heures ajoutées : ${formatDuration(end - start)}", Toast.LENGTH_LONG).show()
            dialog.dismiss()
            (context as? Activity)?.recreate()
        }
        dialog.show()
    }

    private fun parseTime(day: Calendar, value: String): Long? {
        val match = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
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
    init { orientation = VERTICAL; setPadding(0, dp(8), 0, 0); refresh() }

    fun refresh() { removeAllViews(); addCompanyDeleteButton(1); addCompanyDeleteButton(2) }

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
        val colors = hpDialogColors(context)
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = rounded(context, colors.background, 22, colors.gold)
        }
        box.addView(TextView(context).apply { text = "Supprimer $name ?"; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(colors.goldLight) })
        box.addView(TextView(context).apply {
            text = "Les informations de cette entreprise seront supprimées. Les lieux et l'historique resteront conservés."
            textSize = 14f; setTextColor(colors.secondary); setPadding(0, dp(10), 0, dp(14))
        })
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        val cancel = Button(context).apply { text = "Annuler"; isAllCaps = false; setTextColor(colors.secondary); background = rounded(context, colors.panel, 14) }
        val delete = Button(context).apply { text = "Supprimer"; isAllCaps = false; setTextColor(Color.parseColor("#15100A")); background = rounded(context, colors.goldLight, 14) }
        buttons.addView(cancel, LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        buttons.addView(delete, LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        box.addView(buttons)
        val dialog = AlertDialog.Builder(context).setView(box).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        cancel.setOnClickListener { dialog.dismiss() }
        delete.setOnClickListener { dialog.dismiss(); deleteCompany(slot) }
        dialog.show()
    }

    private fun deleteCompany(slot: Int) {
        val prefix = if (slot == 1) "company_" else "company2_"
        val editor = prefs.edit()
        listOf("siret", "siren", "name", "address", "ape", "idcc", "convention_name", "agreement_summary").forEach { editor.remove(prefix + it) }
        if (slot == 1) editor.remove("convention_idcc")
        editor.apply()

        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val companyMap = runCatching { JSONObject(gpsPrefs.getString("address_company_slots", "{}") ?: "{}") }.getOrElse { JSONObject() }
        val cleaned = JSONObject()
        val keys = companyMap.keys()
        while (keys.hasNext()) { val key = keys.next(); if (companyMap.optInt(key, 0) != slot) cleaned.put(key, companyMap.opt(key)) }
        gpsPrefs.edit().putString("address_company_slots", cleaned.toString()).apply()
        Toast.makeText(context, "Entreprise $slot supprimée", Toast.LENGTH_LONG).show()
        (context as? Activity)?.recreate()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
