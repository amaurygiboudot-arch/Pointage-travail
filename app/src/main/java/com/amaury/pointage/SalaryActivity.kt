package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
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

class SalaryActivity : Activity() {

    private lateinit var hourlyRateInput: EditText
    private lateinit var salaryMonthText: TextView
    private lateinit var salaryResultContainer: LinearLayout
    private lateinit var selectedConventionText: TextView
    private lateinit var conventionRuleStatusText: TextView

    private var selectedConvention = ConventionCatalog.conventions.first { it.idcc == "0292" }

    private val selectedMonth = Calendar.getInstance(Locale.FRANCE).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
    private val prefs by lazy { getSharedPreferences("salary_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_salary)

        hourlyRateInput = findViewById(R.id.hourlyRateInput)
        salaryMonthText = findViewById(R.id.salaryMonthText)
        salaryResultContainer = findViewById(R.id.salaryResultContainer)
        selectedConventionText = findViewById(R.id.selectedConventionText)
        conventionRuleStatusText = findViewById(R.id.conventionRuleStatusText)

        val backButton = findViewById<Button>(R.id.salaryBackButton)
        val chooseMonthButton = findViewById<Button>(R.id.chooseSalaryMonthButton)
        val calculateButton = findViewById<Button>(R.id.calculateSalaryButton)
        val searchConventionButton = findViewById<Button>(R.id.searchConventionButton)

        hourlyRateInput.setText(prefs.getString("hourly_rate", "") ?: "")
        selectedConvention = ConventionCatalog.findByIdcc(prefs.getString("convention_idcc", "0292"))
            ?: ConventionCatalog.conventions.first { it.idcc == "0292" }

        updateConventionDisplay()
        updateMonthLabel()
        showInitialState()

        hourlyRateInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty().trim().replace(',', '.')
                prefs.edit().putString("hourly_rate", value).apply()
                calculateSalary(showError = false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        backButton.setOnClickListener { finish() }
        chooseMonthButton.setOnClickListener { showMonthDialog() }
        searchConventionButton.setOnClickListener { showConventionSearchDialog() }
        selectedConventionText.setOnClickListener { showConventionSearchDialog() }
        calculateButton.setOnClickListener { calculateSalary() }

        calculateSalary(showError = false)
    }

    private fun updateConventionDisplay() {
        selectedConventionText.text = selectedConvention.displayName
        conventionRuleStatusText.text = if (selectedConvention.rulesIntegrated) {
            "✓ Règles intégrées dans le calcul"
        } else {
            "⚠ Règles détaillées non intégrées : calcul légal provisoire"
        }
    }

    private fun showConventionSearchDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 12)
        }
        val search = EditText(this).apply {
            hint = "🔎 Nom ou IDCC (ex. plasturgie, 292…)"
            isSingleLine = true
        }
        val list = ListView(this)
        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 720))

        var filtered = ConventionCatalog.conventions.toMutableList()
        var adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1,
            filtered.map { "${it.displayName}\n${it.fullName}" })
        list.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choisir la convention collective")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .create()

        fun refresh(query: String) {
            filtered = ConventionCatalog.conventions.filter { it.matches(query) }.toMutableList()
            adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1,
                filtered.map { "${it.displayName}\n${it.fullName}" })
            list.adapter = adapter
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refresh(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val convention = filtered.getOrNull(position) ?: return@setOnItemClickListener
            selectedConvention = convention
            prefs.edit().putString("convention_idcc", convention.idcc).apply()
            updateConventionDisplay()
            calculateSalary(showError = false)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateMonthLabel() {
        val label = monthFormat.format(selectedMonth.time).replaceFirstChar { it.uppercase() }
        salaryMonthText.text = "Mois : $label"
    }

    private fun showMonthDialog() {
        val labels = ArrayList<String>()
        val months = ArrayList<Calendar>()
        val cursor = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(36) {
            months.add(cursor.clone() as Calendar)
            labels.add(monthFormat.format(cursor.time).replaceFirstChar { it.uppercase() })
            cursor.add(Calendar.MONTH, -1)
        }
        val selectedIndex = months.indexOfFirst {
            it.get(Calendar.YEAR) == selectedMonth.get(Calendar.YEAR) &&
                it.get(Calendar.MONTH) == selectedMonth.get(Calendar.MONTH)
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Choisir le mois")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                selectedMonth.timeInMillis = months[which].timeInMillis
                updateMonthLabel()
                calculateSalary(showError = false)
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun calculateSalary(showError: Boolean = true) {
        val rateText = hourlyRateInput.text.toString().trim().replace(',', '.')
        val hourlyRate = rateText.toDoubleOrNull()
        if (hourlyRate == null || hourlyRate <= 0.0) {
            showInitialState()
            if (showError) Toast.makeText(this, "Entre un taux horaire brut valide", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putString("hourly_rate", rateText)
            .putString("convention_idcc", selectedConvention.idcc)
            .apply()

        val result = SalaryCalculator.calculate(
            data = PointageStore.load(this),
            year = selectedMonth.get(Calendar.YEAR),
            month = selectedMonth.get(Calendar.MONTH),
            hourlyRate = hourlyRate,
            convention = selectedConvention
        )

        val euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
        salaryResultContainer.removeAllViews()

        addSection("CONVENTION")
        addCard("Convention collective", selectedConvention.displayName)
        addCard("Intitulé", selectedConvention.fullName)
        addCard("Statut des règles", if (selectedConvention.rulesIntegrated) "Règles intégrées" else "Calcul légal provisoire")

        addSection("HEURES DU MOIS")
        addCard("Heures normales", formatDuration(result.regularMs))
        result.overtimeTiers.forEach { addCard(it.label, formatDuration(it.durationMs)) }
        addCard("Total pointé", formatDuration(result.totalWorkedMs))
        addCard("Sessions terminées", result.completedSessions.toString())

        addSection("ESTIMATION BRUTE")
        addCard("Taux horaire brut", euro.format(hourlyRate))
        addCard("Valeur des heures pointées", euro.format(result.workedGross))
        addCard("Base mensualisée 151,67 h", euro.format(result.monthlyBaseGross))
        addCard("Heures supplémentaires payées", euro.format(result.overtimeGross))
        addCard("Salaire mensualisé estimé", euro.format(result.monthlyEstimatedGross), highlight = true)

        addSection("AVANTAGES / GARANTIES")
        if (selectedConvention.advantages.isEmpty()) {
            addCard("Informations intégrées", "Aucun avantage spécifique intégré pour le moment.")
        } else {
            selectedConvention.advantages.forEachIndexed { index, value ->
                addCard("Avantage ${index + 1}", value)
            }
        }

        addSection("POINTS DE VIGILANCE")
        if (selectedConvention.cautions.isEmpty()) {
            addCard("Informations intégrées", "Aucun point particulier enregistré.")
        } else {
            selectedConvention.cautions.forEachIndexed { index, value ->
                addCard("Point ${index + 1}", value)
            }
        }
    }

    private fun showInitialState() {
        if (!::salaryResultContainer.isInitialized) return
        salaryResultContainer.removeAllViews()
        addCard("Calcul automatique", "Entre ou modifie ton taux horaire : les résultats se mettront à jour immédiatement.")
    }

    private fun addSection(title: String) {
        val view = TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.hp_gold))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(2, dp(16), 2, dp(5))
        }
        salaryResultContainer.addView(view)
    }

    private fun addCard(label: String, value: String, highlight: Boolean = false) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.hp_panel)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.hp_grey))
            textSize = 12f
        }
        val valueView = TextView(this).apply {
            text = value
            setTextColor(getColor(if (highlight) R.color.hp_gold_light else R.color.hp_white))
            textSize = if (highlight) 21f else 17f
            if (highlight) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(labelView)
        card.addView(valueView)
        salaryResultContainer.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }
}
