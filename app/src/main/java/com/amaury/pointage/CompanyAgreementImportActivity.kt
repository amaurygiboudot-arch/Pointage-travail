package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyAgreementDocumentReaderV2
import com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2
import com.amaury.pointage.v2.CompanyAgreementImportCommitV2
import com.amaury.pointage.v2.CompanyAgreementImportPolicyV2
import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.CompanyAgreementStoreV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.Executors

/** Imports and analyzes a user-provided company agreement without sending its contents to a server. */
class CompanyAgreementImportActivity : Activity() {
    companion object {
        private const val REQUEST_DOCUMENT = 9701
        const val EXTRA_COMPANY_ID = "company_id"
        const val EXTRA_COMPANY_NAME = "company_name"
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
    }

    private val companyId by lazy { intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty() }
    private val companyName by lazy { intent.getStringExtra(EXTRA_COMPANY_NAME).orEmpty() }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    @Volatile private var pendingResult: CompanyAgreementDocumentReaderV2.Result? = null
    private var pickerStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(progressView())
        if (companyId.isBlank() || SalaryCompanyStore.list(this).none { it.id == companyId }) {
            Toast.makeText(this, "Entreprise introuvable : import annulé.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pickerStarted = savedInstanceState?.getBoolean("picker_started") ?: false
        if (!pickerStarted) launchPicker()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker_started", pickerStarted)
        super.onSaveInstanceState(outState)
    }

    private fun progressView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val pad = dp(24)
        setPadding(pad, pad, pad, pad)
        addView(ProgressBar(this@CompanyAgreementImportActivity).apply { isIndeterminate = true })
        status = TextView(this@CompanyAgreementImportActivity).apply {
            text = "Choisis le document de l’accord."
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
        }
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(Button(this@CompanyAgreementImportActivity).apply {
            text = "ANNULER"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun launchPicker() {
        pickerStarted = true
        status.text = "Choisis un PDF, une image, un fichier texte, HTML, XML ou DOCX."
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/pdf",
                    "image/jpeg",
                    "image/png",
                    "image/webp",
                    "text/plain",
                    "text/html",
                    "application/xhtml+xml",
                    "application/xml",
                    "text/xml",
                    "application/json",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }, REQUEST_DOCUMENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DOCUMENT) return
        pickerStarted = false
        if (resultCode != RESULT_OK) {
            finish()
            return
        }
        val uri = data?.data ?: run {
            showFailure("Aucun document n’a été reçu.")
            return
        }
        val grantedFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, grantedFlags) }
        readSelectedDocument(uri)
    }

    private fun readSelectedDocument(uri: Uri) {
        status.text = "Lecture locale du document…"
        executor.execute {
            try {
                val result = CompanyAgreementDocumentReaderV2.read(this, uri) { message -> updateStatus(message) }
                pendingResult = result
                if (CompanyAgreementImportPolicyV2.isMeaningfulText(result.extractedText)) {
                    prepareReview(result, result.extractedText)
                } else {
                    runOnUiThread { if (!isFinishing && !isDestroyed) askForReadableText(result) }
                }
            } catch (error: CompanyAgreementDocumentReaderV2.ImportException) {
                runOnUiThread { showFailure(error.message ?: "Document illisible.") }
            } catch (_: InterruptedException) {
                // Activity is closing.
            } catch (_: Throwable) {
                runOnUiThread { showFailure("Le document n’a pas pu être lu localement.") }
            }
        }
    }

    private fun askForReadableText(result: CompanyAgreementDocumentReaderV2.Result) {
        val text = EditText(this).apply {
            hint = "Colle ici le texte lisible de l’accord"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 10
            maxLines = 18
            setText(result.extractedText.take(50_000))
            setSelection(length())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Texte à confirmer")
            .setMessage("La lecture automatique n’a pas trouvé assez de texte. Le document original restera conservé localement ; colle son texte pour lancer l’analyse, sans envoi sur Internet.")
            .setView(text)
            .setPositiveButton("ANALYSER", null)
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = CompanyAgreementImportPolicyV2.normalizeExtractedText(text.text.toString())
                if (!CompanyAgreementImportPolicyV2.isMeaningfulText(normalized)) {
                    text.error = "Colle au moins quelques phrases lisibles de l’accord."
                    return@setOnClickListener
                }
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                prepareReview(result, normalized)
            }
        }
        dialog.show()
    }

    private fun prepareReview(result: CompanyAgreementDocumentReaderV2.Result, extractedText: String) {
        updateStatus("Détection locale des règles candidates…")
        executor.execute {
            val candidates = CompanyAgreementRuleExtractorV2.extract(extractedText)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showReview(result, extractedText, candidates)
            }
        }
    }

    private fun showReview(
        result: CompanyAgreementDocumentReaderV2.Result,
        extractedText: String,
        candidates: List<CompanyAgreementRuleExtractorV2.Candidate>
    ) {
        val agreementId = CompanyAgreementImportPolicyV2.stableAgreementId(result.sha256)
        val previous = CompanyAgreementStoreV2.list(this, companyId).firstOrNull { it.id == agreementId }
        val title = field("Titre de l’accord", previous?.title ?: CompanyAgreementImportPolicyV2.titleFrom(result.displayName))
        val effectiveFrom = field("Début d’application — JJ/MM/AAAA (facultatif)", previous?.effectiveFrom.orEmpty())
        val effectiveTo = field("Fin d’application — JJ/MM/AAAA (facultatif)", previous?.effectiveTo.orEmpty())
        val details = buildString {
            append("Document : ${result.displayName}\n")
            result.pageCount?.let { append("Pages analysées : $it\n") }
            append("Règles candidates détectées : ${candidates.size}\n\n")
            append("Aucune règle ne sera appliquée automatiquement. Chaque règle, valeur et période devra être validée dans HoraTrack.")
            if (candidates.isEmpty()) append("\n\nAucun passage exploitable n’a été détecté : conserve l’accord puis vérifie-le manuellement.")
            if (result.truncated) append("\n\nAttention : le texte analysé a atteint la limite de sécurité ; vérifie le document original.")
        }
        val preview = TextView(this).apply {
            text = "APERÇU DU TEXTE LU\n\n${extractedText.take(1_400)}${if (extractedText.length > 1_400) "…" else ""}"
            textSize = 13f
            setPadding(0, dp(14), 0, dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(TextView(this@CompanyAgreementImportActivity).apply { text = details; textSize = 15f })
            addView(title, row())
            addView(effectiveFrom, row())
            addView(effectiveTo, row())
            addView(preview)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (companyName.isBlank()) "Importer l’accord" else "Accord — $companyName")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("CONSERVER ET ANALYSER", null)
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val cleanTitle = title.text.toString().replace(Regex("[\\r\\n\\u0000]+"), " ").trim().take(160)
                if (cleanTitle.length < 3) {
                    title.error = "Titre requis"
                    return@setOnClickListener
                }
                val fromDate = parseDate(effectiveFrom)
                if (effectiveFrom.text.isNotBlank() && fromDate == null) return@setOnClickListener
                val toDate = parseDate(effectiveTo)
                if (effectiveTo.text.isNotBlank() && toDate == null) return@setOnClickListener
                if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
                    effectiveTo.error = "La fin doit être postérieure au début."
                    return@setOnClickListener
                }
                save.isEnabled = false
                status.text = "Conservation locale de l’accord…"
                persistImport(
                    dialog = dialog,
                    result = result,
                    title = cleanTitle,
                    effectiveFrom = effectiveFrom.text.toString().trim().takeIf { it.isNotBlank() },
                    effectiveTo = effectiveTo.text.toString().trim().takeIf { it.isNotBlank() },
                    candidates = candidates
                )
            }
        }
        dialog.show()
    }

    private fun persistImport(
        dialog: AlertDialog,
        result: CompanyAgreementDocumentReaderV2.Result,
        title: String,
        effectiveFrom: String?,
        effectiveTo: String?,
        candidates: List<CompanyAgreementRuleExtractorV2.Candidate>
    ) {
        executor.execute {
            var persisted: CompanyAgreementDocumentStoreV2.Persisted? = null
            try {
                val stored = CompanyAgreementDocumentStoreV2.persist(this, companyId, result)
                persisted = stored
                val agreement = CompanyAgreementStoreV2.Agreement(
                    id = CompanyAgreementImportPolicyV2.stableAgreementId(result.sha256),
                    title = title,
                    effectiveFrom = effectiveFrom,
                    effectiveTo = effectiveTo,
                    sourceLabel = "Import local — ${result.displayName.take(120)}",
                    status = CompanyAgreementStoreV2.Status.IMPORTED,
                    notes = buildString {
                        append("Analyse locale : ${candidates.size} règle(s) candidate(s), validation manuelle obligatoire.")
                        if (result.truncated) append(" Texte partiellement analysé : vérifier le document original.")
                    },
                    documentName = result.displayName,
                    documentMimeType = result.mimeType,
                    documentSha256 = result.sha256,
                    documentPath = stored.relativePath,
                    importedAtEpochMs = System.currentTimeMillis()
                )
                val commit = CompanyAgreementImportCommitV2.commit(this, companyId, agreement, candidates)
                if (!commit.saved) {
                    CompanyAgreementDocumentStoreV2.rollback(this, stored)
                    throw CompanyAgreementDocumentReaderV2.ImportException("Impossible d’enregistrer l’accord sans risque pour les données existantes.")
                }
                pendingResult = null
                runOnUiThread {
                    dialog.setOnCancelListener(null)
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        if (commit.duplicate) "Accord déjà connu : document contrôlé, validations conservées."
                        else "Accord importé : ${commit.candidateCount} règle(s) à vérifier.",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (error: CompanyAgreementDocumentReaderV2.ImportException) {
                runOnUiThread {
                    dialog.setOnCancelListener(null)
                    dialog.dismiss()
                    showFailure(error.message ?: "Impossible d’enregistrer l’accord.")
                }
            } catch (_: Throwable) {
                persisted?.let { CompanyAgreementDocumentStoreV2.rollback(this, it) }
                runOnUiThread {
                    dialog.setOnCancelListener(null)
                    dialog.dismiss()
                    showFailure("Impossible d’enregistrer l’accord localement.")
                }
            }
        }
    }

    private fun parseDate(field: EditText): LocalDate? {
        val value = field.text.toString().trim()
        if (value.isBlank()) return null
        val parsed = runCatching { LocalDate.parse(value, DATE_FORMAT) }.getOrNull()
        if (parsed == null) field.error = "Date attendue : JJ/MM/AAAA"
        return parsed
    }

    private fun showFailure(message: String) {
        if (isFinishing || isDestroyed) return
        SalaryCompanyStore.prefs(this, companyId).edit()
            .putLong("company_agreement_import_failed_at", System.currentTimeMillis())
            .commit()
        status.text = message
        AlertDialog.Builder(this)
            .setTitle("Import impossible")
            .setMessage(message)
            .setPositiveButton("CHOISIR UN AUTRE DOCUMENT") { _, _ -> launchPicker() }
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) status.text = message
        }
    }

    private fun field(hintValue: String, value: String) = EditText(this).apply {
        hint = hintValue
        setText(value)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun row() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        CompanyAgreementDocumentStoreV2.discard(pendingResult)
        pendingResult = null
        super.onDestroy()
    }

}
