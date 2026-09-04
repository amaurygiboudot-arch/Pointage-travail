package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyAgreementDocumentReaderV2
import com.amaury.pointage.v2.ProvidentRelaySourceStoreV2
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.ProvidentRelayDocumentParserV2
import java.util.Locale
import java.util.concurrent.Executors

/** Import local d'un décompte de prévoyance rattaché à un arrêt maladie V2. */
class ProvidentRelayImportActivity : Activity() {
    companion object {
        private const val REQUEST_DOCUMENT = 9821
        const val EXTRA_COMPANY_ID = "company_id"
        const val EXTRA_ABSENCE_ID = "absence_id"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private val companyId by lazy { intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty() }
    private val absenceId by lazy { intent.getStringExtra(EXTRA_ABSENCE_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val absence = V2RightsStore.absencesForCompany(this, companyId).firstOrNull { it.id == absenceId }
        if (companyId.isBlank() || absence == null || absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) {
            Toast.makeText(this, "Arrêt maladie introuvable", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        status = TextView(this).apply {
            text = "Choisis le décompte prévoyance à contrôler."
            textSize = 16f
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        setContentView(status)
        if (savedInstanceState == null) chooseDocument()
    }

    private fun chooseDocument() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp"))
        }, REQUEST_DOCUMENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DOCUMENT) return
        if (resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        analyze(uri)
    }

    private fun analyze(uri: Uri) {
        status.text = "Lecture locale du décompte…"
        executor.execute {
            var temporary: java.io.File? = null
            try {
                val document = CompanyAgreementDocumentReaderV2.read(this, uri) { message ->
                    runOnUiThread { status.text = message }
                }
                temporary = document.temporaryFile
                val parsed = ProvidentRelayDocumentParserV2.parse(document.extractedText)
                runOnUiThread { showConfirmation(uri, document.mimeType, document.displayName, parsed) }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, error.message ?: "Impossible de lire le décompte", Toast.LENGTH_LONG).show()
                    finish()
                }
            } finally {
                temporary?.delete()
            }
        }
    }

    private fun showConfirmation(
        uri: Uri,
        mime: String?,
        displayName: String,
        parsed: ProvidentRelayDocumentParserV2.Result
    ) {
        fun amountField(hintText: String, candidate: ProvidentRelayDocumentParserV2.Candidate) = EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            if (candidate.highConfidence) candidate.amount?.let { setText(String.format(Locale.FRANCE, "%.2f", it)) }
        }
        val target = amountField("60 % du salaire brut de référence", parsed.targetGross60)
        val ss = amountField("Prestations SS brutes déduites", parsed.socialSecurityGross)
        val observed = amountField("Prévoyance brute réellement versée", parsed.observedProvidentGross)
        val info = TextView(this).apply {
            text = buildString {
                append("Document : ").append(displayName).append('\n')
                append("Lecture effectuée localement. Vérifie les 3 montants avant d'enregistrer.")
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
            addView(ss, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
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
                val values = listOf(target, ss, observed).map { parseAmount(it.text.toString()) }
                if (values.any { it == null || it < 0.0 }) {
                    Toast.makeText(this, "Vérifie les trois montants bruts avant d'enregistrer", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val current = V2RightsStore.absencesForCompany(this, companyId).firstOrNull { it.id == absenceId }
                if (current == null) {
                    Toast.makeText(this, "L'arrêt n'existe plus", Toast.LENGTH_LONG).show()
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
                    absenceId,
                    ProvidentRelaySourceStoreV2.Source(uri.toString(), mime, displayName)
                )
                Toast.makeText(this, "Décompte prévoyance contrôlé", Toast.LENGTH_LONG).show()
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }

    private fun parseAmount(raw: String): Double? = raw.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
