package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyAgreementDocumentReaderV2
import com.amaury.pointage.v2.PayslipImportStoreV2
import com.amaury.pointage.v2.ProvidentRelaySourceStoreV2
import com.amaury.pointage.v2.V2PayslipStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import com.amaury.pointage.v2.engine.ProvidentRelayDocumentParserV2
import com.amaury.pointage.v2.model.AbsenceV2
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class V2PayslipImportActivity : SalaryAccountActivity() {
    companion object {
        private const val REQUEST_FILE = 9601
        const val EXTRA_COMPANY_ID = "company_id"
        const val EXTRA_COMPANY_NAME = "company_name"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_SOURCE_MIME = "source_mime"
        const val EXTRA_DRAFT_ID = "payslip_draft_id"
        const val EXTRA_RECORD_ID = "payslip_record_id"
        private const val STATE_COMPLETED = "payslip_import_completed"
    }
    private val companyId by lazy { intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty() }
    private val companyName by lazy { intent.getStringExtra(EXTRA_COMPANY_NAME).orEmpty() }
    private val executor = Executors.newSingleThreadExecutor()
    private var status: TextView? = null
    private var profileDraft: PayslipProfileDraftV2? = null
    private var detectedPeriod: PayslipPeriodParserV2.Period? = null
    private var importDraft: PayslipImportStoreV2.Draft? = null
    private var persistForm: (() -> Boolean)? = null
    private var importCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!accountReady()) return
        if (savedInstanceState?.getBoolean(STATE_COMPLETED) == true) { finish(); return }
        val draftId = savedInstanceState?.getString(EXTRA_DRAFT_ID) ?: intent.getStringExtra(EXTRA_DRAFT_ID)
        if (draftId != null) {
            val draft = PayslipImportStoreV2.load(this, companyId, draftId)
            if (draft == null) showImportUnavailable() else showConfirmedDraft(draft)
            return
        }
        val recordId = intent.getStringExtra(EXTRA_RECORD_ID)
        if (recordId != null) {
            // The store resolves the exact existing record; an absent/foreign ID never becomes a new import.
            val draft = PayslipImportStoreV2.create(this, companyId, "", null, recordId)
            if (draft == null) showImportUnavailable() else showConfirmedDraft(draft)
            return
        }
        val existing = intent.getStringExtra(EXTRA_SOURCE_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (existing != null) {
            inspectDocument(existing, intent.getStringExtra(EXTRA_SOURCE_MIME) ?: contentResolver.getType(existing))
            return
        }
        offerPendingImports()
    }

    private fun offerPendingImports() {
        if (!accountReady()) return
        val pending = PayslipImportStoreV2.read(this, companyId)
        if (!pending.reliable) { showImportUnavailable(); return }
        if (pending.drafts.isEmpty()) { chooseDocument(); return }
        val drafts = pending.drafts
        val labels = drafts.map { draft ->
            val date = Calendar.getInstance(Locale.FRANCE).apply { set(draft.year, draft.month, 1) }
            val gross = draft.rawFields[PayslipDocumentParserV2.KEY_GROSS]?.let(::parseAmount)
                ?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "à vérifier"
            val started = SimpleDateFormat("dd/MM/yyyy à HH:mm:ss", Locale.FRANCE).format(java.util.Date(draft.createdAtMs))
            "Bulletin — ${SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(date.time)}\nBrut saisi : $gross • saisie du $started"
        }
        SalaryAccountUi.dialog(this)
            .setTitle("Imports à terminer")
            .setItems(labels.toTypedArray()) { _, which ->
                if (accountReady()) showConfirmedDraft(drafts[which])
            }
            .setNeutralButton("NOUVEAU BULLETIN") { _, _ -> chooseDocument() }
            .setNegativeButton("FERMER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showImportUnavailable() {
        if (!accountReady()) return
        SalaryAccountUi.dialog(this)
            .setTitle("Reprise indisponible")
            .setMessage("Ce bulletin ou sa saisie ne peut pas être repris dans cette entreprise. Les données existantes sont conservées. Rouvre l’espace Salaire pour vérifier le bulletin.")
            .setPositiveButton("FERMER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun chooseDocument() {
        if (!accountReady()) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp"))
        }, REQUEST_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!accountReady()) return
        if (requestCode != REQUEST_FILE) return
        if (resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        inspectDocument(uri, contentResolver.getType(uri))
    }

    private fun shouldInspectForProvident(): Boolean {
        if (companyId.isBlank()) return false
        return V2RightsStore.absencesForCompany(this, companyId)
            .asSequence()
            .filter { it.type == AbsencePayrollImpactV2.TYPE_SICKNESS }
            .any { absence ->
                val relay = V2PayslipStore.sicknessProvidentRelayForAbsence(this, companyId, absence)
                relay?.potentiallyCovered == true && relay.eligibilityConfirmed && relay.relayReached == true
            }
    }

    /**
     * Lecture locale commune aux bulletins et, si nécessaire, aux décomptes prévoyance.
     * Une erreur OCR n'empêche jamais l'import : le formulaire manuel reste disponible.
     */
    private fun inspectDocument(uri: Uri, mime: String?) {
        val label = TextView(this).apply {
            text = "Lecture locale du document…"
            textSize = 16f
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        status = label
        setContentView(label)
        executor.execute {
            if (!accountReady()) return@execute
            var temporary: java.io.File? = null
            try {
                val document = CompanyAgreementDocumentReaderV2.read(this, uri) { message ->
                    runOnUiThread { if (accountReady()) status?.text = message }
                }
                temporary = document.temporaryFile
                val periodDraft = if (document.truncated) null else PayslipPeriodParserV2.parse(document.extractedText)
                val salaryDraft = if (document.truncated) null else PayslipProfileDraftParserV2.parse(document.extractedText)
                // An incomplete document cannot establish complete monthly totals.
                val payslipParsed = if (document.truncated) null else PayslipDocumentParserV2.parse(document.extractedText)
                val providentParsed = if (shouldInspectForProvident()) {
                    ProvidentRelayDocumentParserV2.parse(document.extractedText)
                } else null
                val looksLikeProvident = providentParsed?.let { parsed ->
                    parsed.targetGross60.highConfidence &&
                        (parsed.socialSecurityGross.highConfidence || parsed.observedProvidentGross.highConfidence)
                } == true
                runOnUiThread {
                    if (!accountReady()) return@runOnUiThread
                    detectedPeriod = periodDraft
                    profileDraft = salaryDraft
                    if (looksLikeProvident && companyId.isNotBlank() && providentParsed != null) {
                        offerProvidentLink(uri, document.mimeType, document.displayName, providentParsed, payslipParsed)
                    } else {
                        askConfirmedValues(uri, mime ?: document.mimeType, payslipParsed)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                runOnUiThread { if (accountReady()) askConfirmedValues(uri, mime, null) }
            } finally {
                temporary?.delete()
            }
        }
    }

    private fun offerProvidentLink(
        uri: Uri,
        mime: String?,
        displayName: String,
        parsed: ProvidentRelayDocumentParserV2.Result,
        payslipParsed: PayslipDocumentParserV2.Result?
    ) {
        val eligible = V2RightsStore.absencesForCompany(this, companyId)
            .filter { it.type == AbsencePayrollImpactV2.TYPE_SICKNESS }
            .filter { absence ->
                val relay = V2PayslipStore.sicknessProvidentRelayForAbsence(this, companyId, absence)
                relay?.potentiallyCovered == true && relay.eligibilityConfirmed && relay.relayReached == true
            }
            .sortedByDescending { it.startMs }

        if (eligible.isEmpty()) {
            askConfirmedValues(uri, mime, payslipParsed)
            return
        }
        if (eligible.size == 1) {
            showProvidentConfirmation(uri, mime, displayName, parsed, eligible.first())
            return
        }

        val format = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)
        val zone = ZoneId.systemDefault()
        val labels = eligible.map { absence ->
            val start = Instant.ofEpochMilli(absence.startMs).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(absence.endMs - 1L).atZone(zone).toLocalDate()
            "Arrêt du ${start.format(format)} au ${end.format(format)}"
        }
        SalaryAccountUi.dialog(this)
            .setTitle("Rattacher le décompte à quel arrêt ?")
            .setItems(labels.toTypedArray()) { _, which ->
                showProvidentConfirmation(uri, mime, displayName, parsed, eligible[which])
            }
            .setNeutralButton("TRAITER COMME BULLETIN") { _, _ -> askConfirmedValues(uri, mime, payslipParsed) }
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showProvidentConfirmation(
        uri: Uri,
        mime: String?,
        displayName: String,
        parsed: ProvidentRelayDocumentParserV2.Result,
        absence: AbsenceV2
    ) {
        fun amountField(hintText: String, candidate: ProvidentRelayDocumentParserV2.Candidate) = EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            if (candidate.highConfidence) candidate.amount?.let { setText(String.format(Locale.FRANCE, "%.2f", it)) }
        }
        val target = amountField("60 % du salaire brut de référence", parsed.targetGross60)
        val socialSecurity = amountField("Prestations SS brutes déduites", parsed.socialSecurityGross)
        val observed = amountField("Prévoyance brute réellement versée", parsed.observedProvidentGross)
        val info = TextView(this).apply {
            text = buildString {
                append("Document : ").append(displayName).append('\n')
                append("Lecture locale uniquement. Vérifie les trois montants avant validation.")
                if (parsed.warnings.isNotEmpty()) append("\n\n⚠ ").append(parsed.warnings.joinToString("\n⚠ "))
            }
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(info)
            addView(target, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(socialSecurity, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
            addView(observed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        }
        val dialog = SalaryAccountUi.dialog(this)
            .setTitle("Confirmer le décompte prévoyance")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!accountReady()) return@setOnClickListener
                val values = listOf(target, socialSecurity, observed).map { parseAmount(it.text.toString()) }
                if (values.any { it == null || it < 0.0 }) {
                    Toast.makeText(this, "Vérifie les trois montants bruts du même décompte", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val current = V2RightsStore.absencesForCompany(this, companyId).firstOrNull { it.id == absence.id }
                if (current == null) {
                    Toast.makeText(this, "L'arrêt maladie n'existe plus", Toast.LENGTH_LONG).show()
                    finish()
                    return@setOnClickListener
                }
                V2RightsStore.upsertAbsence(
                    this,
                    current.copy(
                        providentRelayTargetGross60Amount = values[0],
                        providentRelaySocialSecurityGrossAmount = values[1],
                        providentRelayObservedGrossAmount = values[2]
                    )
                )
                ProvidentRelaySourceStoreV2.put(
                    this,
                    current.id,
                    ProvidentRelaySourceStoreV2.Source(uri.toString(), mime, displayName)
                )
                Toast.makeText(this, "Décompte prévoyance rattaché à l'arrêt", Toast.LENGTH_LONG).show()
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }

    private fun askConfirmedValues(
        uri: Uri,
        mime: String?,
        parsed: PayslipDocumentParserV2.Result?
    ) {
        if (!accountReady()) return
        val created = PayslipImportStoreV2.create(this, companyId, uri.toString(), mime)
        if (created == null) { showImportUnavailable(); return }
        val candidates = parsed?.confirmedCandidates().orEmpty()
        val selected = detectedPeriod
        val prepared = created.copy(
            year = selected?.year ?: created.year,
            month = selected?.monthZeroBased ?: created.month,
            rawFields = candidates.mapNotNull { (key, candidate) ->
                candidate.amount?.takeIf { candidate.highConfidence }?.let {
                    key to String.format(Locale.FRANCE, "%.2f", it)
                }
            }.toMap(),
            proposedValues = candidates.mapNotNull { (key, candidate) -> candidate.amount?.let { key to it } }.toMap(),
            proposedEvidence = candidates.mapNotNull { (key, candidate) -> candidate.sourceLabel?.let { key to it.take(300) } }.toMap()
        )
        // A failed draft write must not publish a bulletin or discard the proposed form.
        showConfirmedDraft(PayslipImportStoreV2.save(this, prepared) ?: prepared, parsed)
    }

    private fun showConfirmedDraft(draft: PayslipImportStoreV2.Draft, parsed: PayslipDocumentParserV2.Result? = null) {
        if (!accountReady()) return
        importDraft = draft
        fun amountField(label: String, key: String) = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(256))
            setText(draft.rawFields[key].orEmpty())
        }

        val month = Calendar.getInstance(Locale.FRANCE).apply { set(Calendar.DAY_OF_MONTH, 1) }
        month.set(Calendar.YEAR, draft.year)
        month.set(Calendar.MONTH, draft.month)
        // Loading a draft restores data, never a previous authorization to publish it.
        var periodConfirmed = false
        val monthButton = Button(this).apply {
            text = "Choisir la période du bulletin — requis"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
        }
        val gross = amountField("Brut du bulletin (€) — requis", PayslipDocumentParserV2.KEY_GROSS)
        val netBeforeTax = amountField("Net à payer avant PAS (€)", PayslipDocumentParserV2.KEY_NET_BEFORE_TAX)
        val netTaxable = amountField("Net imposable / fiscal (€)", PayslipDocumentParserV2.KEY_NET_TAXABLE)
        val overtime = amountField("Heures supplémentaires — montant brut (€)", PayslipDocumentParserV2.KEY_OVERTIME_GROSS)
        val premiums = amountField("Primes / majorations — montant brut (€)", PayslipDocumentParserV2.KEY_PREMIUMS_GROSS)
        val baskets = amountField("Paniers / indemnités repas (€)", PayslipDocumentParserV2.KEY_MEAL_BASKETS)
        val mutual = amountField("Mutuelle — part salariale (€)", PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE)
        val provident = amountField("Prévoyance — part salariale (€)", PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE)
        val complementaryRetirement = amountField(
            "Retraite complémentaire / Agirc-Arrco — part salariale totale (€)",
            PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE
        )

        val info = TextView(this).apply {
            text = buildString {
                append("Vérifie les valeurs et confirme la période avant d’enregistrer. « Plus tard » conserve ta saisie sur cet appareil pour la reprendre dans cette entreprise.")
                parsed?.confirmedCandidates()?.forEach { (key, candidate) ->
                    if (candidate.highConfidence && !candidate.sourceLabel.isNullOrBlank()) {
                        append("\n\n").append(key).append(" — source lue : ").append(candidate.sourceLabel)
                    }
                }
                parsed?.warnings?.takeIf { it.isNotEmpty() }?.let {
                    append("\n\n⚠ ").append(it.joinToString("\n⚠ "))
                }
            }
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(info)
            profileDraft?.takeIf { it.hourlyRate != null && companyId.isNotBlank() }?.let { draft ->
                addView(Button(this@V2PayslipImportActivity).apply {
                    text = "Vérifier le taux horaire dans ma fiche salaire"
                    isAllCaps = false
                    setOnClickListener {
                if (!accountReady()) return@setOnClickListener
                        val form = SalaryInformationSheetView(this@V2PayslipImportActivity)
                            .bindCompany(companyId).prefillPayslipDraft(draft)
                        SalaryAccountUi.dialog(this@V2PayslipImportActivity)
                            .setTitle("Fiche salaire — données à confirmer")
                            .setView(ScrollView(this@V2PayslipImportActivity).apply { addView(form) })
                            .setNegativeButton("FERMER", null).show()
                    }
                })
            }
            addView(monthButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            listOf(
                gross,
                netBeforeTax,
                netTaxable,
                overtime,
                premiums,
                baskets,
                mutual,
                provident,
                complementaryRetirement
            ).forEach { field ->
                addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) })
            }
        }
        val scroll = ScrollView(this).apply { addView(box) }
        monthButton.setOnClickListener {
                if (!accountReady()) return@setOnClickListener
            val detected = detectedPeriod
            if (!periodConfirmed && detected != null) {
                SalaryAccountUi.dialog(this).setTitle("Confirmer la période lue")
                    .setMessage(detected.sourceLine)
                    .setPositiveButton("CONFIRMER") { _, _ ->
                        periodConfirmed = true
                        monthButton.text = SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(month.time)
                    }
                    .setNeutralButton("CHOISIR UNE AUTRE PÉRIODE") { _, _ -> chooseMonth(month, monthButton) { periodConfirmed = true } }
                    .setNegativeButton("ANNULER", null).show()
            } else chooseMonth(month, monthButton) { periodConfirmed = true }
        }
        val keyedFields = linkedMapOf(
            PayslipDocumentParserV2.KEY_GROSS to gross,
            PayslipDocumentParserV2.KEY_NET_BEFORE_TAX to netBeforeTax,
            PayslipDocumentParserV2.KEY_NET_TAXABLE to netTaxable,
            PayslipDocumentParserV2.KEY_OVERTIME_GROSS to overtime,
            PayslipDocumentParserV2.KEY_PREMIUMS_GROSS to premiums,
            PayslipDocumentParserV2.KEY_MEAL_BASKETS to baskets,
            PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to mutual,
            PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE to provident,
            PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE to complementaryRetirement
        )
        val feedback = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        box.addView(feedback)
        fun showFeedback(message: String) {
            feedback.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            scroll.post { if (accountReady()) scroll.smoothScrollTo(0, feedback.bottom) }
        }
        persistForm = saveForm@{
            if (!accountReady()) return@saveForm false
            val current = importDraft ?: return@saveForm false
            val changed = current.copy(
                year = month.get(Calendar.YEAR), month = month.get(Calendar.MONTH),
                periodConfirmed = periodConfirmed,
                rawFields = keyedFields.mapValues { it.value.text.toString() }
            )
            val saved = PayslipImportStoreV2.save(this, changed)
            importDraft = saved ?: changed
            if (saved == null) showFeedback("La saisie n’a pas pu être sauvegardée. Garde cet écran ouvert et réessaie ; les données existantes sont conservées.")
            saved != null
        }
        val title = if (companyName.isBlank()) "Contrôle du bulletin" else "Bulletin — $companyName"
        val dialog = SalaryAccountUi.dialog(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("PLUS TARD", null)
            .setNeutralButton("ABANDONNER", null)
            .setCancelable(false)
            .create()
        fun closeForLater() {
            if (persistForm?.invoke() == true) {
                persistForm = null
                dialog.dismiss()
                finish()
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) closeForLater()
                true
            } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { closeForLater() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!accountReady()) return@setOnClickListener
                SalaryAccountUi.dialog(this).setTitle("Abandonner cette saisie ?")
                    .setMessage("Seule cette saisie en attente sera supprimée. Un bulletin déjà enregistré et son document seront conservés.")
                    .setNegativeButton("GARDER", null)
                    .setPositiveButton("ABANDONNER") { _, _ ->
                        val current = importDraft
                        if (accountReady() && current != null && PayslipImportStoreV2.discard(this, current)) {
                            persistForm = null
                            importDraft = null
                            dialog.dismiss()
                            finish()
                        } else showFeedback("La saisie n’a pas pu être supprimée. Elle reste conservée.")
                    }.show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!accountReady()) return@setOnClickListener
                if (!periodConfirmed) {
                    Toast.makeText(this, "Choisis explicitement la période inscrite sur le bulletin", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val invalidField = keyedFields.values.firstOrNull { !PayslipImportConfirmationPolicyV2.isOptionalAmountValid(it.text.toString()) }
                if (invalidField != null) {
                    invalidField.error = "Montant invalide : corrige-le ou laisse le champ vide"
                    invalidField.requestFocus()
                    return@setOnClickListener
                }
                val grossValue = parseAmount(gross.text.toString())
                if (grossValue == null || grossValue < 0.0) {
                    gross.error = "Montant brut requis"
                    return@setOnClickListener
                }
                if (persistForm?.invoke() != true) return@setOnClickListener
                val current = importDraft ?: return@setOnClickListener
                val result = PayslipImportStoreV2.commit(this, current)
                if (!accountReady()) return@setOnClickListener
                if (result.status == PayslipImportStoreV2.Status.SUCCESS) {
                    importCompleted = true
                    persistForm = null
                    importDraft = null
                    Toast.makeText(this, "Bulletin importé • valeurs confirmées enregistrées", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    finish()
                } else {
                    val message = when (result.status) {
                        PayslipImportStoreV2.Status.CONFLICT -> "Ce bulletin ou cette saisie a changé. Rouvre-le depuis l’espace Salaire avant de reprendre ; aucune valeur existante n’a été remplacée."
                        PayslipImportStoreV2.Status.INVALID_INPUT -> "Vérifie les montants et confirme la période du bulletin."
                        else -> if (current.originalRecord != null)
                            "Le bulletin déjà enregistré est conservé, mais l’enregistrement de ses détails n’a pas pu être confirmé. Ta saisie reste disponible ici et dans les imports à terminer."
                        else "Import bloqué : l’enregistrement des valeurs n’a pas pu être confirmé. Ta saisie reste disponible ici et dans les imports à terminer. Les données existantes sont conservées."
                    }
                    showFeedback(message)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "RÉESSAYER"
                }
            }
        }
        dialog.show()
    }

    private fun chooseMonth(selected: Calendar, button: Button, onConfirmed: () -> Unit) {
        val labels = ArrayList<String>(); val months = ArrayList<Calendar>(); val format = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
        val cursor = Calendar.getInstance(Locale.FRANCE).apply { set(Calendar.DAY_OF_MONTH, 1) }
        repeat(36) { months += cursor.clone() as Calendar; labels += format.format(cursor.time).replaceFirstChar { it.uppercase() }; cursor.add(Calendar.MONTH, -1) }
        // A resumed historical bulletin must keep its period selectable beyond the recent-month list.
        if (months.none { it.get(Calendar.YEAR) == selected.get(Calendar.YEAR) && it.get(Calendar.MONTH) == selected.get(Calendar.MONTH) }) {
            months.add(0, selected.clone() as Calendar)
            labels.add(0, format.format(selected.time).replaceFirstChar { it.uppercase() })
        }
        SalaryAccountUi.dialog(this).setTitle("Période du bulletin").setItems(labels.toTypedArray()) { _, which -> selected.timeInMillis = months[which].timeInMillis; button.text = labels[which]; onConfirmed() }.setNegativeButton("Annuler", null).show()
    }

    private fun parseAmount(raw: String): Double? = PayslipImportConfirmationPolicyV2.parseAmount(raw)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        if (accountReady()) persistForm?.invoke()
        importDraft?.id?.let { outState.putString(EXTRA_DRAFT_ID, it) }
        outState.putBoolean(STATE_COMPLETED, importCompleted)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (accountReady()) persistForm?.invoke()
        super.onPause()
    }

    override fun onSalaryAccountInvalidated() {
        // Do not flush an old form into a newly authenticated session.
        persistForm = null
        importDraft = null
        executor.shutdownNow()
        profileDraft = null
        detectedPeriod = null
        status = null
        super.onSalaryAccountInvalidated()
    }

    override fun onDestroy() {
        persistForm = null
        importDraft = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
