package com.amaury.pointage

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SalaryPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
    private val selectedMonth = Calendar.getInstance(Locale.FRANCE).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

    private var selectedConvention = ConventionCatalog.findByIdcc(
        prefs.getString("company_idcc", prefs.getString("convention_idcc", "0292"))
    ) ?: ConventionCatalog.conventions.first { it.idcc == "0292" }

    private val hourlyRateInput = EditText(context)
    private val employmentStartDateText = TextView(context)
    private val selectedConventionText = TextView(context)
    private val conventionRuleStatusText = TextView(context)
    private val salaryMonthText = TextView(context)
    private val salaryResultContainer = LinearLayout(context)

    init {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        buildUi()
        refresh()
    }

    fun refresh() {
        val principalIdcc = prefs.getString("company_idcc", "").orEmpty()
        if (principalIdcc.isNotBlank()) {
            ConventionCatalog.findByIdcc(principalIdcc)?.let { selectedConvention = it }
        }
        val savedRate = prefs.getString("hourly_rate", "").orEmpty()
        if (hourlyRateInput.text.toString() != savedRate) hourlyRateInput.setText(savedRate)
        updateStartDateLabel()
        updateConventionDisplay()
        updateMonthLabel()
        calculateSalary(false)
        AppearanceManager.apply(context as? android.app.Activity ?: return)
    }

    private fun buildUi() {
        addView(sectionTitle("ENTREPRISE PRINCIPALE & SALAIRE"))

        addView(label("TAUX HORAIRE BRUT"))
        hourlyRateInput.apply {
            hint = "Ex. 13,70"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            setBackgroundResource(R.drawable.hp_panel)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        addView(hourlyRateInput, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })

        addView(label("DATE D'ENTRÉE DANS L'ENTREPRISE").apply { setPadding(0, dp(14), 0, 0) })
        employmentStartDateText.textSize = 15f
        addView(employmentStartDateText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val startDateButton = hpButton("CHOISIR LA DATE D'ENTRÉE")
        addView(startDateButton)

        addView(EnterpriseLookupView(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        addView(CompanyControlsView(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(label("CONVENTION COLLECTIVE").apply { setPadding(0, dp(14), 0, 0) })
        val conventionRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        selectedConventionText.apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setBackgroundResource(R.drawable.hp_panel)
            isClickable = true
        }
        val searchButton = hpButton("🔎").apply { textSize = 20f }
        conventionRow.addView(selectedConventionText, LayoutParams(0, dp(56), 1f))
        conventionRow.addView(searchButton, LayoutParams(dp(62), dp(56)).apply { marginStart = dp(8) })
        addView(conventionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        conventionRuleStatusText.textSize = 12f
        addView(conventionRuleStatusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        salaryMonthText.textSize = 16f
        addView(salaryMonthText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val monthButton = hpButton("CHOISIR LE MOIS")
        val calculateButton = hpButton("RECALCULER")
        addView(monthButton)
        addView(calculateButton)

        addView(sectionTitle("RÉSULTATS DU MOIS").apply { setPadding(0, dp(18), 0, dp(4)) })
        salaryResultContainer.orientation = VERTICAL
        addView(salaryResultContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        hourlyRateInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.edit().putString("hourly_rate", s?.toString().orEmpty().trim().replace(',', '.')).apply()
                calculateSalary(false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        startDateButton.setOnClickListener { showStartDatePicker() }
        searchButton.setOnClickListener { showConventionSearchDialog() }
        selectedConventionText.setOnClickListener { showConventionDetailsDialog() }
        monthButton.setOnClickListener { showMonthDialog() }
        calculateButton.setOnClickListener { calculateSalary(true) }
    }

    private fun updateConventionDisplay() {
        selectedConventionText.text = selectedConvention.displayName
        conventionRuleStatusText.text = (if (selectedConvention.rulesIntegrated) "✓ Règles intégrées" else "⚠ Calcul légal provisoire") + "  •  Appuie pour les détails"
    }

    private fun updateStartDateLabel() {
        val ms = prefs.getLong("employment_start_date", 0L)
        employmentStartDateText.text = if (ms > 0L) "Date d'entrée : ${dateFormat.format(ms)}" else "Date d'entrée : non renseignée"
    }

    private fun updateMonthLabel() {
        salaryMonthText.text = "Mois : ${monthFormat.format(selectedMonth.time).replaceFirstChar { it.uppercase() }}"
    }

    private fun showStartDatePicker() {
        val saved = prefs.getLong("employment_start_date", 0L)
        val c = Calendar.getInstance(Locale.FRANCE)
        if (saved > 0L) c.timeInMillis = saved
        DatePickerDialog(context, { _, year, month, day ->
            c.set(year, month, day, 12, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            prefs.edit().putLong("employment_start_date", c.timeInMillis).apply()
            updateStartDateLabel()
            calculateSalary(false)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showMonthDialog() {
        val labels = ArrayList<String>()
        val months = ArrayList<Calendar>()
        val cursor = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(36) {
            months.add(cursor.clone() as Calendar)
            labels.add(monthFormat.format(cursor.time).replaceFirstChar { it.uppercase() })
            cursor.add(Calendar.MONTH, -1)
        }
        val selectedIndex = months.indexOfFirst {
            it.get(Calendar.YEAR) == selectedMonth.get(Calendar.YEAR) && it.get(Calendar.MONTH) == selectedMonth.get(Calendar.MONTH)
        }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Choisir le mois")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                selectedMonth.timeInMillis = months[which].timeInMillis
                updateMonthLabel()
                calculateSalary(false)
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showConventionSearchDialog() {
        val container = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val search = EditText(context).apply { hint = "🔎 Nom ou IDCC"; isSingleLine = true }
        val list = ListView(context)
        container.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        container.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, dp(420)))
        var filtered = ConventionCatalog.conventions.toMutableList()
        fun bind() {
            list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, filtered.map { "${it.displayName} — ${it.fullName}" })
        }
        bind()
        val dialog = AlertDialog.Builder(context).setTitle("Convention de l'entreprise principale").setView(container).setNegativeButton("Annuler", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filtered = ConventionCatalog.conventions.filter { it.matches(s?.toString().orEmpty()) }.toMutableList(); bind()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val convention = filtered.getOrNull(position) ?: return@setOnItemClickListener
            selectedConvention = convention
            prefs.edit().putString("convention_idcc", convention.idcc).putString("company_idcc", convention.idcc).apply()
            updateConventionDisplay(); calculateSalary(false); dialog.dismiss()
        }
        dialog.show()
    }

    private fun showConventionDetailsDialog() {
        val companyName = prefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise principale" }
        val companySiret = prefs.getString("company_siret", "").orEmpty()
        val agreements = prefs.getString("company_agreement_summary", "").orEmpty()
        val travel = TravelRightsCatalog.forIdcc(selectedConvention.idcc)
        val details = buildString {
            append("ENTREPRISE PRINCIPALE\n").append(companyName)
            if (companySiret.isNotBlank()) append("\nSIRET : ").append(companySiret)
            append("\n\nCONVENTION COLLECTIVE\n").append(selectedConvention.fullName)
            if (selectedConvention.idcc.isNotBlank()) append("\nIDCC : ").append(selectedConvention.idcc)
            append("\n\nHEURES SUPPLÉMENTAIRES\n")
            selectedConvention.overtimeTiers.forEach { tier ->
                val from = String.format(Locale.FRANCE, "%.0f", tier.fromHour)
                val to = tier.toHour?.let { String.format(Locale.FRANCE, "%.0f", it) }
                val rate = ((tier.multiplier - 1.0) * 100.0).toInt()
                append("• ").append(from).append(" h")
                if (to != null) append(" à ").append(to).append(" h")
                append(" : +").append(rate).append(" %\n")
            }
            if (selectedConvention.advantages.isNotEmpty()) {
                append("\nAVANTAGES / GARANTIES\n")
                selectedConvention.advantages.forEach { append("• ").append(it).append('\n') }
            }
            if (travel.isNotEmpty()) {
                append("\nDÉPLACEMENTS & FRAIS\n")
                travel.forEach { append("• ").append(it.title).append(" : ").append(it.detail).append('\n') }
            }
            if (selectedConvention.cautions.isNotEmpty()) {
                append("\nPOINTS DE VIGILANCE\n")
                selectedConvention.cautions.forEach { append("• ").append(it).append('\n') }
            }
            if (agreements.isNotBlank()) append("\nACCORDS D'ENTREPRISE\n").append(agreements)
        }
        val text = TextView(context).apply { this.text = details.trim(); textSize = 15f; setPadding(dp(18), dp(8), dp(18), dp(18)) }
        val scroll = ScrollView(context).apply { addView(text) }
        AlertDialog.Builder(context).setTitle(selectedConvention.displayName).setView(scroll).setPositiveButton("Fermer", null).setNeutralButton("Changer") { _, _ -> showConventionSearchDialog() }.show()
    }

    private fun calculateSalary(showError: Boolean) {
        val rateText = hourlyRateInput.text.toString().trim().replace(',', '.')
        val hourlyRate = rateText.toDoubleOrNull()
        if (hourlyRate == null || hourlyRate <= 0.0) {
            salaryResultContainer.removeAllViews()
            addCard("Calcul automatique", "Entre ton taux horaire brut pour afficher l'estimation.")
            if (showError) Toast.makeText(context, "Entre un taux horaire brut valide", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("hourly_rate", rateText).putString("convention_idcc", selectedConvention.idcc).putString("company_idcc", selectedConvention.idcc).apply()
        val result = SalaryCalculator.calculate(PointageStore.load(context), selectedMonth.get(Calendar.YEAR), selectedMonth.get(Calendar.MONTH), hourlyRate, selectedConvention)
        val euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
        salaryResultContainer.removeAllViews()
        val principalName = prefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise principale" }
        addResultSection("ENTREPRISE PRINCIPALE")
        addCard("Employeur", principalName)
        addCard("Convention", selectedConvention.displayName)
        val start = prefs.getLong("employment_start_date", 0L)
        addCard("Ancienneté", if (start > 0L) formatSeniority(start) else "Non renseignée")
        addResultSection("HEURES DU MOIS")
        addCard("Heures normales", formatDuration(result.regularMs))
        result.overtimeTiers.forEach { addCard(it.label, formatDuration(it.durationMs)) }
        addCard("Total pointé", formatDuration(result.totalWorkedMs))
        addResultSection("ESTIMATION BRUTE")
        addCard("Taux horaire", euro.format(hourlyRate))
        addCard("Heures supplémentaires", euro.format(result.overtimeGross))
        addCard("Salaire estimé", euro.format(result.monthlyEstimatedGross), true)
    }

    private fun formatSeniority(startMs: Long): String {
        val start = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = startMs }
        val end = (selectedMonth.clone() as Calendar).apply { add(Calendar.MONTH, 1); add(Calendar.MILLISECOND, -1) }
        if (start.after(end)) return "0 mois"
        var years = end.get(Calendar.YEAR) - start.get(Calendar.YEAR)
        var months = end.get(Calendar.MONTH) - start.get(Calendar.MONTH)
        if (end.get(Calendar.DAY_OF_MONTH) < start.get(Calendar.DAY_OF_MONTH)) months--
        if (months < 0) { years--; months += 12 }
        return when {
            years > 0 && months > 0 -> "$years an${if (years > 1) "s" else ""} et $months mois"
            years > 0 -> "$years an${if (years > 1) "s" else ""}"
            else -> "${months.coerceAtLeast(0)} mois"
        }
    }

    private fun addResultSection(title: String) {
        salaryResultContainer.addView(sectionTitle(title).apply { setPadding(0, dp(14), 0, dp(4)) })
    }

    private fun addCard(label: String, value: String, highlight: Boolean = false) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
        }
        row.addView(TextView(context).apply { text = label; textSize = if (highlight) 16f else 14f }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply { text = value; textSize = if (highlight) 18f else 15f; setTypeface(typeface, if (highlight) Typeface.BOLD else Typeface.NORMAL) })
        salaryResultContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
    }

    private fun sectionTitle(value: String) = TextView(context).apply {
        text = value
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#D6A84B"))
    }

    private fun label(value: String) = TextView(context).apply {
        text = value
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#D6A84B"))
    }

    private fun hpButton(value: String) = Button(context).apply {
        text = value
        isAllCaps = false
        textSize = 13f
        setBackgroundResource(R.drawable.hp_panel)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(7) }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
