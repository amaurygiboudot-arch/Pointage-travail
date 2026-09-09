package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
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
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saisie locale des faits repas propres à une session de travail précise.
 *
 * Aucun signal GPS, horaire ou lieu n'est transformé automatiquement en fait métier. La session
 * sert uniquement d'identifiant et de contexte d'affichage ; toutes les valeurs sont confirmées
 * explicitement par l'utilisateur ou restent TO_CONFIRM.
 */
object SessionMealFactsDialogV2 {
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

    private val questions = listOf(
        Question(
            MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
            "Travail posté / en équipes",
            "Pour cette session seulement : étais-tu réellement en travail posté ou en équipes ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.CAN_RETURN_HOME_FOR_MEAL,
            "Retour au domicile possible pour le repas",
            "Pendant la pause repas concernée par cette session, pouvais-tu réellement rentrer chez toi et revenir dans le temps disponible ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.WORKS_AWAY_FROM_USUAL_WORKPLACE,
            "Travail hors du lieu habituel",
            "Cette session a-t-elle été effectuée hors de ton lieu de travail habituel ? Le lieu GPS affiché n'est jamais utilisé pour répondre automatiquement."
        ),
        Question(
            MealBasketFactJournalV2.Key.MUST_EAT_AT_WORKPLACE,
            "Repas imposé sur le lieu de travail",
            "Pour cette session, l'organisation du travail t'imposait-elle réellement de prendre le repas sur le lieu de travail ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE,
            "Cantine / restauration d'entreprise accessible",
            "Une cantine ou restauration d'entreprise était-elle réellement accessible pendant cette session et au moment du repas concerné ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED,
            "Repas fourni par l'employeur",
            "L'employeur a-t-il effectivement fourni ou pris directement en charge le repas concerné pendant cette session ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
            "Titre-restaurant fourni",
            "Un titre-restaurant a-t-il effectivement été attribué pour le repas concerné par cette session ?"
        ),
        Question(
            MealBasketFactJournalV2.Key.OTHER_SAME_NATURE_MEAL_BENEFIT,
            "Autre avantage couvrant le même repas",
            "As-tu reçu un autre avantage clairement destiné à couvrir ce même repas (par exemple une autre indemnité, un panier ou un remboursement) ? En cas de doute, laisse À confirmer."
        )
    )

