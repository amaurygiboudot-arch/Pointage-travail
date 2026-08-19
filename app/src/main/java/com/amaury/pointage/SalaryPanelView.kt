package com.amaury.pointage

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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

    private val legalConvention: ConventionCatalog.Convention
        get() = ConventionCatalog.conventions.firstOrNull { it.idcc.isBlank() }
            ?: ConventionCatalog.conventions.first()

    private var selectedConvention: ConventionCatalog.Convention = legalConvention

    private val hourlyRateInput = EditText(context)
    private val employmentStartDateText = TextView(context)
    private val selectedConventionText = TextView(context)
    private val conventionRuleStatusText = TextView(context)
    private val salaryMonthText = TextView(context)
    private val mealAmountInput = EditText(context)
    private val salaryResultContainer = LinearLayout(context)
    private lateinit var enterpriseLookup: EnterpriseLookupView

    private val companyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("company") == true || key == "convention_idcc") {
            post {
                updateCompany2Visibility()
                refresh()
            }
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        buildUi()
        prefs.registerOnSharedPreferenceChangeListener(companyListener)
        refresh()
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(companyListener)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        val companyIdcc = prefs.getString("company_idcc", "").orEmpty()
        selectedConvention = if (companyIsIdentified() && companyIdcc.isNotBlank()) {
            ConventionCatalog.findByIdcc(companyIdcc) ?: legalConvention
        } else legalConvention
        val savedRate = prefs.getString("hourly_rate", "").orEmpty()
        if (hourlyRateInput.text.toString() != savedRate) hourlyRateInput.setText(savedRate)
        val mealAmount = prefs.getString("meal_amount", "").orEmpty()
        if (mealAmountInput.text.toString() != mealAmount) mealAmountInput.setText(mealAmount)
        updateCompany2Visibility()
        updateStartDateLabel()
        updateConventionDisplay()
        updateMonthLabel()
        calculateSalary(false)
        (context as? android.app.Activity)?.let { AppearanceManager.apply(it) }
    }

    private fun companyIsIdentified(): Boolean {
        val name = prefs.getString("company_name", "").orEmpty().trim()
        val siret = prefs.getString("company_siret", "").orEmpty().trim()
        return name.isNotBlank() || siret.isNotBlank()
    }

    private fun updateCompany2Visibility() {
        if (!::enterpriseLookup.isInitialized || enterpriseLookup.childCount < 4) return
        enterpriseLookup.getChildAt(3).visibility = if (companyIsIdentified()) View.VISIBLE else View.GONE
    }

    private fun buildUi() {
        addView(sectionTitle("ENTREPRISE PRINCIPALE & SALAIRE"))
        addView(label("TAUX HORAIRE BRUT"))
        configureInput(hourlyRateInput, "Ex. 13,70", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        addView(hourlyRateInput, LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(6) })

        addView(label("DATE D'ENTRÉE DANS L'ENTREPRISE").apply { setPadding(0, dp(14), 0, 0) })
        employmentStartDateText.textSize = 14f
        addView(employmentStartDateText)
        val startDateButton = hpButton("CHOISIR LA DATE D'ENTRÉE")
        addView(startDateButton)

        enterpriseLookup = EnterpriseLookupView(context)
        addView(enterpriseLookup, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        addView(CompanyControlsView(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(label("CONVENTION COLLECTIVE").apply { setPadding(0, dp(14), 0, 0) })
        val conventionRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        selectedConventionText.apply {
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            background = outlinedFieldBackground()
            isClickable = true
        }
        val searchButton = hpButton("🔎").apply { textSize = 18f }
        conventionRow.addView(selectedConventionText, LayoutParams(0, dp(54), 1f))
        conventionRow.addView(searchButton, LayoutParams(dp(60), dp(54)).apply { marginStart = dp(8) })
        addView(conventionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        conventionRuleStatusText.textSize = 14f
        addView(conventionRuleStatusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        addView(sectionTitle("PANIER").apply { setPadding(0, dp(18), 0, dp(5)) })
        addView(TextView(context).apply {
            text = "Le montant est défini ici. Les postes donnant droit au panier (matin, journée, après-midi, nuit) se choisissent dans Aujourd'hui > Pauses et paniers par poste."
            textSize = 14f
        })
        addView(label("MONTANT D'UN PANIER (€)").apply { setPadding(0, dp(12), 0, dp(4)) })
        configureInput(mealAmountInput, "Ex. 5,38", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        addView(mealAmountInput, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))

        salaryMonthText.textSize = 16f
        addView(salaryMonthText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val monthButton = hpButton("CHOISIR LE MOIS")
        val calculateButton = hpButton("RECALCULER")
        addView(monthButton)
        addView(calculateButton)

        addView(sectionTitle("RÉSULTATS DU MOIS").apply { setPadding(0, dp(18), 0, dp(4)) })
        salaryResultContainer.orientation = VERTICAL
        addView(salaryResultContainer)

        hourlyRateInput.addTextChangedListener(watcher("hourly_rate"))
        mealAmountInput.addTextChangedListener(watcher("meal_amount"))

        startDateButton.setOnClickListener { showStartDatePicker() }
        searchButton.setOnClickListener {
            if (!companyIsIdentified()) Toast.makeText(context, "Renseigne d'abord le nom ou le SIRET de l'entreprise", Toast.LENGTH_LONG).show()
            else showConventionSearchDialog()
        }
        selectedConventionText.setOnClickListener { if (companyIsIdentified()) showConventionSearchDialog() }
        monthButton.setOnClickListener { showMonthDialog() }
        calculateButton.setOnClickListener { calculateSalary(true) }
    }

    private fun configureInput(input: EditText, hintText: String, type: Int) {
        input.apply {
            hint = hintText
            inputType = type
            isSingleLine = true
            textSize = 14f
            background = outlinedFieldBackground()
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
    }

    private fun outlinedFieldBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), Color.parseColor("#D6A84B"))
        cornerRadius = dp(18).toFloat()
    }

    private fun watcher(key: String) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val value = s?.toString().orEmpty().trim().replace(',', '.')
            prefs.edit().putString(key, value).apply()
            calculateSalary(false)
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun updateConventionDisplay() {
        if (!companyIsIdentified()) {
            selectedConventionText.text = "Convention non renseignée"
            conventionRuleStatusText.text = "Régime légal provisoire tant que l'entreprise n'est pas renseignée."
        } else {
            selectedConventionText.text = selectedConvention.displayName
            val night = ConventionNightRules.forIdcc(selectedConvention.idcc)
            conventionRuleStatusText.text = if (selectedConvention.idcc.isBlank()) {
                "Régime légal / convention à choisir"
            } else if (night != null) {
                "Convention utilisée pour les heures supplémentaires et la plage de nuit connue."
            } else {
                "Convention utilisée pour les règles intégrées disponibles."
            }
        }
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
            updateStartDateLabel(); calculateSalary(false)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showMonthDialog() {
        val labels = ArrayList<String>()
        val months = ArrayList<Calendar>()
        val cursor = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(36) {
            months += cursor.clone() as Calendar
            labels += monthFormat.format(cursor.time).replaceFirstChar { it.uppercase() }
            cursor.add(Calendar.MONTH, -1)
        }
        val selectedIndex = months.indexOfFirst {
            it.get(Calendar.YEAR) == selectedMonth.get(Calendar.YEAR) && it.get(Calendar.MONTH) == selectedMonth.get(Calendar.MONTH)
        }.coerceAtLeast(0)
        AlertDialog.Builder(context).setTitle("Choisir le mois")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                selectedMonth.timeInMillis = months[which].timeInMillis
                updateMonthLabel(); calculateSalary(false); dialog.dismiss()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun showConventionSearchDialog() {
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val search = EditText(context).apply { hint = "Nom ou IDCC"; isSingleLine = true }
        val list = ListView(context)
        box.addView(search)
        box.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, dp(420)))
        var filtered = ConventionCatalog.conventions.toMutableList()
        fun bind() { list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, filtered.map { it.displayName }) }
        bind()
        val dialog = AlertDialog.Builder(context).setTitle("Convention collective").setView(box).setNegativeButton("Annuler", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filtered = ConventionCatalog.conventions.filter { it.matches(s?.toString().orEmpty()) }.toMutableList(); bind() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            selectedConvention = filtered.getOrNull(position) ?: return@setOnItemClickListener
            prefs.edit().putString("company_idcc", selectedConvention.idcc).putString("convention_idcc", selectedConvention.idcc).apply()
            updateConventionDisplay(); calculateSalary(false); dialog.dismiss()
        }
        dialog.show()
    }

    private fun calculateSalary(showError: Boolean) {
        val hourlyRate = hourlyRateInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (hourlyRate == null || hourlyRate <= 0.0) {
            salaryResultContainer.removeAllViews()
            addCard("Calcul automatique", "Entre ton taux horaire brut pour afficher l'estimation.")
            if (showError) Toast.makeText(context, "Entre un taux horaire brut valide", Toast.LENGTH_LONG).show()
            return
        }

        val effectiveConvention = if (companyIsIdentified()) selectedConvention else legalConvention
        val result = SalaryCalculator.calculate(PointageStore.load(context), selectedMonth.get(Calendar.YEAR), selectedMonth.get(Calendar.MONTH), hourlyRate, effectiveConvention)
        val euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
        val companyName = prefs.getString("company_name", "").orEmpty().trim()
        val companySiret = prefs.getString("company_siret", "").orEmpty().trim()
        val (mealCount, mealTotal) = calculateMeals()

        salaryResultContainer.removeAllViews()
        addResultSection("ENTREPRISE PRINCIPALE")
        addCard("Employeur", companyName.ifBlank { if (companySiret.isNotBlank()) "SIRET $companySiret" else "Non renseigné" })
        addCard("Convention", if (companyIsIdentified() && selectedConvention.idcc.isNotBlank()) selectedConvention.displayName else "Non renseignée — régime légal provisoire")
        val start = prefs.getLong("employment_start_date", 0L)
        addCard("Ancienneté", if (start > 0L) formatSeniority(start) else "Non renseignée")

        addResultSection("HEURES DU MOIS")
        addCard("Heures normales", formatDuration(result.regularMs))
        result.overtimeTiers.forEach { addCard(it.label, formatDuration(it.durationMs)) }
        if (result.nightMs > 0L) addCard("Heures de nuit conventionnelles", formatDuration(result.nightMs))
        addCard("Total travaillé", formatDuration(result.totalWorkedMs))

        addResultSection("PANIER")
        val amount = mealAmountInput.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        addCard("Paniers comptés", "$mealCount × ${euro.format(amount)}")
        addCard("Total paniers", euro.format(mealTotal), mealCount > 0)

        addResultSection("ESTIMATION BRUTE")
        addCard("Taux horaire", euro.format(hourlyRate))
        addCard("Heures supplémentaires", euro.format(result.overtimeGross))
        if (result.nightPremiumGross > 0.0) addCard("Majoration nuit", euro.format(result.nightPremiumGross))
        addCard("Salaire estimé hors paniers", euro.format(result.monthlyEstimatedGross), true)
        ConventionNightRules.forIdcc(effectiveConvention.idcc)?.let { addCard("Règle nuit", it.note) }
    }

    private fun calculateMeals(): Pair<Int, Double> {
        val amount = mealAmountInput.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val data = PointageStore.load(context)
        val firstItemByDay = linkedMapOf<String, org.json.JSONObject>()
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
        val cal = Calendar.getInstance(Locale.FRANCE)
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || item.isNull("exit")) continue
            cal.timeInMillis = entry
            if (cal.get(Calendar.YEAR) != selectedMonth.get(Calendar.YEAR) || cal.get(Calendar.MONTH) != selectedMonth.get(Calendar.MONTH)) continue
            val key = dayFormat.format(cal.time)
            val current = firstItemByDay[key]
            if (current == null || entry < current.optLong("entry", Long.MAX_VALUE)) firstItemByDay[key] = item
        }
        val count = firstItemByDay.values.count { item ->
            val entry = item.optLong("entry", -1L)
            val stored = item.optString("shiftType").trim()
            val shift = ShiftType.values().firstOrNull { it.id == stored } ?: ShiftProfileManager.detect(entry)
            ShiftProfileManager.mealEnabled(context, shift)
        }
        return count to count * amount
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

    private fun addResultSection(title: String) { salaryResultContainer.addView(sectionTitle(title).apply { setPadding(0, dp(14), 0, dp(4)) }) }

    private fun addCard(label: String, value: String, highlight: Boolean = false) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
        }
        row.addView(TextView(context).apply { text = label; textSize = 14f }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply { text = value; textSize = if (highlight) 17f else 14f; setTypeface(typeface, if (highlight) Typeface.BOLD else Typeface.NORMAL) })
        salaryResultContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
    }

    private fun sectionTitle(value: String) = TextView(context).apply { text = value; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#D6A84B")) }
    private fun label(value: String) = TextView(context).apply { text = value; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#D6A84B")) }
    private fun hpButton(value: String) = Button(context).apply { text = value; isAllCaps = false; textSize = 14f; setBackgroundResource(R.drawable.hp_panel); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(7) } }
    private fun formatDuration(ms: Long): String { val totalMinutes = ms.coerceAtLeast(0L) / 60000L; return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
