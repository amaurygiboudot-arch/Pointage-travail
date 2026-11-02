package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import com.amaury.pointage.v2.CompanyProfessionalStatusStoreV2
import com.amaury.pointage.v2.engine.CompanyProfessionalStatusResolverV2
import java.time.LocalDate
import java.time.format.ResolverStyle
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur du statut professionnel avec période d'effet explicite. */
object CompanyProfessionalStatusDialogV2 {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE).withResolverStyle(ResolverStyle.STRICT)

    fun show(context: Context, companyId: String, onChanged: () -> Unit = {}) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Enregistre uniquement un statut confirmé pour une période précise. Le statut cadre/non-cadre peut modifier les cotisations. La période et la source doivent être confirmées."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val stored = CompanyProfessionalStatusStoreV2.read(context, companyId)
        val records = stored.records.sortedWith(compareBy({ it.effectiveFrom }, { it.id }))

        if (!stored.reliable) {
            box.addView(TextView(context).apply {
                text = "⚠ Stockage des versions de statut incohérent. Les cotisations liées au statut restent à confirmer et AGKGMG ne réutilise pas l'ancien statut sans date.\n• " +
                    stored.warnings.joinToString("\n• ")
                textSize = 12f
                setPadding(0, 0, 0, dp(context, 8))
            })
        } else {
            addLegacyMigrationInfo(context, companyId, records, box)
        }

        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = if (stored.reliable) "Aucun statut professionnel daté enregistré."
                else "Aucun statut professionnel exploitable n'a pu être lu dans le stockage incohérent."
                textSize = 13f
                setPadding(0, dp(context, 4), 0, dp(context, 8))
            })
        } else {
            records.forEach { record ->
                box.addView(Button(context).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = recordLabel(record)
                    isEnabled = stored.reliable
                    setOnClickListener {
                        listDialog?.dismiss()
                        showEditor(context, companyId, record, onChanged)
                    }
                }, rowParams(context))
            }
        }

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "AJOUTER UN STATUT DATÉ"
            isEnabled = stored.reliable
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null, onChanged)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Statut professionnel — versions datées")
            .setView(ScrollView(context).apply { addView(box) })
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: CompanyProfessionalStatusResolverV2.Record?,
        onChanged: () -> Unit
    ) {
        if (!CompanyProfessionalStatusStoreV2.read(context, companyId).reliable) {
            Toast.makeText(context, "Stockage statut incohérent : modification bloquée", Toast.LENGTH_LONG).show()
            return
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val rate = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf("Choisir le statut", "Non-cadre", "Cadre"))
        }
        val start = field(context, "Date de début — JJ/MM/AAAA")
        val end = field(context, "Date de fin — JJ/MM/AAAA (facultatif)")
        val source = field(context, "Source — ex. contrat signé ou bulletin identifié")
        box.addView(rate, rowParams(context))
        listOf(start, end, source).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "La date de fin est incluse. Laisse-la vide si le statut reste applicable jusqu'à nouvel ordre. Deux versions de statut ne peuvent pas se chevaucher."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        if (existing == null) {
            val legacy = SalaryCompanyStore.prefs(context, companyId).getString("professional_status", "").orEmpty()
            rate.setSelection(when (legacy) { "NON_CADRE" -> 1; "CADRE" -> 2; else -> 0 })
        }
        existing?.let { record ->
            rate.setSelection(if (record.status == CompanyProfessionalStatusResolverV2.Status.CADRE) 2 else 1)
            start.setText(formatDate(record.effectiveFrom))
            end.setText(record.effectiveTo?.let(::formatDate).orEmpty())
            source.setText(record.source)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Ajouter un statut professionnel" else "Modifier le statut professionnel")
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("CONFIRMER CETTE VERSION DATÉE", null)
            .setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val status = when (rate.selectedItemPosition) {
                    1 -> CompanyProfessionalStatusResolverV2.Status.NON_CADRE
                    2 -> CompanyProfessionalStatusResolverV2.Status.CADRE
                    else -> null
                }
                if (status == null) {
                    Toast.makeText(context, "Choisis un statut à confirmer", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val startDate = parseRequiredDate(start, "Date de début invalide") ?: return@setOnClickListener
                val endDate = if (end.text.toString().isNotBlank()) {
                    parseRequiredDate(end, "Date de fin invalide") ?: return@setOnClickListener
                } else null
                if (endDate != null && endDate < startDate) {
                    end.error = "La date de fin doit être après le début"
                    return@setOnClickListener
                }
                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique la source du statut confirmé"
                    return@setOnClickListener
                }

                val current = CompanyProfessionalStatusStoreV2.read(context, companyId)
                if (!current.reliable) {
                    Toast.makeText(context, "Stockage statut devenu incohérent : enregistrement bloqué", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val overlapping = current.records
                    .asSequence()
                    .filter { it.id != existing?.id }
                    .firstOrNull { other ->
                        val otherStart = other.effectiveFrom
                        periodsOverlap(startDate, endDate, otherStart, other.effectiveTo)
                    }
                if (overlapping != null) {
                    Toast.makeText(context, "Une version de statut chevauche déjà cette période", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val record = CompanyProfessionalStatusResolverV2.Record(
                    id = existing?.id ?: "professional_status_${UUID.randomUUID()}",
                    status = status,
                    effectiveFrom = startDate,
                    effectiveTo = endDate,
                    source = rawSource,
                    confirmedAtMs = System.currentTimeMillis()
                )
                if (!CompanyProfessionalStatusStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement du statut professionnel", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Statut professionnel daté enregistré", Toast.LENGTH_SHORT).show()
                onChanged()
                show(context, companyId, onChanged)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyProfessionalStatusStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        Toast.makeText(context, "Statut professionnel daté supprimé", Toast.LENGTH_SHORT).show()
                        onChanged()
                        show(context, companyId, onChanged)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun addLegacyMigrationInfo(
        context: Context,
        companyId: String,
        records: List<CompanyProfessionalStatusResolverV2.Record>,
        box: LinearLayout
    ) {
        if (records.isNotEmpty()) return
        val raw = SalaryCompanyStore.prefs(context, companyId)
            .getString("professional_status", "")
            .orEmpty()
            .trim()
        val legacy = raw.takeIf { it == "CADRE" || it == "NON_CADRE" } ?: return
        box.addView(TextView(context).apply {
            text = "⚠ Migration à confirmer\nAncien statut professionnel : $legacy — sans période ni source confirmée.\nAjoute une période datée avant de considérer ce statut comme exact."
            textSize = 12f
            setPadding(0, 0, 0, dp(context, 8))
        })
    }

    private fun periodsOverlap(
        startA: LocalDate,
        endA: LocalDate?,
        startB: LocalDate,
        endB: LocalDate?
    ): Boolean =
        (endA == null || startB <= endA) && (endB == null || startA <= endB)

    private fun parseRequiredDate(field: EditText, error: String): LocalDate? {
        val parsed = runCatching { LocalDate.parse(field.text.toString().trim(), dateFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

    private fun recordLabel(record: CompanyProfessionalStatusResolverV2.Record): String = buildString {
        append("Statut — ").append(record.status.name)
        append("\nDu ").append(formatDate(record.effectiveFrom))
        record.effectiveTo?.let { append(" au ").append(formatDate(it)) }
        append(" • ").append(record.source)
    }

    private fun formatDate(value: LocalDate): String = value.format(dateFormatter)

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
