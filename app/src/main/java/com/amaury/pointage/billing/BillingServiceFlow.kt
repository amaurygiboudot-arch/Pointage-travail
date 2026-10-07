package com.amaury.pointage.billing

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.amaury.pointage.PdfPreviewActivity
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Private immutable drafts and server job identities survive Play pending/process death. */
object BillingServiceFlow {
    data class Report(val productId: String, val documentId: String, val reportId: String, val name: String, val file: File)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private fun key(uid: String, hash: String) = "${BillingContract.accountId(uid)}_$hash"
    private fun prefs(context: Context) = context.getSharedPreferences("billing_service_reports", Context.MODE_PRIVATE)

    fun prepared(context: Context, uid: String, hash: String): Report? = runCatching {
        if (!Regex("[a-f0-9]{64}").matches(hash)) return null
        val record = JSONObject(prefs(context).getString(key(uid, hash), null) ?: return null)
        val reportId = record.getString("reportId")
        val productId = record.getString("productId")
        if (!Regex("[a-f0-9]{64}").matches(reportId) || productId !in BillingContract.serviceProducts) return null
        Report(productId, hash, reportId, record.getString("name"),
            File(context.filesDir, "billing_service_pending/${BillingContract.accountId(uid)}/$hash.pdf"))
    }.getOrNull()

    /** Recover classification only from an authenticated server denial, never from a filename. */
    fun recover(activity: Activity, uid: String, file: File, hash: String, reportId: String, productId: String, name: String, done: (Report?) -> Unit) {
        io.execute {
            val record = runCatching {
                check(productId in BillingContract.serviceProducts && Regex("[a-f0-9]{64}").matches(reportId))
                check(BillingContract.documentId(file) == hash && BillingBackend.uid() == uid)
                val snapshot = PdfPendingVault.publish(activity.filesDir, BillingContract.accountId(uid), file, hash, "billing_service_pending")
                check(BillingContract.documentId(snapshot) == hash && BillingBackend.uid() == uid)
                check(prefs(activity).edit().putString(key(uid, hash), JSONObject()
                    .put("productId", productId).put("reportId", reportId).put("name", name).toString()).commit())
                Report(productId, hash, reportId, name, snapshot)
            }.getOrNull()
            main.post { if (active(activity, uid)) done(record) }
        }
    }

    fun start(activity: Activity, productId: String) {
        if (BillingServiceCatalog.services.none { it.productId == productId && it.availability == BillingServiceCatalog.Availability.READY }) {
            message(activity, "Service en préparation : aucun paiement disponible."); return
        }
        val uid = BillingBackend.uid() ?: run { message(activity, "Connecte ton compte avant de préparer un rapport."); return }
        PaidServiceReports.prepare(activity, productId) { draft ->
            if (!active(activity, uid)) return@prepare
            io.execute {
                val result = runCatching {
                    check(active(activity, uid))
                    val hash = BillingContract.documentId(draft.file)
                    val snapshot = PdfPendingVault.publish(activity.filesDir, BillingContract.accountId(uid), draft.file, hash, "billing_service_pending")
                    check(BillingContract.documentId(snapshot) == hash)
                    check(BillingBackend.uid() == uid)
                    val result = Tasks.await(BillingBackend.call("billingPrepareReport", mapOf(
                        "productId" to productId, "documentSha256" to hash, "inputSha256" to draft.inputSha256,
                        "requestId" to draft.requestId)), 20, TimeUnit.SECONDS) as? Map<*, *> ?: error("Réponse serveur absente")
                    check(result["prepared"] == true && result["documentSha256"] == hash && result["productId"] == productId && BillingBackend.uid() == uid)
                    val reportId = result["reportId"] as? String ?: error("Rapport non enregistré")
                    check(Regex("[a-f0-9]{64}").matches(reportId))
                    val record = JSONObject().put("productId", productId).put("reportId", reportId).put("name", draft.name)
                    check(prefs(activity).edit().putString(key(uid, hash), record.toString()).commit())
                    Report(productId, hash, reportId, draft.name, snapshot)
                }
                main.post {
                    if (!active(activity, uid)) return@post
                    result.onSuccess { report ->
                        AlertDialog.Builder(activity).setTitle("Rapport prêt à débloquer")
                            .setMessage("${draft.summary}\n\nLe PDF reste privé et invisible avant vérification des droits. Le PDF du rapport est inclus.")
                            .setPositiveButton("Continuer") { _, _ -> open(activity, report) }
                            .setNegativeButton("Plus tard", null).show()
                    }.onFailure { message(activity, "Rapport non préparé ou serveur indisponible. Aucun paiement lancé.") }
                }
            }
        }
    }

    private fun open(activity: Activity, report: Report) {
        BillingPdfGate.require(activity, report.file, report.name) { verified ->
            activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                putExtra("pdf_path", verified.absolutePath); putExtra("pdf_name", report.name)
            })
        }
    }

    fun showPending(activity: Activity) {
        val uid = BillingBackend.uid() ?: return
        val prefix = "${BillingContract.accountId(uid)}_"
        val reports = prefs(activity).all.keys.filter { it.startsWith(prefix) }
            .mapNotNull { prepared(activity, uid, it.removePrefix(prefix)) }.filter { it.file.isFile }
        if (reports.isEmpty()) { message(activity, "Aucun rapport préparé conservé sur cet appareil."); return }
        AlertDialog.Builder(activity).setTitle("Rapports préparés — droits à vérifier")
            .setItems(reports.map { it.name }.toTypedArray()) { _, index ->
                if (active(activity, uid)) open(activity, reports[index])
            }.setNegativeButton("Fermer", null).show()
    }

    private fun active(activity: Activity, uid: String) = !activity.isFinishing && !activity.isDestroyed && BillingBackend.uid() == uid
    private fun message(activity: Activity, text: String) { if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}
