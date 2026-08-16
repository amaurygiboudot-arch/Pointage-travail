package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var salaryResultText: TextView
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
        salaryResultText = findViewById(R.id.salaryResultText)
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

        backButton.setOnClickListener { finish() }
        chooseMonthButton.setOnClickListener { showMonthDialog() }
        searchConventionButton.setOnClickListener { showConventionSearchDialog() }
        selectedConventionText.setOnClickListener { showConventionSearchDialog() }
        calculateButton.setOnClickListener { calculateSalary() }
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
        container.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            720
        ))

        var filtered = ConventionCatalog.conventions.toMutableList()
        var adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            filtered.map { "${it.displayName}\n${it.fullName}" }
        )
        list.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choisir la convention collective")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .create()

        fun refresh(query: String) {
            filtered = ConventionCatalog.conventions.filter { it.matches(query) }.toMutableList()
            adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                filtered.map { "${it.displayName}\n${it.fullName}" }
            )
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
            calculateSalaryIfRateAvailable()
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
                calculateSalaryIfRateAvailable()
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun calculateSalaryIfRateAvailable() {
        if (hourlyRateInput.text.toString().trim().replace(',', '.').toDoubleOrNull() != null) {
            calculateSalary(showError = false)
        }
    }

    private fun calculateSalary(showError: Boolean = true) {
        val rateText = hourlyRateInput.text.toString().trim().replace(',', '.')
        val hourlyRate = rateText.toDoubleOrNull()
        if (hourlyRate == null || hourlyRate <= 0.0) {
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
        salaryResultText.text = buildString {
            append("CONVENTION\n${selectedConvention.displayName}\n${selectedConvention.fullName}\n\n")
            if (!selectedConvention.rulesIntegrated) {
                append("⚠ CALCUL PROVISOIRE : les règles propres à cette convention ne sont pas encore toutes intégrées. Le barème légal est utilisé.\n\n")
            }

            append("HEURES DU MOIS\n")
            append("Heures normales : ${formatDuration(result.regularMs)}\n")
            result.overtimeTiers.forEach { append("${it.label} : ${formatDuration(it.durationMs)}\n") }
            append("Total pointé : ${formatDuration(result.totalWorkedMs)}\n")
            append("Sessions terminées : ${result.completedSessions}\n\n")

            append("ESTIMATION BRUTE\n")
            append("Valeur des heures pointées : ${euro.format(result.workedGross)}\n")
            append("Base mensualisée 151,67 h : ${euro.format(result.monthlyBaseGross)}\n")
            append("Heures supplémentaires payées : ${euro.format(result.overtimeGross)}\n")
            append("Salaire mensualisé estimé : ${euro.format(result.monthlyEstimatedGross)}\n\n")

            append("AVANTAGES / GARANTIES IDENTIFIÉS\n")
            if (selectedConvention.advantages.isEmpty()) append("• Aucun avantage spécifique intégré pour le moment.\n")
            selectedConvention.advantages.forEach { append("• $it\n") }

            append("\nPOINTS DE VIGILANCE\n")
            if (selectedConvention.cautions.isEmpty()) append("• Aucun point particulier enregistré.\n")
            selectedConvention.cautions.forEach { append("• $it\n") }

            append("\nCette estimation ne remplace pas une fiche de paie : accords d'entreprise, primes, ancienneté, absences et dispositifs d'aménagement du temps de travail peuvent modifier le résultat.")
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }
}
