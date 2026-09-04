package com.amaury.pointage

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.RestEngineV2
import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class V2RightsRestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val companyId: String = ""
) : LinearLayout(context, attrs) {

    companion object {
        const val TAG = "v2_rights_rest"
    }

    private val content = TextView(context)
    private val absenceBox = LinearLayout(context)
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(16), 0, dp(6))
        addView(TextView(context).apply {
            text = "DROITS, CONGÉS & REPOS"
            textSize = 15f
        })
        addView(TextView(context).apply {
            text = "Les compteurs sont propres à l’entreprise sélectionnée. Le repos est calculé automatiquement ; aucune règle légale ou conventionnelle non confirmée n’est inventée."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })
        addView(
            Button(context).apply {
                text = "➕ AJOUTER / METTRE À JOUR UN COMPTEUR"
                isAllCaps = false
                textSize = 14f
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { showCounterDialog() }
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )
        addView(content.apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })

        addView(TextView(context).apply {
            text = "ABSENCES"
            textSize = 15f
            setPadding(0, dp(18), 0, dp(4))
        })
        addView(TextView(context).apply {
            text = "Absence non rémunérée, arrêt maladie, congé payé ou autre : HoraTrack enregistre le cas réel mais n’invente ni IJSS ni maintien employeur."
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })
        addView(
            Button(context).apply {
                text = "➕ ABSENCE NON RÉMUNÉRÉE"
                isAllCaps = false
                textSize = 14f
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { showAbsencePeriodDialog(AbsencePayrollImpactV2.TYPE_UNPAID, AbsenceSalaryTreatmentV2.UNPAID, true) }
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )
        addView(
            Button(context).apply {
                text = "➕ AUTRE ABSENCE"
                isAllCaps = false
                textSize = 14f
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { chooseAbsenceType() }
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
        )
        absenceBox.orientation = VERTICAL
        addView(absenceBox)
        refresh()
    }

    fun refresh() {
        val balances = if (companyId.isBlank()) {
            V2RightsStore.all(context)
        } else {
            V2RightsStore.forCompany(context, companyId)
        }
        val rights = if (balances.isEmpty()) {
            "Compteurs : aucun renseigné pour cette entreprise."
        } else {
            balances.joinToString("\n") { b ->
                buildString {
                    append("• ").append(b.label)
                    b.acquired?.let { append(" • acquis ").append(number(it)) }
                    b.available?.let { append(" • disponible ").append(number(it)) }
                    b.taken?.let { append(" • pris ").append(number(it)) }
                    b.anticipated?.let { append(" • anticipé ").append(number(it)) }
                    b.remaining?.let { append(" • restant ").append(number(it)) }
                    append(' ').append(b.unit)
                }
            }
        }
        val sessions = if (companyId.isBlank()) {
            V2RuntimeStore.allSessions(context)
        } else {
            val accepted = SalaryCompanyStore.acceptedEmployerIds(context, companyId)
            V2RuntimeStore.allSessions(context).filter { it.employerId in accepted }
        }
        val latest = RestEngineV2.dailyRests(sessions).lastOrNull()
        val rest = latest?.let {
            "Dernier repos entre journées : ${duration(it.restMs)} • conformité légale : À confirmer selon la règle applicable"
        } ?: "Repos quotidien : pas encore assez de journées terminées pour calculer un intervalle."
        val warnings = if (companyId.isBlank()) {
            V2RightsStore.snapshot(context).warnings
        } else {
            V2RightsStore.snapshot(context, companyId = companyId).warnings
        }
        content.text = buildString {
            append(rights).append("\n\n").append(rest)
            if (warnings.isNotEmpty()) {
                append("\n\n⚠ ").append(warnings.joinToString("\n⚠ "))
            }
        }
        refreshAbsences()
    }

    private fun refreshAbsences() {
        absenceBox.removeAllViews()
        val absences = if (companyId.isBlank()) emptyList() else
            V2RightsStore.absencesForCompany(context, companyId).sortedByDescending { it.startMs }
        if (absences.isEmpty()) {
            absenceBox.addView(TextView(context).apply {
                text = "Aucune absence enregistrée pour cette entreprise."
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        absences.forEach { absence ->
            val start = localDate(absence.startMs)
            val end = localDate(absence.endMs - 1L)
            val days = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(TextView(context).apply {
                text = buildString {
                    append("• ").append(AbsencePayrollImpactV2.label(absence.type)).append('\n')
                    append("  ").append(start.format(dateFormat)).append(" → ").append(end.format(dateFormat))
                    append(" • ").append(days).append(" jour").append(if (days > 1) "s" else "")
                    append('\n').append("  ").append(treatmentLabel(absence.salaryTreatment))
                }
                textSize = 13f
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(context).apply {
                text = "SUPPRIMER"
                isAllCaps = false
                textSize = 11f
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { confirmDeleteAbsence(absence, start, end) }
            }, LayoutParams(dp(108), dp(48)))
            absenceBox.addView(row)
        }
    }

    private fun chooseAbsenceType() {
        if (companyId.isBlank()) {
            Toast.makeText(context, "Choisis d’abord une entreprise", Toast.LENGTH_LONG).show()
            return
        }
        val labels = arrayOf("Arrêt maladie", "Congé payé", "Accident du travail", "Maternité / paternité", "Autre absence")
        val types = arrayOf(
            AbsencePayrollImpactV2.TYPE_SICKNESS,
            AbsencePayrollImpactV2.TYPE_PAID_LEAVE,
            AbsencePayrollImpactV2.TYPE_WORK_ACCIDENT,
            AbsencePayrollImpactV2.TYPE_PARENTAL,
            AbsencePayrollImpactV2.TYPE_OTHER
        )
        AlertDialog.Builder(context)
            .setTitle("Type d’absence")
            .setItems(labels) { _, which ->
                showAbsencePeriodDialog(types[which], AbsenceSalaryTreatmentV2.TO_CONFIRM, false)
            }
            .setNegativeButton("ANNULER", null)
            .show()
    }

    private fun showAbsencePeriodDialog(
        type: String,
        initialTreatment: AbsenceSalaryTreatmentV2,
        treatmentLocked: Boolean
    ) {
        if (companyId.isBlank()) {
            Toast.makeText(context, "Choisis d’abord une entreprise", Toast.LENGTH_LONG).show()
            return
        }
        var start = LocalDate.now()
        var end = start
        var treatment = initialTreatment
        val startButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        val endButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        val treatmentButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        fun updateLabels() {
            startButton.text = "Début : ${start.format(dateFormat)}"
            endButton.text = "Fin : ${end.format(dateFormat)}"
            treatmentButton.text = "Rémunération : ${treatmentLabel(treatment)}"
        }
        fun pick(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
            DatePickerDialog(
                context,
                { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth
            ).show()
        }
        startButton.setOnClickListener {
            pick(start) { picked -> start = picked; if (end.isBefore(start)) end = start; updateLabels() }
        }
        endButton.setOnClickListener { pick(end) { picked -> end = picked; updateLabels() } }
        if (!treatmentLocked) {
            treatmentButton.setOnClickListener {
                val labels = arrayOf("Maintien complet confirmé", "Sans maintien employeur", "À confirmer")
                val values = arrayOf(
                    AbsenceSalaryTreatmentV2.FULLY_MAINTAINED,
                    AbsenceSalaryTreatmentV2.UNPAID,
                    AbsenceSalaryTreatmentV2.TO_CONFIRM
                )
                AlertDialog.Builder(context)
                    .setTitle("Traitement de l’absence")
                    .setItems(labels) { _, which -> treatment = values[which]; updateLabels() }
                    .setNegativeButton("ANNULER", null)
                    .show()
            }
        } else treatmentButton.isEnabled = false
        updateLabels()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(TextView(context).apply {
                text = "${AbsencePayrollImpactV2.label(type)} — journée(s) entière(s). La date de fin est incluse. Les IJSS et règles de maintien ne sont jamais déduites automatiquement sans données suffisantes."
                textSize = 12f
                setPadding(0, 0, 0, dp(8))
            })
            addView(startButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(endButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
            addView(treatmentButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(AbsencePayrollImpactV2.label(type))
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (end.isBefore(start)) {
                    Toast.makeText(context, "La date de fin doit être après le début", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val zone = ZoneId.systemDefault()
                V2RightsStore.upsertAbsence(
                    context,
                    AbsenceV2(
                        id = "absence-${UUID.randomUUID()}",
                        employerId = companyId,
                        type = type,
                        startMs = start.atStartOfDay(zone).toInstant().toEpochMilli(),
                        endMs = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        salaryTreatment = treatment,
                        fullDay = true,
                        status = DecisionStatusV2.CONFIRMED
                    )
                )
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun treatmentLabel(value: AbsenceSalaryTreatmentV2): String = when (value) {
        AbsenceSalaryTreatmentV2.FULLY_MAINTAINED -> "maintien complet confirmé"
        AbsenceSalaryTreatmentV2.UNPAID -> "sans maintien employeur"
        AbsenceSalaryTreatmentV2.TO_CONFIRM -> "rémunération à confirmer"
    }

    private fun confirmDeleteAbsence(absence: AbsenceV2, start: LocalDate, end: LocalDate) {
        AlertDialog.Builder(context)
            .setTitle("Supprimer cette absence ?")
            .setMessage("${AbsencePayrollImpactV2.label(absence.type)}\n${start.format(dateFormat)} → ${end.format(dateFormat)}")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("SUPPRIMER") { _, _ ->
                V2RightsStore.removeAbsence(context, absence.id)
                refresh()
            }
            .show()
    }

    private fun showCounterDialog() {
        fun amount(h: String) = EditText(context).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            isSingleLine = true
        }

        val label = EditText(context).apply {
            hint = "Nom du compteur — ex. Congés payés"
            isSingleLine = true
        }
        val unit = EditText(context).apply {
            hint = "Unité — ex. jours ou heures"
            isSingleLine = true
            setText("jours")
        }
        val acquired = amount("Acquis — facultatif")
        val available = amount("Disponible — facultatif")
        val taken = amount("Pris — facultatif")
        val anticipated = amount("Anticipé — facultatif")
        val remaining = amount("Restant — facultatif")
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            listOf(label, unit, acquired, available, taken, anticipated, remaining).forEach(::addView)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Compteur de droits")
            .setMessage("HoraTrack enregistre les valeurs pour cette entreprise. La période de référence reste non renseignée sans source fiable.")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = label.text.toString().trim()
                if (name.isBlank()) {
                    label.error = "Nom requis"
                    return@setOnClickListener
                }
                val values = listOf(acquired, available, taken, anticipated, remaining)
                    .map { parse(it.text.toString()) }
                if (values.all { it == null }) {
                    Toast.makeText(context, "Renseigne au moins une valeur", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                V2RightsStore.upsert(
                    context,
                    V2RightsStore.Balance(
                        "manual-${UUID.randomUUID()}",
                        name,
                        values[0],
                        values[1],
                        values[2],
                        values[3],
                        values[4],
                        unit.text.toString().trim().ifBlank { "jours" },
                        0L,
                        Long.MAX_VALUE,
                        "MANUAL",
                        companyId
                    )
                )
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun localDate(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun parse(raw: String) = raw.trim().replace(',', '.').toDoubleOrNull()

    private fun number(v: Double) = String.format(Locale.FRANCE, "%.2f", v)
        .trimEnd('0')
        .trimEnd(',')

    private fun duration(ms: Long): String {
        val m = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%dh%02d", m / 60L, m % 60L)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
