package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.DatedWorkRuleStoreV2
import com.amaury.pointage.v2.V2EmploymentContractHistoryStore
import com.amaury.pointage.v2.engine.DatedWorkRuleContractScopeV2
import com.amaury.pointage.v2.engine.DatedWorkRuleOwnerAuthorizationV2
import com.amaury.pointage.v2.engine.DatedWorkRuleV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.engine.WorkRuleConfirmationV2
import com.amaury.pointage.v2.engine.WorkRuleTopicV2
import com.google.firebase.auth.FirebaseAuth
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Configuration individuelle des références de règles. L'utilisateur doit confirmer
 * qu'il s'agit de son propre contrat. Pas de copie entre comptes, pas de taux inventé.
 * Les domaines hors référentiel contractuel sont enregistrés TO_CONFIRM.
 */
class DatedWorkRulesEditorV2(
    context: Context,
    private val companyId: String
) : LinearLayout(context) {
    private val startingUid: String? = activeUid()
    private var contracts: List<EmploymentContractSnapshotV2> = emptyList()
    private val contractSpinner = Spinner(context)
    private val topicSpinner = Spinner(context)
    private val fromField = EditText(context)
    private val toField = EditText(context)
    private val sourceField = EditText(context)
    private val referenceField = EditText(context)
    private val ownContract = CheckBox(context)
    private val status = label("")
    private val history = label("")

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(18))
        addView(label("RÈGLES INDIVIDUELLES DATÉES", true))
        addView(label("Une règle ne s'applique qu'à votre compte, votre contrat et ses dates. Une majoration n'est jamais confirmée par un simple champ libre."))
        addView(status)
        addView(label("Version de votre contrat", true))
        addView(contractSpinner, row())
        addView(label("Sujet à qualifier", true))
        topicSpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            WorkRuleTopicV2.values().map(::topicTitle)
        )
        addView(topicSpinner, row())
        addView(label("Date de début (AAAA-MM-JJ)"))
        fromField.hint = "2026-10-01"
        fromField.setSingleLine(true)
        addView(fromField, row())
        addView(label("Date de fin incluse (AAAA-MM-JJ, facultative pour contrat ouvert)"))
        toField.hint = "Facultatif si votre contrat est en cours"
        toField.setSingleLine(true)
        addView(toField, row())
        addView(label("Identifiant de la source (contrat, accord, document)"))
        sourceField.hint = "Source existante ou à vérifier"
        sourceField.setSingleLine(true)
        addView(sourceField, row())
        addView(label("Référence exacte / article"))
        referenceField.hint = "Référence du texte à vérifier"
        referenceField.setSingleLine(true)
        addView(referenceField, row())
        ownContract.text = "Je confirme qu'il s'agit de mon propre contrat de travail."
        addView(ownContract, row())
        addView(button("VÉRIFIER ET ENREGISTRER") { confirmBeforeSave() }, row())
        addView(label("Règles enregistrées pour ce compte et cette version", true))
        addView(history)
        contractSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                val contract = contracts.getOrNull(i) ?: return
                fromField.setText(LocalDate.ofEpochDay(contract.effectiveFromEpochDay).toString())
                toField.setText(contract.effectiveToEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty())
                sourceField.setText(contract.sourceId)
                referenceField.setText("contract:${contract.versionId}")
                ownContract.isChecked = false
                refreshHistory()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        loadVersions()
    }

    private val accountListener by lazy {
        FirebaseAuth.AuthStateListener {
            if (activeUid() != startingUid) post { hideWhenAccountChanges() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { FirebaseAuth.getInstance().addAuthStateListener(accountListener) }
        if (activeUid() != startingUid) hideWhenAccountChanges()
    }

    override fun onDetachedFromWindow() {
        runCatching { FirebaseAuth.getInstance().removeAuthStateListener(accountListener) }
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && activeUid() != startingUid) hideWhenAccountChanges()
    }

    private fun hideWhenAccountChanges() {
        contracts = emptyList()
        fromField.setText("")
        toField.setText("")
        sourceField.setText("")
        referenceField.setText("")
        history.text = ""
        ownContract.isChecked = false
        contractSpinner.isEnabled = false
        // Remove the old user's company/contract form immediately, without deleting facts.
        for (index in 3 until childCount) getChildAt(index).visibility = View.GONE
        status.text = "Le compte a changé : fermez cet écran avant de continuer."
    }

    private fun loadVersions() {
        if (startingUid == null) {
            status.text = "Connectez-vous à votre compte pour enregistrer des règles personnelles."
            contractSpinner.isEnabled = false
            return
        }
        val companies = SalaryCompanyStore.readConfirmed(context)
        val stored = V2EmploymentContractHistoryStore.readConfirmed(context)
        if (!companies.reliable || companies.companies.none { it.id == companyId } || !stored.reliable) {
            status.text = "Entreprise ou historique des contrats non fiable : impossible de sélectionner une règle."
            contractSpinner.isEnabled = false
            return
        }
        contracts = stored.snapshots.filter { it.contract.employerId == companyId }
            .sortedByDescending { it.effectiveFromEpochDay }
        if (contracts.isEmpty()) {
            status.text = "Enregistrez d'abord une version datée du contrat dans Salaire V2."
            contractSpinner.isEnabled = false
            return
        }
        contractSpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            contracts.map { c ->
                "${c.versionId} • ${LocalDate.ofEpochDay(c.effectiveFromEpochDay)} → " +
                    (c.effectiveToEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "en cours")
            }
        )
        status.text = "Profil personnel du compte connecté. Les règles non prouvées restent « à confirmer »."
    }

    private fun refreshHistory() {
        val uid = activeUid()
        val snapshot = currentContract()
        if (uid == null || uid != startingUid || snapshot == null) {
            history.text = ""
            return
        }
        val owner = DatedWorkRuleOwnerAuthorizationV2.ownerForSelf(uid, companyId, snapshot.versionId)
        val read = owner?.let { DatedWorkRuleStoreV2.read(context, it) }
        history.text = when {
            read == null || !read.reliable -> "Lecture bloquée : compte, contrat ou stockage incohérent."
            read.records.isEmpty() -> "Aucune règle enregistrée pour ce contrat."
            else -> read.records.sortedByDescending { it.effectiveFromEpochDay }.joinToString("\n\n") { r ->
                "${topicTitle(r.topic)} • ${r.confirmation.name}\n" +
                    "${LocalDate.ofEpochDay(r.effectiveFromEpochDay)} → " +
                    (r.effectiveToEpochDay?.let { LocalDate.ofEpochDay(it - 1).toString() } ?: "en cours") +
                    "\nSource : ${r.sourceId.ifBlank { "à fournir" }} | ${r.ruleReference.ifBlank { "à préciser" }}"
            }
        }
    }

    private fun currentContract() = contracts.getOrNull(contractSpinner.selectedItemPosition)
    private fun selectedTopic() = WorkRuleTopicV2.values().getOrNull(topicSpinner.selectedItemPosition)

    private fun confirmBeforeSave() {
        val uid = activeUid()
        if (uid == null || uid != startingUid) return message("Compte changé ou déconnecté : enregistrement refusé.")
        val contract = currentContract() ?: return message("Version de contrat non confirmée.")
        val owner = DatedWorkRuleOwnerAuthorizationV2.ownerForSelf(uid, companyId, contract.versionId)
            ?: return message("Identité du salarié non disponible.")
        if (!ownContract.isChecked) return message("Confirmez que ce contrat est bien le vôtre.")
        val topic = selectedTopic() ?: return message("Choisissez un sujet.")
        val fromDay = parseDay(fromField.text.toString())
            ?: return message("Date de début invalide : utilisez AAAA-MM-JJ.")
        val toRaw = toField.text.toString().trim()
        val toExclusive = if (toRaw.isEmpty()) null else {
            val end = parseDay(toRaw) ?: return message("Date de fin invalide.")
            end + 1L
        }
        val src = sourceField.text.toString().trim()
        val ref = referenceField.text.toString().trim()
        if (src.length > 512 || ref.length > 512) return message("Référence trop longue.")
        val contractReference =
            topic == WorkRuleTopicV2.TIME_ACCOUNTING &&
                src == contract.sourceId && ref == "contract:${contract.versionId}" &&
                contract.checkedAtMs > 0L
        val decision = if (contractReference) WorkRuleConfirmationV2.CONFIRMED
            else WorkRuleConfirmationV2.TO_CONFIRM
        val proposal = DatedWorkRuleV2(
            id = UUID.randomUUID().toString(),
            owner = owner,
            topic = topic,
            effectiveFromEpochDay = fromDay,
            effectiveToEpochDay = toExclusive,
            sourceId = src,
            ruleReference = ref,
            checkedAtMs = if (contractReference) contract.checkedAtMs else 0L,
            confirmation = decision
        )
        if (!DatedWorkRuleContractScopeV2.verifiedApplicability(proposal, contract)) {
            return message("Dates hors du contrat ou provenance non vérifiable.")
        }
        val read = DatedWorkRuleStoreV2.read(context, owner)
        if (!read.reliable) return message("Stockage non fiable : aucune écriture autorisée.")
        val open = if (decision == WorkRuleConfirmationV2.CONFIRMED) {
            read.records.singleOrNull {
                it.topic == topic && it.confirmation == WorkRuleConfirmationV2.CONFIRMED &&
                    it.effectiveToEpochDay == null && it.effectiveFromEpochDay < fromDay
            }
        } else null
        val preview = buildString {
            append("Entreprise : ${companyId}\nContrat : ${contract.versionId}\n")
            append("Sujet : ${topicTitle(topic)}\nDate d'effet : ${LocalDate.ofEpochDay(fromDay)}\n")
            append("Fin incluse : ${toExclusive?.let { LocalDate.ofEpochDay(it - 1) } ?: "en cours"}\n")
            append("Source : ${src.ifBlank { "à fournir" }}\nRéférence : ${ref.ifBlank { "à vérifier" }}\n")
            append("Qualification : ${if (contractReference) "provenance contractuelle vérifiée, PAS taux de paie" else "à confirmer (ne certifie aucun salaire)"}")
            if (open != null) append("\nL'ancienne version ${open.id} sera fermée à la date d'effet.")
        }
        AlertDialog.Builder(context)
            .setTitle("Vérifier la règle datée")
            .setMessage(preview)
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("ENREGISTRER") { _, _ ->
                if (activeUid() != startingUid) return@setPositiveButton message("Compte changé.")
                val saved = if (open == null) {
                    DatedWorkRuleStoreV2.append(context, owner, proposal)
                } else {
                    DatedWorkRuleStoreV2.replaceOpenVersion(context, owner, open.id, proposal)
                }
                message(if (saved) "Référence enregistrée avec sa date et sa source." else
                    "Enregistrement refusé : doublon, conflit, contrat ou compte à vérifier.")
                if (saved) refreshHistory()
            }.show()
    }

    private fun activeUid(): String? = runCatching {
        FirebaseAuth.getInstance().currentUser?.uid?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun parseDay(value: String): Long? = try {
        LocalDate.parse(value.trim()).toEpochDay()
    } catch (_: DateTimeParseException) { null }

    private fun topicTitle(topic: WorkRuleTopicV2): String = when (topic) {
        WorkRuleTopicV2.TIME_ACCOUNTING -> "Décompte du temps contractuel"
        WorkRuleTopicV2.PAUSE_COMPENSATION -> "Pauses rémunérées ou non"
        WorkRuleTopicV2.NIGHT_WORK -> "Travail de nuit"
        WorkRuleTopicV2.WEEKEND_WORK -> "Samedi et dimanche"
        WorkRuleTopicV2.PUBLIC_HOLIDAY -> "Jours fériés"
        WorkRuleTopicV2.OVERTIME -> "Heures supplémentaires ou complémentaires"
        WorkRuleTopicV2.ON_CALL -> "Astreintes et interventions"
        WorkRuleTopicV2.BUSINESS_TRAVEL -> "Déplacements professionnels"
        WorkRuleTopicV2.MEAL_ALLOWANCE -> "Indemnités de repas / panier"
        WorkRuleTopicV2.ABSENCE -> "Absences"
    }

    private fun label(value: String, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = if (bold) 15f else 14f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(7), 0, dp(6))
    }

    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; minHeight = dp(48)
        setOnClickListener { action() }
        gravity = Gravity.CENTER
    }

    private fun row() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun message(value: String) { Toast.makeText(context, value, Toast.LENGTH_LONG).show() }
}
