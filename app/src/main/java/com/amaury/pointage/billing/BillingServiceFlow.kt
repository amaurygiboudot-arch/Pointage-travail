package com.amaury.pointage.billing

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.amaury.pointage.PdfPreviewActivity
import com.amaury.pointage.SalaryAccountUi
import com.amaury.pointage.v2.SalaryAccountScope
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
        val lease = SalaryAccountScope.lease(context)
        if (!SalaryAccountScope.isCurrent(lease) || lease.scope != "uid:$uid") return null
        if (!Regex("[a-f0-9]{64}").matches(hash)) return null
        val record = JSONObject(prefs(context).getString(key(uid, hash), null) ?: return null)
        val reportId = record.getString("reportId")
        val productId = record.getString("productId")
        if (!Regex("[a-f0-9]{64}").matches(reportId) || productId !in BillingContract.serviceProducts) return null
        if (!SalaryAccountScope.isCurrent(lease)) return null
        Report(productId, hash, reportId, record.getString("name"),
            File(context.filesDir, "billing_service_pending/${BillingContract.accountId(uid)}/$hash.pdf"))
    }.getOrNull()

    /** Recover classification only from an authenticated server denial, never from a filename. */
    fun recover(activity: Activity, uid: String, file: File, hash: String, reportId: String, productId: String, name: String, accountLease: SalaryAccountScope.Lease = SalaryAccountScope.lease(activity), done: (Report?) -> Unit) {
        io.execute {
            val record = runCatching {
                check(productId in BillingContract.serviceProducts && Regex("[a-f0-9]{64}").matches(reportId))
                check(BillingContract.documentId(file) == hash && active(activity, uid, accountLease))
                val snapshot = PdfPendingVault.publish(activity.filesDir, BillingContract.accountId(uid), file, hash, "billing_service_pending")
                check(BillingContract.documentId(snapshot) == hash && active(activity, uid, accountLease))
                check(prefs(activity).edit().putString(key(uid, hash), JSONObject()
                    .put("productId", productId).put("reportId", reportId).put("name", name).toString()).commit())
                Report(productId, hash, reportId, name, snapshot)
            }.getOrNull()
            main.post { if (active(activity, uid, accountLease)) done(record) }
        }
    }

    fun start(activity: Activity, productId: String) {
        val accountLease = SalaryAccountScope.lease(activity)
        if (!SalaryAccountScope.isCurrent(accountLease)) return
        val sourceContext = SalaryAccountScope.bindContext(activity, accountLease)
        if (BillingServiceCatalog.services.none { it.productId == productId && it.availability == BillingServiceCatalog.Availability.READY }) {
            message(activity, "Service en préparation : aucun paiement disponible."); return
        }
        val uid = BillingBackend.uid() ?: run { message(activity, "Connecte ton compte avant de préparer un rapport."); return }
        PaidServiceReports.prepare(activity, productId, accountLease) { draft ->
            if (!active(activity, uid, accountLease)) return@prepare
            io.execute {
                val result = runCatching {
                    check(active(activity, uid, accountLease))
                    val hash = BillingContract.documentId(draft.file)
                    val snapshot = PdfPendingVault.publish(activity.filesDir, BillingContract.accountId(uid), draft.file, hash, "billing_service_pending")
                    check(BillingContract.documentId(snapshot) == hash)
                    check(active(activity, uid, accountLease))
                    val result = Tasks.await(BillingBackend.call("billingPrepareReport", mapOf(
                        "productId" to productId, "documentSha256" to hash, "inputSha256" to draft.inputSha256,
                        "requestId" to draft.requestId)), 20, TimeUnit.SECONDS) as? Map<*, *> ?: error("Réponse serveur absente")
                    check(result["prepared"] == true && result["documentSha256"] == hash && result["productId"] == productId && active(activity, uid, accountLease))
                    val reportId = result["reportId"] as? String ?: error("Rapport non enregistré")
                    check(Regex("[a-f0-9]{64}").matches(reportId))
                    val record = JSONObject().put("productId", productId).put("reportId", reportId).put("name", draft.name)
                    check(prefs(activity).edit().putString(key(uid, hash), record.toString()).commit())
                    Report(productId, hash, reportId, draft.name, snapshot)
                }
                main.post {
                    if (!active(activity, uid, accountLease)) return@post
                    result.onSuccess { report ->
                        SalaryAccountUi.dialog(sourceContext).setTitle("Rapport prêt à débloquer")
                            .setMessage("${draft.summary}\n\nLe PDF reste privé et invisible avant vérification des droits. Le PDF du rapport est inclus.")
                            .setPositiveButton("Continuer") { _, _ -> open(activity, report, accountLease) }
                            .setNegativeButton("Plus tard", null).show()
                    }.onFailure { message(activity, "Rapport non préparé ou serveur indisponible. Aucun paiement lancé.") }
                }
            }
        }
    }

    private fun open(activity: Activity, report: Report, accountLease: SalaryAccountScope.Lease) {
        if (!SalaryAccountScope.isCurrent(accountLease)) return
        BillingPdfGate.require(activity, report.file, report.name, accountLease = accountLease) { verified ->
            if (!SalaryAccountScope.isCurrent(accountLease)) return@require
            activity.startActivity(SalaryAccountUi.attach(Intent(activity, PdfPreviewActivity::class.java).apply {
                putExtra("pdf_path", verified.absolutePath); putExtra("pdf_name", report.name)
            }, accountLease))
        }
    }

    fun showPending(activity: Activity) {
        val accountLease = SalaryAccountScope.lease(activity)
        if (!SalaryAccountScope.isCurrent(accountLease)) return
        val sourceContext = SalaryAccountScope.bindContext(activity, accountLease)
        val uid = BillingBackend.uid() ?: return
        val prefix = "${BillingContract.accountId(uid)}_"
        val reports = prefs(activity).all.keys.filter { it.startsWith(prefix) }
            .mapNotNull { prepared(sourceContext, uid, it.removePrefix(prefix)) }.filter { it.file.isFile }
        if (reports.isEmpty()) { message(activity, "Aucun rapport préparé conservé sur cet appareil."); return }
        SalaryAccountUi.dialog(sourceContext).setTitle("Rapports préparés — droits à vérifier")
            .setItems(reports.map { it.name }.toTypedArray()) { _, index ->
                if (active(activity, uid, accountLease)) open(activity, reports[index], accountLease)
            }.setNegativeButton("Fermer", null).show()
    }

    private fun active(activity: Activity, uid: String, lease: SalaryAccountScope.Lease) = !activity.isFinishing && !activity.isDestroyed && BillingBackend.uid() == uid && SalaryAccountScope.isCurrent(lease) && lease.scope == "uid:$uid"
    private fun message(activity: Activity, text: String) { if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}
