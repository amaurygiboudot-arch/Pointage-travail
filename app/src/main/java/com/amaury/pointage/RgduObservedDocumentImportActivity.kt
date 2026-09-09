package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import com.amaury.pointage.v2.CompanyEmployerGeneralReductionObservedAdvanceStoreV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionObservedAdvanceV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionObservedDocumentParserV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Import local assisté d'un montant RGDU depuis bulletin, DSN ou justificatif employeur. */
class RgduObservedDocumentImportActivity : Activity() {
    companion object {
        private const val REQUEST_FILE = 9711
        const val EXTRA_COMPANY_ID = "company_id"
    }

    private val companyId by lazy { intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty() }
    private val executor = Executors.newSingleThreadExecutor()
    private var status: TextView? = null
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (companyId.isBlank()) {
            finish()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/pdf",
                    "image/jpeg",
                    "image/png",
                    "image/webp",
                    "text/plain",
                    "application/xml",
                    "text/xml",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE) return
        if (resultCode != RESULT_OK) {
            finish()
            return
        }
        val uri = data?.data ?: run {
            finish()
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        inspect(uri)
    }

    private fun inspect(uri: Uri) {
        val label = TextView(this).apply {
            text = "Lecture locale du document RGDU…"
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
                val parsed = EmployerGeneralReductionObservedDocumentParserV2.parse(document.extractedText)
                val source = buildString {
                    append("Document importé : ").append(document.displayName)
                    append(" • SHA-256 ").append(document.sha256.take(16))
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showConfirmation(parsed, source)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showConfirmation(
                            EmployerGeneralReductionObservedDocumentParserV2.Result(
                                rgdu = EmployerGeneralReductionObservedDocumentParserV2.Candidate(null, 0.0),
                                warnings = listOf("Lecture automatique impossible : saisis uniquement une valeur que tu peux vérifier sur le document.")
                            ),
                            "Document RGDU importé"
                        )
                    }
                }
            } finally {
                temporary?.delete()
            }
        }
    }

    private fun showConfirmation(
        parsed: EmployerGeneralReductionObservedDocumentParserV2.Result,
        defaultSource: String
    ) {
        val month = EditText(this).apply {
            hint = "Mois — MM/AAAA"
            inputType = InputType.TYPE_CLASS_DATETIME
            isSingleLine = true
            setText(selectedPayrollMonth().format(monthFormatter))
        }
        val amount = EditText(this).apply {
            hint = "RGDU constatée (€)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            if (parsed.rgdu.highConfidence) {
                parsed.rgdu.amount?.let { setText(String.format(Locale.FRANCE, "%.2f", it)) }
            }
        }
        val source = EditText(this).apply {
            hint = "Source vérifiable"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setText(defaultSource)
        }
        val info = TextView(this).apply {
            text = buildString {
                append("HoraTrack lit le document uniquement sur le téléphone. Le montant détecté n'est jamais enregistré sans ta validation.")
                parsed.rgdu.sourceLabel?.takeIf { it.isNotBlank() }?.let {
                    append("\n\nLigne repérée : ").append(it)
                }
                if (parsed.warnings.isNotEmpty()) {
                    append("\n\n⚠ ").append(parsed.warnings.joinToString("\n⚠ "))
                }
                append("\n\nAttention : confirme uniquement la RGDU, pas le total de toutes les réductions patronales.")
            }
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(info)
            addView(month, rowParams())
            addView(amount, rowParams())
            addView(source, rowParams())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirmer la RGDU du document")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedMonth = runCatching {
                    YearMonth.parse(month.text.toString().trim(), monthFormatter)
                }.getOrNull()
                if (parsedMonth == null) {
                    month.error = "Mois invalide"
                    return@setOnClickListener
                }
                val parsedAmount = amount.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (parsedAmount == null || !parsedAmount.isFinite() || parsedAmount < 0.0) {
                    amount.error = "Montant RGDU invalide"
                    return@setOnClickListener
                }
                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique la source vérifiable"
                    return@setOnClickListener
                }

                val record = EmployerGeneralReductionObservedAdvanceV2.Record(
                    id = "rgdu_observed_${UUID.randomUUID()}",
                    month = parsedMonth,
                    amount = parsedAmount,
                    source = rawSource
                )
                if (!CompanyEmployerGeneralReductionObservedAdvanceStoreV2.save(this, companyId, record)) {
                    Toast.makeText(this, "Échec de l'enregistrement RGDU", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                Toast.makeText(this, "RGDU du document confirmée", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        dialog.show()
    }

    private fun selectedPayrollMonth(): YearMonth {
        val ms = getSharedPreferences("navigation_state", Context.MODE_PRIVATE)
            .getLong("report_month_ms", -1L)
        val calendar = Calendar.getInstance(Locale.FRANCE)
        if (ms > 0L) calendar.timeInMillis = ms
        return YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
