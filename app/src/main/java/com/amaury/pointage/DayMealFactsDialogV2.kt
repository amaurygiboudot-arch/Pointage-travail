package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.MealBasketFactJournalV2
import com.amaury.pointage.v2.V2MealBasketFactStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Saisie locale des faits repas communs à toutes les sessions d'une entreprise sur une journée.
 *
 * La journée est calculée avec la même entrée réparée que le pont salarial. Aucun horaire, GPS,
 * chantier ou poste n'est converti en fait métier : ces données servent uniquement à retrouver la
 * journée et l'entreprise auxquelles l'utilisateur choisit explicitement d'attacher une réponse.
 */
object DayMealFactsDialogV2 {
    private val managedKeys = listOf(
        MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
        MealBasketFactJournalV2.Key.CAN_RETURN_HOME_FOR_MEAL,
        MealBasketFactJournalV2.Key.WORKS_AWAY_FROM_USUAL_WORKPLACE,
        MealBasketFactJournalV2.Key.MUST_EAT_AT_WORKPLACE,
        MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE,
        MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED,
        MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
        MealBasketFactJournalV2.Key.OTHER_SAME_NATURE_MEAL_BENEFIT
    )

    private data class Question(
        val key: MealBasketFactJournalV2.Key,
        val title: String,
        val detail: String
    )

    private data class TargetKey(val companyId: String, val day: LocalDate)
    private data class DayTarget(val companyId: String, val day: LocalDate, val sessionCount: Int)

    private val questions = listOf(
        Question(
            MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
            "Travail posté / en équipes",
            "Pour toutes les sessions de cette entreprise ce jour-là, étais-tu réellement en travail posté ou en équipes ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.CAN_RETURN_HOME_FOR_MEAL,
            "Retour au domicile possible pour le repas",
            "Pour le ou les repas concernés ce jour-là, pouvais-tu réellement rentrer chez toi et revenir dans le temps disponible ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.WORKS_AWAY_FROM_USUAL_WORKPLACE,
            "Travail hors du lieu habituel",
            "Toutes les sessions concernées de cette journée ont-elles été effectuées hors de ton lieu de travail habituel ? Aucun lieu GPS n'est utilisé pour répondre automatiquement."
        ),
        Question(
            MealBasketFactJournalV2.Key.MUST_EAT_AT_WORKPLACE,
            "Repas imposé sur le lieu de travail",
            "L'organisation du travail t'imposait-elle réellement de prendre le ou les repas concernés sur le lieu de travail pendant toute cette journée ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE,
            "Cantine / restauration d'entreprise accessible",
            "Une cantine ou restauration d'entreprise était-elle réellement accessible pour le ou les repas concernés de cette journée ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED,
            "Repas fourni par l'employeur",
            "L'employeur a-t-il effectivement fourni ou pris directement en charge le ou les repas concernés de cette journée ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
            "Titre-restaurant fourni",
            "Un titre-restaurant a-t-il effectivement été attribué pour le ou les repas concernés de cette journée ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.OTHER_SAME_NATURE_MEAL_BENEFIT,
            "Autre avantage couvrant le même repas",
            "Un autre avantage a-t-il clairement couvert le ou les mêmes repas ce jour-là ? En cas de doute ou si la réponse varie entre sessions, n'impose pas un fait journée."
        )
    )

