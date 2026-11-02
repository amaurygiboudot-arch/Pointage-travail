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
import com.amaury.pointage.v2.PayslipObservedValuesStoreV2
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

class V2PayslipImportActivity : Activity() {
    companion object {
        private const val REQUEST_FILE = 9601
        const val EXTRA_COMPANY_ID = "company_id"
        const val EXTRA_COMPANY_NAME = "company_name"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_SOURCE_MIME = "source_mime"
    }
    private val companyId by lazy { intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty() }
    private val companyName by lazy { intent.getStringExtra(EXTRA_COMPANY_NAME).orEmpty() }
    private val executor = Executors.newSingleThreadExecutor()
    private var status: TextView? = null
    private var profileDraft: PayslipProfileDraftV2? = null
    private var detectedPeriod: PayslipPeriodParserV2.Period? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra(EXTRA_SOURCE_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (existing != null) {
            inspectDocument(existing, intent.getStringExtra(EXTRA_SOURCE_MIME) ?: contentResolver.getType(existing))
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp"))
        }, REQUEST_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
            var temporary: java.io.File? = null
            try {
                val document = CompanyAgreementDocumentReaderV2.read(this, uri) { message ->
                    runOnUiThread { if (!isFinishing && !isDestroyed) status?.text = message }
                }
                temporary = document.temporaryFile
                detectedPeriod = if (document.truncated) null else PayslipPeriodParserV2.parse(document.extractedText)
                profileDraft = if (document.truncated) null else PayslipProfileDraftParserV2.parse(document.extractedText)
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
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (looksLikeProvident && companyId.isNotBlank() && providentParsed != null) {
                        offerProvidentLink(uri, document.mimeType, document.displayName, providentParsed, payslipParsed)
                    } else {
                        askConfirmedValues(uri, mime ?: document.mimeType, payslipParsed)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                runOnUiThread { if (!isFinishing && !isDestroyed) askConfirmedValues(uri, mime, null) }
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
        AlertDialog.Builder(this)
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirmer le décompte prévoyance")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
        fun amountField(label: String, candidate: PayslipDocumentParserV2.Candidate? = null) = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            if (candidate?.highConfidence == true) candidate.amount?.let { setText(String.format(Locale.FRANCE, "%.2f", it)) }
        }

        val month = Calendar.getInstance(Locale.FRANCE).apply { set(Calendar.DAY_OF_MONTH, 1) }
        detectedPeriod?.let { month.set(Calendar.YEAR, it.year); month.set(Calendar.MONTH, it.monthZeroBased) }
        var periodConfirmed = false
        val monthButton = Button(this).apply {
            text = "Choisir la période du bulletin — requis"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
        }
        val gross = amountField("Brut du bulletin (€) — requis", parsed?.gross)
        val netBeforeTax = amountField("Net à payer avant PAS (€)", parsed?.netBeforeTax)
        val netTaxable = amountField("Net imposable / fiscal (€)", parsed?.netTaxable)
        val overtime = amountField("Heures supplémentaires — montant brut (€)", parsed?.overtimeGross)
        val premiums = amountField("Primes / majorations — montant brut (€)", parsed?.premiumsGross)
        val baskets = amountField("Paniers / indemnités repas (€)", parsed?.mealBaskets)
        val mutual = amountField("Mutuelle — part salariale (€)", parsed?.mutualEmployee)
        val provident = amountField("Prévoyance — part salariale (€)", parsed?.providentEmployee)
        val complementaryRetirement = amountField(
            "Retraite complémentaire / Agirc-Arrco — part salariale totale (€)",
            parsed?.complementaryRetirementEmployee
        )

        val info = TextView(this).apply {
            text = buildString {
                append("Lecture locale du bulletin. Vérifie les valeurs avant validation : seules les valeurs confirmées sont enregistrées.")
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
                        val form = SalaryInformationSheetView(this@V2PayslipImportActivity)
                            .bindCompany(companyId).prefillPayslipDraft(draft)
                        AlertDialog.Builder(this@V2PayslipImportActivity)
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
            val detected = detectedPeriod
            if (!periodConfirmed && detected != null) {
                AlertDialog.Builder(this).setTitle("Confirmer la période lue")
                    .setMessage(detected.sourceLine)
                    .setPositiveButton("CONFIRMER") { _, _ ->
                        periodConfirmed = true
                        monthButton.text = SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(month.time)
                    }
                    .setNeutralButton("CHOISIR UNE AUTRE PÉRIODE") { _, _ -> chooseMonth(month, monthButton) { periodConfirmed = true } }
                    .setNegativeButton("ANNULER", null).show()
            } else chooseMonth(month, monthButton) { periodConfirmed = true }
        }
        val title = if (companyName.isBlank()) "Contrôle du bulletin" else "Bulletin — $companyName"
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!periodConfirmed) {
                    Toast.makeText(this, "Choisis explicitement la période inscrite sur le bulletin", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val amountFields = listOf(gross, netBeforeTax, netTaxable, overtime, premiums, baskets, mutual, provident, complementaryRetirement)
                val invalidField = amountFields.firstOrNull { !PayslipImportConfirmationPolicyV2.isOptionalAmountValid(it.text.toString()) }
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
                val fields = linkedMapOf(
                    PayslipDocumentParserV2.KEY_GROSS to grossValue,
                    PayslipDocumentParserV2.KEY_NET_BEFORE_TAX to parseAmount(netBeforeTax.text.toString()),
                    PayslipDocumentParserV2.KEY_NET_TAXABLE to parseAmount(netTaxable.text.toString()),
                    PayslipDocumentParserV2.KEY_OVERTIME_GROSS to parseAmount(overtime.text.toString()),
                    PayslipDocumentParserV2.KEY_PREMIUMS_GROSS to parseAmount(premiums.text.toString()),
                    PayslipDocumentParserV2.KEY_MEAL_BASKETS to parseAmount(baskets.text.toString()),
                    PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to parseAmount(mutual.text.toString()),
                    PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE to parseAmount(provident.text.toString()),
                    PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE to parseAmount(complementaryRetirement.text.toString())
                )
                if (fields.values.filterNotNull().any { it < 0.0 || !it.isFinite() }) {
                    Toast.makeText(this, "Un montant du bulletin est invalide", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val confirmed = fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
                val record = V2PayslipStore.add(
                    this,
                    month.get(Calendar.YEAR),
                    month.get(Calendar.MONTH),
                    uri,
                    mime,
                    grossValue,
                    confirmed[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX],
                    true,
                    companyId
                )
                if (record == null) {
                    Toast.makeText(
                        this,
                        "Import bloqué : le stockage local des bulletins est illisible ou n'a pas pu être enregistré. Les données existantes sont conservées.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val evidence = parsed?.confirmedCandidates().orEmpty().mapNotNull { (key, candidate) ->
                    val confirmedValue = confirmed[key]
                    if (confirmedValue != null && candidate.amount == confirmedValue) candidate.sourceLabel?.let { key to it } else null
                }.toMap()
                val observedSaved = PayslipObservedValuesStoreV2.put(this, record.id, confirmed, evidence)
                Toast.makeText(
                    this,
                    if (observedSaved) "Bulletin importé • valeurs confirmées enregistrées"
                    else "Document et brut enregistrés, mais les valeurs détaillées n’ont pas pu être sauvegardées. Vérifie le bulletin avant toute comparaison.",
                    Toast.LENGTH_LONG
                ).show()
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }

    private fun chooseMonth(selected: Calendar, button: Button, onConfirmed: () -> Unit) {
        val labels = ArrayList<String>(); val months = ArrayList<Calendar>(); val format = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
        val cursor = Calendar.getInstance(Locale.FRANCE).apply { set(Calendar.DAY_OF_MONTH, 1) }
        repeat(36) { months += cursor.clone() as Calendar; labels += format.format(cursor.time).replaceFirstChar { it.uppercase() }; cursor.add(Calendar.MONTH, -1) }
        AlertDialog.Builder(this).setTitle("Période du bulletin").setItems(labels.toTypedArray()) { _, which -> selected.timeInMillis = months[which].timeInMillis; button.text = labels[which]; onConfirmed() }.setNegativeButton("Annuler", null).show()
    }

    private fun parseAmount(raw: String): Double? = PayslipImportConfirmationPolicyV2.parseAmount(raw)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
