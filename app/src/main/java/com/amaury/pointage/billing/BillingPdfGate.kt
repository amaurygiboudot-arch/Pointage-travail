package com.amaury.pointage.billing

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Gate every preview and byte export. File hashes identify exact purchased documents. */
object BillingPdfGate {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun require(activity: Activity, file: File, displayName: String = file.name, usePlusCredit: Boolean = false, useAnalysisCredit: Boolean = false, onDenied: () -> Unit = {}, onAuthorized: (File) -> Unit) {
        HoraTrackBilling.initialize(activity)
        val uid = BillingBackend.uid()
        if (uid == null) { message(activity, "Connecte ton compte pour vérifier les droits PDF."); onDenied(); return }
        val requested = runCatching { file.canonicalFile }.getOrNull()
        val roots = listOf("billing_pdf_archive", "billing_service_pending", "billing_service_drafts", "paid_service_prepared", "monthly_pdf_pending", "billing_pdf_pending")
        if (requested == null || roots.any { root ->
                val shared = File(activity.filesDir, root).canonicalFile
                val own = File(shared, BillingContract.accountId(uid)).canonicalFile
                requested.path.startsWith(shared.path + File.separator) && !requested.path.startsWith(own.path + File.separator)
            }) {
            message(activity, "Ce PDF appartient à un autre compte."); onDenied()
            return
        }
        io.execute {
            val preserved = runCatching {
                check(BillingBackend.uid() == uid)
                val snapshot = PdfPendingVault.capture(activity.filesDir, BillingContract.accountId(uid), file, displayName)
                check(BillingBackend.uid() == uid)
                snapshot to BillingContract.documentId(snapshot)
            }.getOrNull()
            main.post {
                if (!active(activity) || BillingBackend.uid() != uid) { onDenied(); return@post }
                if (preserved == null) { message(activity, "PDF introuvable, corrompu ou impossible à conserver. Aucun achat lancé."); onDenied(); return@post }
                val file = preserved.first
                val hash = preserved.second
                val service = BillingServiceFlow.prepared(activity, uid, hash)
                val endpoint = if (service == null) "billingAuthorizePdf" else "billingAuthorizeReport"
                val payload = if (service == null) mapOf("documentSha256" to hash)
                    else mapOf("documentSha256" to hash, "reportId" to service.reportId, "usePlusCredit" to usePlusCredit, "useAnalysisCredit" to useAnalysisCredit)
                BillingBackend.call(endpoint, payload).addOnCompleteListener { task ->
                    if (!active(activity) || BillingBackend.uid() != uid) { onDenied(); return@addOnCompleteListener }
                    val response = if (task.isSuccessful) task.result as? Map<*, *> else null
                    val serviceMatches = service == null || (response != null && response["reportId"] == service.reportId && response["productId"] == service.productId)
                    if (task.isSuccessful && BillingContract.authorizedPdf(task.result, hash) && serviceMatches) {
                        // Preserve the exact purchased bytes for later downloads. Never trust this cache as an entitlement.
                        io.execute {
                            val verifiedSnapshot = runCatching {
                                check(BillingContract.documentId(file) == hash)
                                val snapshot = PdfPendingVault.publish(activity.filesDir, BillingContract.accountId(uid), file, hash, "billing_pdf_archive")
                                check(BillingContract.documentId(snapshot) == hash)
                                activity.getSharedPreferences("billing_pdf_names", Context.MODE_PRIVATE).edit()
                                    .putString("${BillingContract.accountId(uid)}_$hash", displayName.substringAfterLast('/').take(160)).commit()
                                snapshot
                            }.getOrNull()
                            main.post {
                                if (active(activity) && BillingBackend.uid() == uid) {
                                    if (verifiedSnapshot != null) onAuthorized(verifiedSnapshot) else { message(activity, "Le PDF a changé : vérifie de nouveau ses droits."); onDenied() }
                                } else onDenied()
                            }
                        }
                    } else {
                        val errorCode = (task.exception as? FirebaseFunctionsException)?.code
                        val denied = errorCode in setOf(FirebaseFunctionsException.Code.PERMISSION_DENIED, FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)
                        if (denied && service != null) {
                            if (usePlusCredit || useAnalysisCredit) message(activity, "Crédit demandé indisponible ou déjà utilisé. Aucun rapport débloqué.")
                            BillingOffers.showServicePurchase(activity, service,
                                onUsePlusCredit = { require(activity, file, displayName, usePlusCredit = true, onDenied = onDenied, onAuthorized = onAuthorized) },
                                onUseLegacyCredit = { require(activity, file, displayName, useAnalysisCredit = true, onDenied = onDenied, onAuthorized = onAuthorized) },
                                onClosed = onDenied, onVerified = { require(activity, file, displayName, onDenied = onDenied, onAuthorized = onAuthorized) })
                        } else if (denied) {
                            val details = (task.exception as? FirebaseFunctionsException)?.details as? Map<*, *>
                            val productId = details?.get("productId") as? String
                            val reportId = details?.get("reportId") as? String
                            if (productId in BillingContract.serviceProducts && reportId != null) {
                                BillingServiceFlow.recover(activity, uid, file, hash, reportId, productId!!, displayName) { recovered ->
                                    if (recovered == null) { message(activity, "Rapport indisponible : aucune offre PDF ordinaire ne peut le débloquer."); onDenied() }
                                    else BillingOffers.showServicePurchase(activity, recovered,
                                        onUsePlusCredit = { require(activity, recovered.file, displayName, usePlusCredit = true, onDenied = onDenied, onAuthorized = onAuthorized) },
                                        onUseLegacyCredit = { require(activity, recovered.file, displayName, useAnalysisCredit = true, onDenied = onDenied, onAuthorized = onAuthorized) },
                                        onClosed = onDenied, onVerified = { require(activity, recovered.file, displayName, onDenied = onDenied, onAuthorized = onAuthorized) })
                                }
                            } else BillingOffers.show(activity, hash, onClosed = onDenied) { require(activity, file, displayName, onDenied = onDenied, onAuthorized = onAuthorized) }
                        } else { message(activity, "Vérification PDF indisponible. Aucun aperçu ni export débloqué."); onDenied() }
                    }
                }
            }
        }
    }

    /** Workers only; finite timeout, no local premium/owner flag and no purchase UI. */
    fun authorizeBackgroundBlocking(context: Context, file: File): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "La vérification PDF ne doit pas bloquer l'interface." }
        val uid = BillingBackend.uid() ?: return false
        return runCatching {
            val requested = file.canonicalFile
            check(listOf("billing_pdf_archive", "billing_service_pending", "billing_service_drafts", "paid_service_prepared", "monthly_pdf_pending", "billing_pdf_pending").none { root ->
                val shared = File(context.filesDir, root).canonicalFile
                val own = File(shared, BillingContract.accountId(uid)).canonicalFile
                requested.path.startsWith(shared.path + File.separator) && !requested.path.startsWith(own.path + File.separator)
            })
            val hash = PdfPendingVault.validate(context.filesDir, BillingContract.accountId(uid), file)
            val result = Tasks.await(BillingBackend.call("billingAuthorizePdf", mapOf("documentSha256" to hash)), 20, TimeUnit.SECONDS)
            BillingBackend.uid() == uid && BillingContract.authorizedPdf(result, hash) && BillingContract.documentId(file) == hash
        }.getOrDefault(false)
    }

    fun authorizeBackground(context: Context, file: File): Boolean = authorizeBackgroundBlocking(context, file)
    private fun active(activity: Activity) = !activity.isDestroyed && !activity.isFinishing
    private fun message(activity: Activity, message: String) { if (active(activity)) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
