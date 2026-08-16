package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SalaryActivity : Activity() {

    private lateinit var hourlyRateInput: EditText
    private lateinit var conventionSpinner: Spinner
    private lateinit var salaryMonthText: TextView
    private lateinit var salaryResultText: TextView

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
        conventionSpinner = findViewById(R.id.conventionSpinner)
        salaryMonthText = findViewById(R.id.salaryMonthText)
        salaryResultText = findViewById(R.id.salaryResultText)

        val backButton = findViewById<Button>(R.id.salaryBackButton)
        val chooseMonthButton = findViewById<Button>(R.id.chooseSalaryMonthButton)
        val calculateButton = findViewById<Button>(R.id.calculateSalaryButton)

        val savedRate = prefs.getString("hourly_rate", "") ?: ""
        hourlyRateInput.setText(savedRate)
        conventionSpinner.setSelection(prefs.getInt("convention_index", 0).coerceIn(0, 1))
        updateMonthLabel()

        backButton.setOnClickListener { finish() }
        chooseMonthButton.setOnClickListener { showMonthDialog() }
        calculateButton.setOnClickListener { calculateSalary() }
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
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun calculateSalary() {
        val rateText = hourlyRateInput.text.toString().trim().replace(',', '.')
        val hourlyRate = rateText.toDoubleOrNull()

        if (hourlyRate == null || hourlyRate <= 0.0) {
            Toast.makeText(this, "Entre un taux horaire brut valide", Toast.LENGTH_LONG).show()
            return
        }

        val conventionIndex = conventionSpinner.selectedItemPosition.coerceIn(0, 1)
        prefs.edit()
            .putString("hourly_rate", rateText)
            .putInt("convention_index", conventionIndex)
            .apply()

        val result = SalaryCalculator.calculate(
            data = PointageStore.load(this),
            year = selectedMonth.get(Calendar.YEAR),
            month = selectedMonth.get(Calendar.MONTH),
            hourlyRate = hourlyRate
        )

        val euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
        val regime = if (conventionIndex == 0) {
            "Plasturgie — régime hebdomadaire 35 h"
        } else {
            "Régime légal — sans accord spécifique"
        }

        salaryResultText.text = buildString {
            append("RÉGIME\n$regime\n\n")
            append("HEURES DU MOIS\n")
            append("Heures normales : ${formatDuration(result.regularMs)}\n")
            append("Heures sup. +25 % : ${formatDuration(result.overtime25Ms)}\n")
            append("Heures sup. +50 % : ${formatDuration(result.overtime50Ms)}\n")
            append("Total pointé : ${formatDuration(result.totalWorkedMs)}\n")
            append("Sessions terminées : ${result.completedSessions}\n\n")
            append("ESTIMATION BRUTE\n")
            append("Valeur des heures pointées : ${euro.format(result.workedGross)}\n")
            append("Base mensualisée 151,67 h : ${euro.format(result.monthlyBaseGross)}\n")
            append("Heures supplémentaires payées : ${euro.format(result.overtimeGross)}\n")
            append("Salaire mensualisé estimé : ${euro.format(result.monthlyEstimatedGross)}\n\n")
            append("Calcul hebdomadaire : 35 h normales, de la 36e à la 43e heure +25 %, à partir de la 44e +50 %.\n\n")
            append("Estimation indicative : un accord d'entreprise, une modulation/annualisation, des primes, absences, congés, travail de nuit, dimanche ou jours fériés peuvent modifier la paie réelle.")
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return String.format(Locale.FRANCE, "%02dh %02dm", hours, minutes)
    }
}
