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
import com.amaury.pointage.v2.V2PayslipStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.PlasturgieProtectionCategoryV2
import com.amaury.pointage.v2.engine.RestEngineV2
import com.amaury.pointage.v2.engine.SicknessPaymentFlowV2
import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSubrogationV2
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
            text = "Absence non rémunérée, arrêt maladie, congé payé ou autre : HoraTrack enregistre le cas réel. Pour un arrêt maladie, les IJSS, le maintien employeur, la subrogation et une éventuelle prévoyance qui chevauche le maintien restent séparés pour éviter tout double compte."
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
            val sickness = if (absence.type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
                V2PayslipStore.sicknessAllowanceForAbsence(context, companyId, absence)
            } else null
            val sicknessFlow = if (absence.type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
                SicknessPaymentFlowV2.resolve(absence, sickness)
            } else null
            val sicknessAmount = if (absence.type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
                V2PayslipStore.sicknessTheoreticalNetForAbsence(context, companyId, absence)
            } else null
            val relay = if (absence.type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
                V2PayslipStore.sicknessProvidentRelayForAbsence(context, companyId, absence)
            } else null
            val relayControl = if (absence.type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
                V2PayslipStore.sicknessProvidentRelayControlForAbsence(context, companyId, absence)
            } else null
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
                    if (absence.type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
                        append('\n').append("  Subrogation : ").append(subrogationLabel(absence.subrogation))
                        append('\n').append("  Prévoyance pendant maintien : ").append(providentLabel(absence))
                    }
                    if (sickness != null) {
                        append('\n').append("  IJSS maladie : ")
                        if (sickness.complete && sickness.dailyGross != null && sickness.payableDays != null && sickness.estimatedGrossTotal != null) {
                            append(String.format(Locale.FRANCE, "%.2f € brut/jour • %d jour(s) indemnisable(s) • %.2f € brut estimés", sickness.dailyGross, sickness.payableDays, sickness.estimatedGrossTotal))
                            append(" (si droits ouverts)")
                        } else {
                            append("à confirmer")
                            sickness.warnings.firstOrNull()?.let { append(" — ").append(it) }
                        }
                    }
                    if (sicknessFlow != null) {
                        append('\n').append("  Circuit IJSS : ")
                        when (sicknessFlow.ijssRecipient) {
                            SicknessPaymentFlowV2.IjssRecipient.EMPLOYER -> {
                                append("versées à l'employeur")
                                sicknessFlow.employerIjssReimbursementGross?.let {
                                    append(String.format(Locale.FRANCE, " • %.2f € brut estimés", it))
                                }
                                append(" • ne pas les ajouter une 2e fois au salarié")
                            }
                            SicknessPaymentFlowV2.IjssRecipient.EMPLOYEE -> {
                                append("versées directement au salarié")
                                sicknessFlow.directEmployeeIjssGross?.let {
                                    append(String.format(Locale.FRANCE, " • %.2f € brut estimés", it))
                                }
                            }
                            SicknessPaymentFlowV2.IjssRecipient.TO_CONFIRM -> append("destination à confirmer")
                        }
                        sicknessFlow.warnings.firstOrNull()?.let { append('\n').append("  ⚠ ").append(it) }
                    }
                    sicknessAmount?.employerComplementBeforeProvidentNet?.let {
                        append('\n').append("  Complément avant prévoyance : ")
                            .append(String.format(Locale.FRANCE, "%.2f € net avant PAS", it))
                    }
                    if (sicknessAmount?.finalComplementReliable == true) {
                        sicknessAmount.employerProvidentNetDeducted?.let {
                            append('\n').append("  Prévoyance chevauchante déduite : ")
                                .append(String.format(Locale.FRANCE, "%.2f € net", it))
                        }
                        sicknessAmount.employerComplementFinalNet?.let {
                            append('\n').append("  Complément employeur final estimé : ")
                                .append(String.format(Locale.FRANCE, "%.2f € net avant PAS", it))
                        }
                    } else if (sicknessAmount?.employerComplementBeforeProvidentNet != null) {
                        append('\n').append("  Complément final : à confirmer tant que la prévoyance chevauchante n'est pas renseignée")
                    }
                    if (relay?.applicableConvention == true) {
                        append('\n').append("  Catégorie prévoyance : ")
                            .append(PlasturgieProtectionCategoryV2.label(relay.protectionCategory))
                    }
                    if (relay?.potentiallyCovered == true) {
                        append('\n').append("  Relais prévoyance Plasturgie : ≥ 60 % du brut après maintien employeur")
                        relay.earliestContinuousStopDay?.let { append(" • dès le ").append(it).append("e jour continu") }
                        relay.relayReached?.let { append(if (it) " • relais atteint" else " • relais non encore atteint") }
                    }
                    if (relayControl?.complete == true) {
                        relayControl.expectedMinimumProvidentGross?.let {
                            append('\n').append("  Minimum prévoyance contrôlé : ")
                                .append(String.format(Locale.FRANCE, "%.2f € brut", it))
                        }
                        relayControl.observedProvidentGross?.let {
                            append(" • observé ").append(String.format(Locale.FRANCE, "%.2f € brut", it))
                        }
                        relayControl.differenceGross?.let {
                            append(" • écart ").append(String.format(Locale.FRANCE, "%+.2f €", it))
                        }
                        append(if (relayControl.meetsBranchMinimum == true) " • minimum respecté" else " • écart à vérifier")
                    }
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

            if (relay?.relayReached == true && relay.potentiallyCovered && relay.eligibilityConfirmed) {
                val controlAlreadyEntered = absence.providentRelayTargetGross60Amount != null &&
                    absence.providentRelaySocialSecurityGrossAmount != null &&
                    absence.providentRelayObservedGrossAmount != null
                absenceBox.addView(
                    Button(context).apply {
                        text = if (controlAlreadyEntered) "MODIFIER LE CONTRÔLE PRÉVOYANCE" else "CONTRÔLER LE RELAIS PRÉVOYANCE"
                        isAllCaps = false
                        textSize = 13f
                        setBackgroundResource(R.drawable.hp_panel)
                        setOnClickListener { showProvidentRelayControlDialog(absence) }
                    },
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
                )
            }
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
        var subrogation = AbsenceSubrogationV2.TO_CONFIRM
        var providentTreatment = AbsenceProvidentTreatmentV2.TO_CONFIRM
        var providentAmount: Double? = null
        val startButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        val endButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        val treatmentButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        val subrogationButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        val providentButton = Button(context).apply { isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
        fun updateLabels() {
            startButton.text = "Début : ${start.format(dateFormat)}"
            endButton.text = "Fin : ${end.format(dateFormat)}"
            treatmentButton.text = "Rémunération : ${treatmentLabel(treatment)}"
            subrogationButton.text = "Subrogation : ${subrogationLabel(subrogation)}"
            providentButton.text = when (providentTreatment) {
                AbsenceProvidentTreatmentV2.TO_CONFIRM -> "Prévoyance pendant maintien : à confirmer"
                AbsenceProvidentTreatmentV2.NONE_CONFIRMED -> "Prévoyance pendant maintien : aucune confirmée"
                AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED -> "Prévoyance pendant maintien : ${String.format(Locale.FRANCE, "%.2f € net", providentAmount ?: 0.0)}"
            }
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
                val labels = arrayOf("Maintien complet confirmé", "Maintien partiel", "Sans maintien employeur", "À confirmer")
                val values = arrayOf(
                    AbsenceSalaryTreatmentV2.FULLY_MAINTAINED,
                    AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED,
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
        if (type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
            subrogationButton.setOnClickListener {
                val labels = arrayOf("Oui — IJSS versées à l'employeur", "Non — IJSS versées au salarié", "À confirmer")
                val values = arrayOf(AbsenceSubrogationV2.YES, AbsenceSubrogationV2.NO, AbsenceSubrogationV2.TO_CONFIRM)
                AlertDialog.Builder(context)
                    .setTitle("Subrogation")
                    .setItems(labels) { _, which -> subrogation = values[which]; updateLabels() }
                    .setNegativeButton("ANNULER", null)
                    .show()
            }
            providentButton.setOnClickListener {
                val labels = arrayOf("À confirmer", "Aucune prestation pendant le maintien", "Montant net confirmé pendant le maintien")
                AlertDialog.Builder(context)
                    .setTitle("Prévoyance employeur pendant le maintien")
                    .setItems(labels) { _, which ->
                        when (which) {
                            0 -> {
                                providentTreatment = AbsenceProvidentTreatmentV2.TO_CONFIRM
                                providentAmount = null
                                updateLabels()
                            }
                            1 -> {
                                providentTreatment = AbsenceProvidentTreatmentV2.NONE_CONFIRMED
                                providentAmount = null
                                updateLabels()
                            }
                            2 -> showProvidentAmountDialog { amount ->
                                providentTreatment = AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED
                                providentAmount = amount
                                updateLabels()
                            }
                        }
                    }
                    .setNegativeButton("ANNULER", null)
                    .show()
            }
        }
        updateLabels()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(TextView(context).apply {
                text = "${AbsencePayrollImpactV2.label(type)} — journée(s) entière(s). La date de fin est incluse. Une prévoyance de branche en relais après maintien n'est jamais confondue avec une prestation qui chevauche le maintien."
                textSize = 12f
                setPadding(0, 0, 0, dp(8))
            })
            addView(startButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(endButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
            addView(treatmentButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
            if (type == AbsencePayrollImpactV2.TYPE_SICKNESS) {
                addView(subrogationButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
                addView(providentButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
            }
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
                if (providentTreatment == AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED && providentAmount == null) {
                    Toast.makeText(context, "Renseigne le montant net de prévoyance", Toast.LENGTH_LONG).show()
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
                        status = DecisionStatusV2.CONFIRMED,
                        subrogation = if (type == AbsencePayrollImpactV2.TYPE_SICKNESS) subrogation else AbsenceSubrogationV2.TO_CONFIRM,
                        providentTreatment = if (type == AbsencePayrollImpactV2.TYPE_SICKNESS) providentTreatment else AbsenceProvidentTreatmentV2.TO_CONFIRM,
                        employerProvidentOverlapNetAmount = if (type == AbsencePayrollImpactV2.TYPE_SICKNESS && providentTreatment == AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED) providentAmount else null
                    )
                )
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showProvidentAmountDialog(onConfirmed: (Double) -> Unit) {
        val input = EditText(context).apply {
            hint = "Montant net avant PAS — ex. 125,40"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Prestation prévoyance qui chevauche le maintien")
            .setMessage("Renseigne uniquement le montant NET avant PAS financé par l'employeur et couvrant la même période de maintien. Ne renseigne pas ici le relais de branche après maintien.")
            .setView(input)
            .setPositiveButton("VALIDER", null)
            .setNegativeButton("ANNULER", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = parse(input.text.toString())
                if (value == null || value < 0.0) {
                    input.error = "Montant net valide requis"
                    return@setOnClickListener
                }
                onConfirmed(value)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showProvidentRelayControlDialog(absence: AbsenceV2) {
        fun amountField(hintText: String, initial: Double?) = EditText(context).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            initial?.let { setText(String.format(Locale.FRANCE, "%.2f", it)) }
        }
        val target60 = amountField("60 % du salaire brut de référence — même période", absence.providentRelayTargetGross60Amount)
        val socialSecurity = amountField("Prestations SS brutes déduites — même période", absence.providentRelaySocialSecurityGrossAmount)
        val observed = amountField("Prévoyance brute réellement versée — même période", absence.providentRelayObservedGrossAmount)
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(TextView(context).apply {
                text = "Recopie les 3 montants du même décompte et de la même période. HoraTrack contrôle 60 % brut − prestations SS brutes sans inventer de conversion annuelle ou journalière."
                textSize = 12f
                setPadding(0, 0, 0, dp(8))
            })
            addView(target60, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(socialSecurity, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
            addView(observed, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Contrôle relais prévoyance")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNeutralButton("EFFACER", null)
            .setNegativeButton("ANNULER", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = listOf(target60, socialSecurity, observed).map { parse(it.text.toString()) }
                if (values.any { it == null || it < 0.0 }) {
                    Toast.makeText(context, "Renseigne les trois montants bruts du même décompte", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                V2RightsStore.upsertAbsence(
                    context,
                    absence.copy(
                        providentRelayTargetGross60Amount = values[0],
                        providentRelaySocialSecurityGrossAmount = values[1],
                        providentRelayObservedGrossAmount = values[2]
                    )
                )
                refresh()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                V2RightsStore.upsertAbsence(
                    context,
                    absence.copy(
                        providentRelayTargetGross60Amount = null,
                        providentRelaySocialSecurityGrossAmount = null,
                        providentRelayObservedGrossAmount = null
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
        AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED -> "maintien partiel — montant à confirmer"
        AbsenceSalaryTreatmentV2.UNPAID -> "sans maintien employeur"
        AbsenceSalaryTreatmentV2.TO_CONFIRM -> "rémunération à confirmer"
    }

    private fun subrogationLabel(value: AbsenceSubrogationV2): String = when (value) {
        AbsenceSubrogationV2.YES -> "oui — IJSS à l'employeur"
        AbsenceSubrogationV2.NO -> "non — IJSS au salarié"
        AbsenceSubrogationV2.TO_CONFIRM -> "à confirmer"
    }

    private fun providentLabel(absence: AbsenceV2): String = when (absence.providentTreatment) {
        AbsenceProvidentTreatmentV2.TO_CONFIRM -> "à confirmer"
        AbsenceProvidentTreatmentV2.NONE_CONFIRMED -> "aucune confirmée"
        AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED -> absence.employerProvidentOverlapNetAmount?.let {
            String.format(Locale.FRANCE, "%.2f € net confirmé", it)
        } ?: "montant à confirmer"
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
