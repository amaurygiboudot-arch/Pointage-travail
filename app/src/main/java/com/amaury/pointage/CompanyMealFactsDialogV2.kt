package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.MealBasketFactJournalV2
import com.amaury.pointage.v2.V2MealBasketFactStore
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Éditeur local des faits repas durables propres au salarié dans une entreprise.
 *
 * Il ne demande jamais si un panier "est dû" et ne demande aucun montant : il enregistre seulement
 * des faits datés que le moteur juridique vérifié pourra ensuite utiliser ou laisser à confirmer.
 */
object CompanyMealFactsDialogV2 {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
    private val managedKeys = setOf(
        MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
        MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE,
        MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
        MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW
    )

    private data class PeriodKey(val fromEpochDay: Long, val toEpochDay: Long?)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val loaded = V2MealBasketFactStore.load(context)
        if (loaded.malformedCount > 0) {
            AlertDialog.Builder(context)
                .setTitle("Faits repas / organisation")
                .setMessage(
                    "Le journal local contient ${loaded.malformedCount} fait(s) illisible(s). " +
                        "HoraTrack refuse de le réécrire pour ne pas effacer une information potentiellement bloquante."
                )
                .setPositiveButton("FERMER", null)
                .show()
            return
        }

        val groups = loaded.entries
            .filter {
                it.companyId == companyId &&
                    it.scope == MealBasketFactJournalV2.Scope.COMPANY &&
                    it.key in managedKeys &&
                    it.effectiveFromEpochDay != null
            }
            .groupBy { PeriodKey(it.effectiveFromEpochDay!!, it.effectiveToEpochDay) }
            .toList()
            .sortedByDescending { it.first.fromEpochDay }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Ici, HoraTrack enregistre des faits réels et datés — jamais un droit au panier ni un montant. " +
                "Le moteur juridique décide ensuite si une règle officielle est applicable."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })
        box.addView(TextView(context).apply {
            text = "Les situations variables (chantier, retour possible au domicile, repas imposé sur place, etc.) seront gérées au niveau de la journée ou de la session, pas comme vérité permanente de l’entreprise."
            textSize = 12f
            setPadding(0, 0, 0, dp(context, 10))
        })

        var dialog: AlertDialog? = null
        if (groups.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucune période factuelle enregistrée."
                textSize = 13f
                setPadding(0, dp(context, 4), 0, dp(context, 8))
            })
        } else {
            groups.forEach { (_, entries) ->
                box.addView(Button(context).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = periodSummary(entries)
                    setOnClickListener {
                        dialog?.dismiss()
                        showEditor(context, companyId, entries)
                    }
                }, rowParams(context))
            }
        }

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "AJOUTER UNE PÉRIODE"
            setOnClickListener {
                dialog?.dismiss()
                showEditor(context, companyId, emptyList())
            }
        }, rowParams(context))

        dialog = AlertDialog.Builder(context)
            .setTitle("Faits repas / organisation")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        dialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: List<MealBasketFactJournalV2.Entry>
    ) {
        val period = existing.firstOrNull()?.let {
            PeriodKey(it.effectiveFromEpochDay!!, it.effectiveToEpochDay)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        box.addView(TextView(context).apply {
            text = "Ces réponses décrivent ta situation réelle dans cette entreprise pour la période indiquée. " +
                "« À confirmer » bloque le fait concerné au lieu d’inventer une valeur."
            textSize = 12f
            setPadding(0, 0, 0, dp(context, 8))
        })

        val from = field(context, "Début d’effet — JJ/MM/AAAA").apply {
            setText(LocalDate.ofEpochDay(period?.fromEpochDay ?: LocalDate.now().toEpochDay()).format(dateFormatter))
        }
        val to = field(context, "Fin d’effet — JJ/MM/AAAA (facultatif)").apply {
            setText(period?.toEpochDay?.let { LocalDate.ofEpochDay(it).format(dateFormatter) }.orEmpty())
        }
        box.addView(from, rowParams(context))
        box.addView(to, rowParams(context))

        val posted = triStateSpinner(context)
        val canteen = triStateSpinner(context)
        val vouchers = triStateSpinner(context)
        addQuestion(
            context,
            box,
            "Travail posté / en équipes",
            "Confirme uniquement si ton organisation habituelle dans cette entreprise te place réellement en travail posté ou en équipes sur cette période.",
            posted
        )
        addQuestion(
            context,
            box,
            "Cantine / restauration d’entreprise disponible",
            "Indique si une cantine ou restauration d’entreprise est réellement accessible pour les journées concernées sur cette période.",
            canteen
        )
        addQuestion(
            context,
            box,
            "Titres-restaurant fournis de manière récurrente",
            "Confirme seulement si l’employeur fournit habituellement des titres-restaurant pour les journées concernées sur cette période.",
            vouchers
        )

        val nightState = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("À confirmer", "Plage de nuit employeur confirmée")
            )
        }
        val nightStart = field(context, "Début de la plage de nuit — HH:mm", InputType.TYPE_CLASS_DATETIME)
        val nightEnd = field(context, "Fin de la plage de nuit — HH:mm", InputType.TYPE_CLASS_DATETIME)
        addQuestion(
            context,
            box,
            "Plage de nuit définie par l’employeur",
            "Saisis cette plage uniquement si elle est connue. Elle peut traverser minuit (ex. 21:00 → 06:00).",
            nightState
        )
        box.addView(nightStart, rowParams(context))
        box.addView(nightEnd, rowParams(context))

        posted.setSelection(flagSelection(existing, MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER))
        canteen.setSelection(flagSelection(existing, MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE))
        vouchers.setSelection(flagSelection(existing, MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED))
        val existingNight = existing.singleOrNull { it.key == MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW }
            ?.takeIf { it.status == DecisionStatusV2.CONFIRMED }
            ?.value as? MealBasketFactJournalV2.Value.NightWindow
        if (existingNight != null) {
            nightState.setSelection(1)
            nightStart.setText(formatMinute(existingNight.window.startMinute))
            nightEnd.setText(formatMinute(existingNight.window.endMinute))
        }

        fun updateNightFields() {
            val visible = if (nightState.selectedItemPosition == 1) View.VISIBLE else View.GONE
            nightStart.visibility = visible
            nightEnd.visibility = visible
        }
        nightState.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateNightFields()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateNightFields()

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing.isEmpty()) "Nouvelle période factuelle" else "Modifier la période factuelle")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
        if (existing.isNotEmpty()) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fromDate = parseRequiredDate(from, "Date de début invalide") ?: return@setOnClickListener
                val toDate = if (to.text.toString().isBlank()) null else
                    parseRequiredDate(to, "Date de fin invalide") ?: return@setOnClickListener
                if (toDate != null && toDate < fromDate) {
                    to.error = "La fin doit être postérieure ou égale au début"
                    return@setOnClickListener
                }

                val fresh = V2MealBasketFactStore.load(context)
                if (fresh.malformedCount > 0) {
                    Toast.makeText(context, "Journal factuel illisible : enregistrement refusé", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val oldIds = existing.map { it.id }.toSet()
                val otherPeriods = fresh.entries
                    .filter {
                        it.id !in oldIds &&
                            it.companyId == companyId &&
                            it.scope == MealBasketFactJournalV2.Scope.COMPANY &&
                            it.key in managedKeys &&
                            it.effectiveFromEpochDay != null
                    }
                    .map { PeriodKey(it.effectiveFromEpochDay!!, it.effectiveToEpochDay) }
                    .distinct()
                val newPeriod = PeriodKey(fromDate.toEpochDay(), toDate?.toEpochDay())
                if (otherPeriods.any { overlaps(it, newPeriod) }) {
                    Toast.makeText(
                        context,
                        "Cette période chevauche une autre période factuelle. Ferme ou corrige d’abord l’autre période.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                val nightValue = if (nightState.selectedItemPosition == 1) {
                    val startMinute = parseMinute(nightStart, "Heure de début invalide") ?: return@setOnClickListener
                    val endMinute = parseMinute(nightEnd, "Heure de fin invalide") ?: return@setOnClickListener
                    val window = ConventionMealBasketV2.DailyWindow(startMinute, endMinute)
                    if (!window.structurallyValid()) {
                        nightEnd.error = "La plage de nuit doit avoir un début et une fin différents"
                        return@setOnClickListener
                    }
                    MealBasketFactJournalV2.Value.NightWindow(window)
                } else {
                    MealBasketFactJournalV2.Value.Unknown
                }

                val now = System.currentTimeMillis()
                val replacements = listOf(
                    flagEntry(companyId, fromDate, toDate, MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER, posted.selectedItemPosition, now),
                    flagEntry(companyId, fromDate, toDate, MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE, canteen.selectedItemPosition, now),
                    flagEntry(companyId, fromDate, toDate, MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED, vouchers.selectedItemPosition, now),
                    MealBasketFactJournalV2.Entry(
                        id = companyEntryId(companyId, fromDate, MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW),
                        companyId = companyId,
                        scope = MealBasketFactJournalV2.Scope.COMPANY,
                        key = MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW,
                        value = nightValue,
                        source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
                        status = if (nightValue is MealBasketFactJournalV2.Value.NightWindow) DecisionStatusV2.CONFIRMED else DecisionStatusV2.TO_CONFIRM,
                        recordedAtMs = now,
                        effectiveFromEpochDay = fromDate.toEpochDay(),
                        effectiveToEpochDay = toDate?.toEpochDay()
                    )
                )
                if (!V2MealBasketFactStore.replaceAtomically(context, oldIds, replacements)) {
                    Toast.makeText(context, "Échec de l’enregistrement des faits", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Faits datés enregistrés", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing.isNotEmpty()) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (V2MealBasketFactStore.replaceAtomically(context, existing.map { it.id }.toSet(), emptyList())) {
                        dialog.dismiss()
                        Toast.makeText(context, "Période factuelle supprimée", Toast.LENGTH_SHORT).show()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun flagEntry(
        companyId: String,
        from: LocalDate,
        to: LocalDate?,
        key: MealBasketFactJournalV2.Key,
        selection: Int,
        recordedAtMs: Long
    ) = MealBasketFactJournalV2.Entry(
        id = companyEntryId(companyId, from, key),
        companyId = companyId,
        scope = MealBasketFactJournalV2.Scope.COMPANY,
        key = key,
        value = when (selection) {
            1 -> MealBasketFactJournalV2.Value.Flag(true)
            2 -> MealBasketFactJournalV2.Value.Flag(false)
            else -> MealBasketFactJournalV2.Value.Unknown
        },
        source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
        status = if (selection in 1..2) DecisionStatusV2.CONFIRMED else DecisionStatusV2.TO_CONFIRM,
        recordedAtMs = recordedAtMs,
        effectiveFromEpochDay = from.toEpochDay(),
        effectiveToEpochDay = to?.toEpochDay()
    )

    private fun flagSelection(entries: List<MealBasketFactJournalV2.Entry>, key: MealBasketFactJournalV2.Key): Int {
        val entry = entries.singleOrNull { it.key == key } ?: return 0
        if (entry.status != DecisionStatusV2.CONFIRMED) return 0
        val flag = entry.value as? MealBasketFactJournalV2.Value.Flag ?: return 0
        return if (flag.value) 1 else 2
    }

    private fun periodSummary(entries: List<MealBasketFactJournalV2.Entry>): String {
        val first = entries.first()
        val from = LocalDate.ofEpochDay(first.effectiveFromEpochDay!!).format(dateFormatter)
        val to = first.effectiveToEpochDay?.let { LocalDate.ofEpochDay(it).format(dateFormatter) } ?: "en cours"
        fun flag(key: MealBasketFactJournalV2.Key): String = when (flagSelection(entries, key)) {
            1 -> "oui"
            2 -> "non"
            else -> "?"
        }
        val night = entries.singleOrNull { it.key == MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW }
            ?.takeIf { it.status == DecisionStatusV2.CONFIRMED }
            ?.value as? MealBasketFactJournalV2.Value.NightWindow
        val nightLabel = night?.let { "${formatMinute(it.window.startMinute)}–${formatMinute(it.window.endMinute)}" } ?: "?"
        return "Du $from au $to\nPosté: ${flag(MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER)} · Cantine: ${flag(MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE)} · Tickets: ${flag(MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED)} · Nuit: $nightLabel"
    }

    private fun overlaps(a: PeriodKey, b: PeriodKey): Boolean {
        val aEnd = a.toEpochDay ?: Long.MAX_VALUE
        val bEnd = b.toEpochDay ?: Long.MAX_VALUE
        return a.fromEpochDay <= bEnd && b.fromEpochDay <= aEnd
    }

    private fun companyEntryId(companyId: String, from: LocalDate, key: MealBasketFactJournalV2.Key) =
        "company_meal:$companyId:${from.toEpochDay()}:${key.name}"

    private fun addQuestion(context: Context, box: LinearLayout, title: String, detail: String, spinner: Spinner) {
        box.addView(TextView(context).apply {
            text = title
            textSize = 13f
            setPadding(0, dp(context, 12), 0, 0)
        })
        box.addView(TextView(context).apply {
            text = detail
            textSize = 12f
            setPadding(0, dp(context, 2), 0, 0)
        })
        box.addView(spinner, rowParams(context))
    }

    private fun triStateSpinner(context: Context) = Spinner(context).apply {
        adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("À confirmer", "Oui — confirmé", "Non — confirmé")
        )
    }

    private fun parseRequiredDate(field: EditText, error: String): LocalDate? {
        val parsed = runCatching { LocalDate.parse(field.text.toString().trim(), dateFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

    private fun parseMinute(field: EditText, error: String): Int? {
        val parsed = runCatching { LocalTime.parse(field.text.toString().trim(), timeFormatter) }.getOrNull()
        if (parsed == null) {
            field.error = error
            return null
        }
        return parsed.hour * 60 + parsed.minute
    }

    private fun formatMinute(minute: Int): String =
        LocalTime.of(minute / 60, minute % 60).format(timeFormatter)

    private fun field(context: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
    }

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(context, 6) }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
