package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.billing.BillingContract
import com.amaury.pointage.billing.BillingPdfGate
import com.amaury.pointage.billing.HoraTrackBilling
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.engine.MonthlyPdfReportV2
import com.amaury.pointage.v2.engine.ConfirmedWorkPdfPolicyV2
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/** A destination is requested only after server authorization of immutable PDF bytes. */
class V2MonthlyPdfActivity : Activity() {
    companion object {
        private const val REQUEST_CREATE = 9401
        // Serialize restarted copies with the departing instance: never two writers for one URI.
        private val worker = Executors.newSingleThreadExecutor()
    }
    private lateinit var status: TextView
    private var ownerUid: String? = null
    private var authorizedFile: File? = null
    private var authorizedHash: String? = null
    private var destinationRequested = false
    private var fileName = "HoraTrack.pdf"
    private var previewMode = false
    private var phase = MonthlyPdfRecovery.Phase.CHOOSE
    private var selectedYear = -1
    private var selectedMonth = -1
    private var destinationUri: Uri? = null
    private var restoredVerification = false
    private var checkingRecovery = false
    private var stateSaved = false
    private lateinit var resumeButton: Button
    private lateinit var offersButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewMode = savedInstanceState?.getBoolean("preview_mode") ?: intent.getBooleanExtra("report_preview", false)
        ownerUid = savedInstanceState?.getString("owner_uid") ?: currentUid()
        if (ownerUid == null || ownerUid != currentUid()) { toast("Connecte ton compte avant l'export PDF."); finish(); return }
        status = TextView(this).apply { text = "Choisis le mois à exporter."; textSize = 16f }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            resumeButton = actionButton().apply {
                text = "Reprendre la vérification"; visibility = android.view.View.GONE
                setOnClickListener {
                    val uid = ownerUid ?: return@setOnClickListener
                    HoraTrackBilling.initialize(this@V2MonthlyPdfActivity)
                    HoraTrackBilling.restore(this@V2MonthlyPdfActivity, quiet = true) {
                        if (active(uid)) checkRestoredAuthorization()
                    }
                }
            }
            addView(resumeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            offersButton = actionButton().apply {
                text = "Voir les options de déblocage"; visibility = android.view.View.GONE
                setOnClickListener { authorizedFile?.let { requestAuthorization(it) } }
            }
            addView(offersButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(actionButton().apply { text = "Annuler"; setOnClickListener { finish() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (savedInstanceState != null) {
            restoreExport(savedInstanceState)
            return
        }
        if (intent.hasExtra("report_year") || intent.hasExtra("report_month")) {
            val year = intent.getIntExtra("report_year", -1)
            val month = intent.getIntExtra("report_month", -1)
            if (!MonthlyPdfExportPolicy.validPeriod(year, month)) { toast("Période PDF invalide."); finish(); return }
            prepare(Calendar.getInstance(Locale.FRANCE).apply { clear(); set(year, month, 1) })
        } else chooseMonth()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun actionButton() = Button(this).apply {
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        setSingleLine(false)
    }

    private fun chooseMonth() {
        val cursor = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val months = List(36) { (cursor.clone() as Calendar).also { cursor.add(Calendar.MONTH, -1) } }
        val format = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
        AlertDialog.Builder(this).setTitle("Mois du rapport")
            .setItems(months.map { format.format(it.time).replaceFirstChar(Char::uppercase) }.toTypedArray()) { _, index -> prepare(months[index]) }
            .setNegativeButton("Annuler") { _, _ -> if (!isChangingConfigurations && !isDestroyed) finish() }
            .setOnCancelListener { if (!isChangingConfigurations && !isDestroyed) finish() }.show()
    }

    private fun prepare(selected: Calendar) {
        val uid = ownerUid ?: return finish()
        val year = selected.get(Calendar.YEAR); val month = selected.get(Calendar.MONTH)
        selectedYear = year; selectedMonth = month; phase = MonthlyPdfRecovery.Phase.PREPARING
        fileName = "HoraTrack_${SimpleDateFormat("yyyy_MM", Locale.FRANCE).format(selected.time)}.pdf"
        status.text = "Préparation privée et vérification des droits PDF…"
        worker.execute {
            val prepared = runCatching {
                check(currentUid() == uid && !isFinishing && !isDestroyed)
                val sessions = V2RuntimeReader.allSessions(this).requireReliable().filter { session ->
                    session.realArrivalMs?.let { at -> Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = at }.let {
                        it.get(Calendar.YEAR) == year && it.get(Calendar.MONTH) == month
                    } } == true
                }
                ConfirmedWorkPdfPolicyV2.requireStable(sessions)
                // Reuse unchanged private bytes, including pending payments after restart.
                val input = "monthly-v1|$year|$month|${java.util.TimeZone.getDefault().id}|${sessions.sortedBy { it.id }}"
                val folder = File(filesDir, "monthly_pdf_pending/${BillingContract.accountId(uid)}").apply { check(mkdirs() || isDirectory) }
                val file = File(folder, "${BillingContract.accountId(input)}.pdf")
                if (!file.exists()) {
                    val temp = File.createTempFile("monthly_", ".pdf", folder)
                    try { temp.outputStream().use { MonthlyPdfReportV2.write(sessions, year, month, it) }; check(temp.renameTo(file)) }
                    finally { temp.delete() }
                }
                check(file.isFile && file.length() > 0)
                file
            }
            runOnUiThread {
                if (!active(uid)) return@runOnUiThread
                prepared.onSuccess { file ->
                    authorizedFile = file
                    authorizedHash = BillingContract.documentId(file)
                    requestAuthorization(file)
                }.onFailure { toast(it.message?.takeIf { message -> message == ConfirmedWorkPdfPolicyV2.WARNING } ?: "Impossible de préparer le PDF. Aucun fichier extérieur créé."); finish() }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE) return
        if (resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        if (uri.scheme != "content") { toast("Emplacement PDF invalide."); finish(); return }
        val uid = ownerUid; val file = authorizedFile; val hash = authorizedHash
        if (uid == null || file == null || hash == null || !destinationRequested) { cleanup(uri); finish(); return }
        destinationUri = uri
        runCatching { contentResolver.takePersistableUriPermission(uri,
            data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) }
        copyAuthorized(uid, file, hash, uri)
    }

    private fun copyAuthorized(uid: String, file: File, hash: String, uri: Uri) {
        phase = MonthlyPdfRecovery.Phase.COPYING
        status.text = "Vérification finale et enregistrement du PDF…"
        worker.execute {
            val result = runCatching {
                check(active(uid))
                check(MonthlyPdfExportPolicy.allows(uid, currentUid(), hash, BillingContract.documentId(file)))
                // Read-only revalidation: no second purchase after destination creation.
                check(BillingPdfGate.authorizeBackgroundBlocking(this, file))
                check(active(uid) && MonthlyPdfExportPolicy.allows(uid, currentUid(), hash, BillingContract.documentId(file)))
                contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; check(active(uid)); output.write(buffer, 0, count) }
                } } ?: error("Destination inaccessible")
            }
            if (result.isFailure && !isChangingConfigurations) cleanup(uri)
            runOnUiThread {
                if (!isDestroyed && !isChangingConfigurations) { toast(if (result.isSuccess) "PDF HoraTrack enregistré" else "Export annulé : droits, compte ou fichier à vérifier."); finish() }
            }
        }
    }

    private fun currentUid() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    private fun active(uid: String) = !isFinishing && !isDestroyed && !isChangingConfigurations && currentUid() == uid
    private fun cleanup(uri: Uri) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        stateSaved = true
        outState.putBoolean("preview_mode", previewMode)
        outState.putString("owner_uid", ownerUid)
        outState.putString("phase", phase.name)
        outState.putInt("year", selectedYear); outState.putInt("month", selectedMonth)
        outState.putString("file", authorizedFile?.absolutePath)
        outState.putString("hash", authorizedHash); outState.putString("name", fileName)
        outState.putString("destination", destinationUri?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun restoreExport(state: Bundle) {
        selectedYear = state.getInt("year", -1); selectedMonth = state.getInt("month", -1)
        phase = runCatching { MonthlyPdfRecovery.Phase.valueOf(state.getString("phase") ?: "CHOOSE") }.getOrDefault(MonthlyPdfRecovery.Phase.CHOOSE)
        fileName = state.getString("name") ?: "HoraTrack.pdf"
        val uid = ownerUid ?: return finish()
        val file = state.getString("file")?.let(::File)
        authorizedHash = state.getString("hash")
        val ownedPath = file != null && runCatching {
            val path = file.canonicalPath
            listOf("monthly_pdf_pending", "billing_pdf_archive").any { root ->
                path.startsWith(File(filesDir, "$root/${BillingContract.accountId(uid)}").canonicalPath + File.separator)
            }
        }.getOrDefault(false)
        when (MonthlyPdfRecovery.action(phase, uid, currentUid(), ownedPath, authorizedHash)) {
            MonthlyPdfRecovery.Action.CHOOSE -> chooseMonth()
            MonthlyPdfRecovery.Action.PREPARE -> {
                if (!MonthlyPdfExportPolicy.validPeriod(selectedYear, selectedMonth)) { finish(); return }
                prepare(Calendar.getInstance(Locale.FRANCE).apply { clear(); set(selectedYear, selectedMonth, 1) })
            }
            MonthlyPdfRecovery.Action.REVERIFY -> {
                authorizedFile = file
                restoredVerification = true
                status.text = "Vérification du paiement et du PDF préparé…"
                resumeButton.visibility = android.view.View.VISIBLE
                offersButton.visibility = android.view.View.VISIBLE
                checkRestoredAuthorization()
            }
            MonthlyPdfRecovery.Action.WAIT_PICKER -> {
                authorizedFile = file; destinationRequested = true
                status.text = "Choix d'emplacement en cours…"
            }
            MonthlyPdfRecovery.Action.COPY -> {
                authorizedFile = file; destinationRequested = true
                val uri = state.getString("destination")?.let(Uri::parse)
                if (uri == null || uri.scheme != "content") { finish(); return }
                destinationUri = uri
                copyAuthorized(uid, file!!, authorizedHash!!, uri)
            }
            MonthlyPdfRecovery.Action.REJECT -> { toast("Reprise impossible : compte ou PDF à vérifier."); finish() }
        }
    }

    private fun requestAuthorization(file: File) {
        phase = MonthlyPdfRecovery.Phase.AUTHORIZING
        restoredVerification = false
        resumeButton.visibility = android.view.View.GONE
        offersButton.visibility = android.view.View.GONE
        BillingPdfGate.require(this, file, fileName, onDenied = { if (!isChangingConfigurations && !isDestroyed) finish() }) { verified ->
            val uid = ownerUid ?: return@require
            if (!active(uid) || destinationRequested || isChangingConfigurations) return@require
            authorizedFile = verified
            authorizedHash = BillingContract.documentId(verified)
            launchDestination()
        }
    }

    private fun launchDestination() {
        if (destinationRequested) return
        if (stateSaved) { restoredVerification = true; return }
        if (previewMode) {
            val uid = ownerUid ?: return
            val file = authorizedFile ?: return
            if (!active(uid)) return
            runCatching {
                destinationRequested = true
                startActivity(Intent(this, PdfPreviewActivity::class.java).apply {
                    putExtra("pdf_path", file.absolutePath)
                    putExtra("pdf_name", fileName)
                })
                finish()
            }.onFailure { toast("Impossible d'ouvrir l'aperçu PDF."); finish() }
            return
        }
        runCatching {
            phase = MonthlyPdfRecovery.Phase.WAIT_PICKER
            destinationRequested = true
            status.text = "PDF autorisé. Choisis son emplacement."
            resumeButton.visibility = android.view.View.GONE
            offersButton.visibility = android.view.View.GONE
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, fileName)
            }, REQUEST_CREATE)
        }.onFailure { toast("Impossible d'ouvrir le choix d'emplacement."); finish() }
    }

    override fun onResume() {
        super.onResume()
        stateSaved = false
        if (restoredVerification) checkRestoredAuthorization()
    }

    private fun checkRestoredAuthorization() {
        if (checkingRecovery || !restoredVerification) return
        val uid = ownerUid ?: return
        val file = authorizedFile ?: return
        val hash = authorizedHash ?: return
        checkingRecovery = true
        worker.execute {
            val verified = runCatching {
                MonthlyPdfExportPolicy.allows(uid, currentUid(), hash, BillingContract.documentId(file)) &&
                    BillingPdfGate.authorizeBackgroundBlocking(this, file)
            }.getOrDefault(false)
            runOnUiThread {
                checkingRecovery = false
                if (!active(uid) || isChangingConfigurations) return@runOnUiThread
                if (verified) { restoredVerification = false; launchDestination() }
                else status.text = "Aucun export débloqué. Si le paiement est en cours, attends sa confirmation puis reprends la vérification."
            }
        }
    }
}

/** Final copy cannot rely on a previous account or mutable PDF contents. */
internal object MonthlyPdfExportPolicy {
    fun validPeriod(year: Int, month: Int): Boolean = year in 1900..9999 && month in 0..11
    fun allows(ownerUid: String?, currentUid: String?, expectedHash: String?, actualHash: String?): Boolean =
        !ownerUid.isNullOrBlank() && ownerUid == currentUid && expectedHash != null &&
            Regex("[a-f0-9]{64}").matches(expectedHash) && expectedHash == actualHash
}