    fun show(context: Context) {
        val loaded = V2MealBasketFactStore.load(context)
        if (loaded.malformedCount > 0) {
            AlertDialog.Builder(context)
                .setTitle("Faits repas par session")
                .setMessage(
                    "Le journal local contient ${loaded.malformedCount} fait(s) illisible(s). " +
                        "HoraTrack refuse de le réécrire pour ne pas effacer une information potentiellement bloquante."
                )
                .setPositiveButton("FERMER", null)
                .show()
            return
        }

        val sessions = V2RuntimeStore.allSessions(context)
            .filter { !it.id.isBlank() && (it.realArrivalMs ?: 0L) > 0L }
            .sortedByDescending { it.realArrivalMs }

        if (sessions.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Faits repas par session")
                .setMessage("Aucune session V2 n'est encore disponible.")
                .setPositiveButton("FERMER", null)
                .show()
            return
        }

        val companies = SalaryCompanyStore.list(context).associateBy { it.id }
        val labels = sessions.map { session ->
            sessionLabel(session, companies[session.employerId]?.name)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Choisir une session")
            .setItems(labels) { _, which ->
                val session = sessions[which]
                val companyId = session.employerId?.trim().orEmpty()
                if (companyId.isBlank()) {
                    AlertDialog.Builder(context)
                        .setTitle("Session sans entreprise")
                        .setMessage(
                            "Cette session n'a pas d'entreprise identifiée. HoraTrack ne peut pas rattacher ses faits au moteur salarial sans fabriquer ce lien."
                        )
                        .setPositiveButton("FERMER", null)
                        .show()
                } else {
                    showEditor(context, session, companies[companyId]?.name)
                }
            }
            .setNegativeButton("FERMER", null)
            .show()
    }

    private fun showEditor(context: Context, session: WorkSessionV2, companyName: String?) {
        val companyId = session.employerId?.trim().orEmpty()
        if (companyId.isBlank() || session.id.isBlank()) return

        val loaded = V2MealBasketFactStore.load(context)
        if (loaded.malformedCount > 0) {
            Toast.makeText(context, "Journal factuel illisible : modification refusée", Toast.LENGTH_LONG).show()
            return
        }
        val existing = loaded.entries.filter {
            it.companyId == companyId &&
                it.scope == MealBasketFactJournalV2.Scope.SESSION &&
                it.sessionId == session.id &&
                it.key in managedKeys
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 8))
        }
        body.addView(TextView(context).apply {
            text = sessionLabel(session, companyName)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 8))
        })
        body.addView(TextView(context).apply {
            text = "Ces réponses valent uniquement pour cette session. HoraTrack ne déduit rien du GPS, du chantier, de l'heure ou du poste. « À confirmer » bloque le fait concerné au lieu d'inventer une réponse."
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
            val spinner = triStateSpinner(context).apply {
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
            .setTitle("Faits repas de la session")
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
                // On relit les IDs au moment de sauvegarder afin de ne pas laisser une ancienne
                // entrée concurrente de la même session survivre à l'édition.
                val idsToReplace = fresh.entries.filter {
                    it.companyId == companyId &&
                        it.scope == MealBasketFactJournalV2.Scope.SESSION &&
                        it.sessionId == session.id &&
                        it.key in managedKeys
                }.map { it.id }.toSet()

                val now = System.currentTimeMillis()
                val replacements = questions.map { question ->
                    val selected = spinners.getValue(question.key).selectedItemPosition
                    MealBasketFactJournalV2.Entry(
                        id = entryId(companyId, session.id, question.key),
                        companyId = companyId,
                        scope = MealBasketFactJournalV2.Scope.SESSION,
                        key = question.key,
                        value = when (selected) {
                            1 -> MealBasketFactJournalV2.Value.Flag(true)
                            2 -> MealBasketFactJournalV2.Value.Flag(false)
                            else -> MealBasketFactJournalV2.Value.Unknown
                        },
                        source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
                        status = if (selected in 1..2) DecisionStatusV2.CONFIRMED else DecisionStatusV2.TO_CONFIRM,
                        recordedAtMs = now,
                        sessionId = session.id
                    )
                }

                if (!V2MealBasketFactStore.replaceAtomically(context, idsToReplace, replacements)) {
                    Toast.makeText(context, "Échec de l'enregistrement des faits", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Faits de la session enregistrés", Toast.LENGTH_SHORT).show()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val fresh = V2MealBasketFactStore.load(context)
                if (fresh.malformedCount > 0) {
                    Toast.makeText(context, "Journal factuel illisible : suppression refusée", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val ids = fresh.entries.filter {
                    it.companyId == companyId &&
                        it.scope == MealBasketFactJournalV2.Scope.SESSION &&
                        it.sessionId == session.id &&
                        it.key in managedKeys
                }.map { it.id }.toSet()
                if (ids.isEmpty() || V2MealBasketFactStore.replaceAtomically(context, ids, emptyList())) {
                    dialog.dismiss()
                    Toast.makeText(context, "Faits de la session effacés", Toast.LENGTH_SHORT).show()
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
        if (matching.size != 1) return 0
        val entry = matching.single()
        if (entry.status != DecisionStatusV2.CONFIRMED) return 0
        val flag = entry.value as? MealBasketFactJournalV2.Value.Flag ?: return 0
        return if (flag.value) 1 else 2
    }

    private fun triStateSpinner(context: Context) = Spinner(context).apply {
        adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("À confirmer", "Oui — confirmé", "Non — confirmé")
        )
    }

    private fun sessionLabel(session: WorkSessionV2, companyName: String?): String {
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        val start = session.realArrivalMs?.let { formatter.format(Date(it)) } ?: "début inconnu"
        val endFormatter = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val end = session.realExitMs?.let { endFormatter.format(Date(it)) }
            ?: if (session.status == SessionStatusV2.OPEN) "en cours" else "fin inconnue"
        val company = companyName?.trim().takeUnless { it.isNullOrBlank() }
            ?: session.employerId?.let { "Entreprise ${it.take(8)}…" }
            ?: "Sans entreprise"
        val place = session.placeLabel?.trim().takeUnless { it.isNullOrBlank() }
        return buildString {
            append(start).append(" → ").append(end).append(" · ").append(company)
            if (place != null) append("\n").append(place)
        }
    }

    private fun entryId(
        companyId: String,
        sessionId: String,
        key: MealBasketFactJournalV2.Key
    ): String = "session_meal:$companyId:$sessionId:${key.name}"

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(context, 4) }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