    fun show(context: Context) {
        val loaded = V2MealBasketFactStore.load(context)
        if (loaded.malformedCount > 0) {
            AlertDialog.Builder(context)
                .setTitle("Faits repas par journée")
                .setMessage(
                    "Le journal local contient ${loaded.malformedCount} fait(s) illisible(s). " +
                        "HoraTrack refuse de le réécrire pour ne pas effacer une information potentiellement bloquante."
                )
                .setPositiveButton("FERMER", null)
                .show()
            return
        }

        val zoneId = ZoneId.systemDefault()
        val grouped = linkedMapOf<TargetKey, Int>()
        V2RuntimeStore.allSessions(context).forEach { session ->
            val companyId = SalaryCompanyStore.canonicalCompanyIdForEmployerId(context, session.employerId)
                ?: return@forEach
            val entry = WorkTimePolicyV2.repairKnownCountedEntry(session.realArrivalMs, session.countedEntryMs)
                ?: session.countedEntryMs
                ?: session.realArrivalMs
                ?: return@forEach
            val day = Instant.ofEpochMilli(entry).atZone(zoneId).toLocalDate()
            val key = TargetKey(companyId, day)
            grouped[key] = (grouped[key] ?: 0) + 1
        }

        val targets = grouped.map { (key, count) -> DayTarget(key.companyId, key.day, count) }
            .sortedWith(compareByDescending<DayTarget> { it.day }.thenBy { it.companyId })
        if (targets.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Faits repas par journée")
                .setMessage("Aucune journée V2 rattachée sans ambiguïté à une entreprise n'est disponible.")
                .setPositiveButton("FERMER", null)
                .show()
            return
        }

        val companies = SalaryCompanyStore.list(context).associateBy { it.id }
        val labels = targets.map { target ->
            targetLabel(target, companies[target.companyId]?.name)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Choisir une journée")
            .setItems(labels) { _, which ->
                val target = targets[which]
                showEditor(context, target, companies[target.companyId]?.name)
            }
            .setNegativeButton("FERMER", null)
            .show()
    }

    private fun showEditor(context: Context, target: DayTarget, companyName: String?) {
        val loaded = V2MealBasketFactStore.load(context)
        if (loaded.malformedCount > 0) {
            Toast.makeText(context, "Journal factuel illisible : modification refusée", Toast.LENGTH_LONG).show()
            return
        }
        val epochDay = target.day.toEpochDay()
        val existing = loaded.entries.filter {
            belongsToCompany(context, it.companyId, target.companyId) &&
                it.scope == MealBasketFactJournalV2.Scope.DAY &&
                it.dayEpochDay == epochDay &&
                it.key in managedKeys
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 8))
        }
        body.addView(TextView(context).apply {
            text = targetLabel(target, companyName)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 8))
        })
        body.addView(TextView(context).apply {
            text = "Un fait JOURNÉE doit être vrai pour toutes les sessions de cette entreprise rattachées à cette date. " +
                "Si une réponse varie dans la journée, laisse « Pas de fait journée » et renseigne la session concernée. " +
                "Les faits SESSION restent prioritaires sur les faits JOURNÉE. HoraTrack ne déduit aucune réponse du GPS, du chantier, de l'heure ou du poste."
            textSize = 12f
            setPadding(0, 0, 0, dp(context, 8))
        })

        val spinners = linkedMapOf<MealBasketFactJournalV2.Key, Spinner>()
        questions.forEach { question ->
            body.addView(TextView(context).apply {
                text = question.title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(context, 12), 0, 0)
            })
            body.addView(TextView(context).apply {
                text = question.detail
                textSize = 12f
                setPadding(0, dp(context, 2), 0, dp(context, 2))
            })
            val spinner = dayStateSpinner(context).apply {
                setSelection(selection(existing, question.key))
            }
            spinners[question.key] = spinner
            body.addView(spinner, rowParams(context))
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Faits repas de la journée")
            .setView(scroll)
            .setPositiveButton("ENREGISTRER", null)
            .setNeutralButton("EFFACER LES FAITS", null)
            .setNegativeButton("ANNULER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fresh = V2MealBasketFactStore.load(context)
                if (fresh.malformedCount > 0) {
                    Toast.makeText(context, "Journal factuel illisible : enregistrement refusé", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val idsToReplace = fresh.entries.filter {
                    belongsToCompany(context, it.companyId, target.companyId) &&
                        it.scope == MealBasketFactJournalV2.Scope.DAY &&
                        it.dayEpochDay == epochDay &&
                        it.key in managedKeys
                }.map { it.id }.toSet()

                val now = System.currentTimeMillis()
                val replacements = questions.mapNotNull { question ->
                    when (val selected = spinners.getValue(question.key).selectedItemPosition) {
                        0 -> null
                        1 -> MealBasketFactJournalV2.Entry(
                            id = entryId(target.companyId, epochDay, question.key),
                            companyId = target.companyId,
                            scope = MealBasketFactJournalV2.Scope.DAY,
                            key = question.key,
                            value = MealBasketFactJournalV2.Value.Unknown,
                            source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
                            status = DecisionStatusV2.TO_CONFIRM,
                            recordedAtMs = now,
                            dayEpochDay = epochDay
                        )
                        2, 3 -> MealBasketFactJournalV2.Entry(
                            id = entryId(target.companyId, epochDay, question.key),
                            companyId = target.companyId,
                            scope = MealBasketFactJournalV2.Scope.DAY,
                            key = question.key,
                            value = MealBasketFactJournalV2.Value.Flag(selected == 2),
                            source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
                            status = DecisionStatusV2.CONFIRMED,
                            recordedAtMs = now,
                            dayEpochDay = epochDay
                        )
                        else -> null
                    }
                }

                if (!V2MealBasketFactStore.replaceAtomically(context, idsToReplace, replacements)) {
                    Toast.makeText(context, "Échec de l'enregistrement des faits", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Faits de la journée enregistrés", Toast.LENGTH_SHORT).show()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val fresh = V2MealBasketFactStore.load(context)
                if (fresh.malformedCount > 0) {
                    Toast.makeText(context, "Journal factuel illisible : suppression refusée", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val ids = fresh.entries.filter {
                    belongsToCompany(context, it.companyId, target.companyId) &&
                        it.scope == MealBasketFactJournalV2.Scope.DAY &&
                        it.dayEpochDay == epochDay &&
                        it.key in managedKeys
                }.map { it.id }.toSet()
                if (ids.isEmpty() || V2MealBasketFactStore.replaceAtomically(context, ids, emptyList())) {
                    dialog.dismiss()
                    Toast.makeText(context, "Faits de la journée effacés", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun selection(
        entries: List<MealBasketFactJournalV2.Entry>,
        key: MealBasketFactJournalV2.Key
    ): Int {
        val matching = entries.filter { it.key == key }
        if (matching.isEmpty()) return 0
        if (matching.size != 1) return 1
        val entry = matching.single()
        if (entry.status != DecisionStatusV2.CONFIRMED) return 1
        val flag = entry.value as? MealBasketFactJournalV2.Value.Flag ?: return 1
        return if (flag.value) 2 else 3
    }

    private fun dayStateSpinner(context: Context) = Spinner(context).apply {
        adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "Pas de fait journée — résoudre le niveau entreprise",
                "À confirmer — bloquer le niveau entreprise ce jour-là",
                "Oui — confirmé pour cette journée",
                "Non — confirmé pour cette journée"
            )
        )
    }

    private fun targetLabel(target: DayTarget, companyName: String?): String {
        val date = target.day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE))
        val company = companyName?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Entreprise ${target.companyId.take(8)}…"
        val sessions = if (target.sessionCount == 1) "1 session" else "${target.sessionCount} sessions"
        return "$date · $company · $sessions"
    }

    private fun belongsToCompany(context: Context, storedCompanyId: String, companyId: String): Boolean =
        storedCompanyId == companyId ||
            SalaryCompanyStore.canonicalCompanyIdForEmployerId(context, storedCompanyId) == companyId

    private fun entryId(
        companyId: String,
        epochDay: Long,
        key: MealBasketFactJournalV2.Key
    ): String = "day_meal:$companyId:$epochDay:${key.name}"

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(context, 4) }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
