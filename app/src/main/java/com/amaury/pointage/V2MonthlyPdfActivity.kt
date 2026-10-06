package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.billing.BillingContract
import com.amaury.pointage.billing.BillingPdfGate
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.engine.MonthlyPdfReportV2
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/** A destination is requested only after server authorization of immutable PDF bytes. */
class V2MonthlyPdfActivity : Activity() {
    companion object { private const val REQUEST_CREATE = 9401 }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private var ownerUid: String? = null
    private var authorizedFile: File? = null
    private var authorizedHash: String? = null
    private var destinationRequested = false
    private var fileName = "HoraTrack.pdf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never restore an in-memory authorization or replay payment after process death/rotation.
        if (savedInstanceState != null) { finish(); return }
        ownerUid = currentUid()
        if (ownerUid == null) { toast("Connecte ton compte avant l'export PDF."); finish(); return }
        status = TextView(this).apply { text = "Choisis le mois à exporter."; textSize = 16f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(status)
            addView(Button(this@V2MonthlyPdfActivity).apply { text = "Annuler"; setOnClickListener { finish() } })
        })
        if (intent.hasExtra("report_year") || intent.hasExtra("report_month")) {
            val year = intent.getIntExtra("report_year", -1)
            val month = intent.getIntExtra("report_month", -1)
            if (!MonthlyPdfExportPolicy.validPeriod(year, month)) { toast("Période PDF invalide."); finish(); return }
            prepare(Calendar.getInstance(Locale.FRANCE).apply { clear(); set(year, month, 1) })
        } else chooseMonth()
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
            .setNegativeButton("Annuler") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }

    private fun prepare(selected: Calendar) {
        val uid = ownerUid ?: return finish()
        val year = selected.get(Calendar.YEAR); val month = selected.get(Calendar.MONTH)
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
                check(sessions.all { session ->
                    MonthlyPdfExportPolicy.stableSession(
                        session.status == SessionStatusV2.CLOSED, session.realArrivalMs, session.countedEntryMs,
                        session.realExitMs, session.countedExitMs, session.pauses.all { it.endMs != null },
                        HoraTrackV2.time.calculate(session, session.realExitMs ?: 0L).reliable
                    )
                }) { "Termine ou confirme les pointages du mois avant de générer ce PDF." }
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
                    BillingPdfGate.require(this, file, fileName, onDenied = { finish() }) { verified ->
                        if (!active(uid) || destinationRequested) return@require
                        runCatching {
                            authorizedFile = verified
                            authorizedHash = BillingContract.documentId(verified)
                            destinationRequested = true
                            status.text = "PDF autorisé. Choisis son emplacement."
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, fileName)
                            }, REQUEST_CREATE)
                        }.onFailure { toast("Impossible d'ouvrir le choix d'emplacement."); finish() }
                    }
                }.onFailure { toast(it.message?.takeIf { message -> message == "Termine ou confirme les pointages du mois avant de générer ce PDF." } ?: "Impossible de préparer le PDF. Aucun fichier extérieur créé."); finish() }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE) return
        if (resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        val uid = ownerUid; val file = authorizedFile; val hash = authorizedHash
        if (uid == null || file == null || hash == null || !destinationRequested) { cleanup(uri); finish(); return }
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
            if (result.isFailure) cleanup(uri)
            runOnUiThread {
                if (!isDestroyed) { toast(if (result.isSuccess) "PDF HoraTrack enregistré" else "Export annulé : droits, compte ou fichier à vérifier."); finish() }
            }
        }
    }

    private fun currentUid() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    private fun active(uid: String) = !isFinishing && !isDestroyed && currentUid() == uid
    private fun cleanup(uri: Uri) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

/** Final copy cannot rely on a previous account or mutable PDF contents. */
internal object MonthlyPdfExportPolicy {
    fun stableSession(closed: Boolean, arrival: Long?, countedEntry: Long?, realExit: Long?, countedExit: Long?, pausesClosed: Boolean, timeReliable: Boolean): Boolean =
        closed && arrival != null && arrival > 0L && countedEntry != null && countedEntry > 0L &&
            realExit != null && realExit >= arrival && countedExit != null && countedExit > countedEntry &&
            pausesClosed && timeReliable

    fun validPeriod(year: Int, month: Int): Boolean = year in 1900..9999 && month in 0..11
    fun allows(ownerUid: String?, currentUid: String?, expectedHash: String?, actualHash: String?): Boolean =
        !ownerUid.isNullOrBlank() && ownerUid == currentUid && expectedHash != null &&
            Regex("[a-f0-9]{64}").matches(expectedHash) && expectedHash == actualHash
}
