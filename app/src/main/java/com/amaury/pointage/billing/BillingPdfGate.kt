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

    fun require(activity: Activity, file: File, displayName: String = file.name, onAuthorized: (File) -> Unit) {
        HoraTrackBilling.initialize(activity)
        val uid = BillingBackend.uid()
        if (uid == null) { message(activity, "Connecte ton compte pour vérifier les droits PDF."); return }
        val archiveRoot = File(activity.filesDir, "billing_pdf_archive").canonicalFile
        val requested = runCatching { file.canonicalFile }.getOrNull()
        val accountRoot = File(archiveRoot, BillingContract.accountId(uid)).canonicalFile
        if (requested == null || (requested.path.startsWith(archiveRoot.path + File.separator) &&
                !requested.path.startsWith(accountRoot.path + File.separator))) {
            message(activity, "Ce PDF appartient à un autre compte.")
            return
        }
        io.execute {
            val hash = runCatching { BillingContract.documentId(file) }.getOrNull()
            main.post {
                if (!active(activity) || BillingBackend.uid() != uid) return@post
                if (hash == null) { message(activity, "PDF introuvable ou vide."); return@post }
                BillingBackend.call("billingAuthorizePdf", mapOf("documentSha256" to hash)).addOnCompleteListener { task ->
                    if (!active(activity) || BillingBackend.uid() != uid) return@addOnCompleteListener
                    if (task.isSuccessful && BillingContract.authorizedPdf(task.result, hash)) {
                        // Preserve the exact purchased bytes for later downloads. Never trust this cache as an entitlement.
                        io.execute {
                            val verifiedSnapshot = runCatching {
                                check(BillingContract.documentId(file) == hash)
                                val folder = File(activity.filesDir, "billing_pdf_archive/${BillingContract.accountId(uid)}").apply { mkdirs() }
                                val snapshot = File(folder, "$hash.pdf")
                                if (!snapshot.exists()) file.copyTo(snapshot)
                                check(BillingContract.documentId(snapshot) == hash)
                                activity.getSharedPreferences("billing_pdf_names", Context.MODE_PRIVATE).edit()
                                    .putString("${BillingContract.accountId(uid)}_$hash", displayName.substringAfterLast('/').take(160)).commit()
                                snapshot
                            }.getOrNull()
                            main.post {
                                if (active(activity) && BillingBackend.uid() == uid) {
                                    if (verifiedSnapshot != null) onAuthorized(verifiedSnapshot) else message(activity, "Le PDF a changé : vérifie de nouveau ses droits.")
                                }
                            }
                        }
                    } else {
                        val denied = (task.exception as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.PERMISSION_DENIED
                        if (denied) BillingOffers.show(activity, hash) { require(activity, file, displayName, onAuthorized) }
                        else message(activity, "Vérification PDF indisponible. Aucun aperçu ni export débloqué.")
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
            val hash = BillingContract.documentId(file)
            val result = Tasks.await(BillingBackend.call("billingAuthorizePdf", mapOf("documentSha256" to hash)), 20, TimeUnit.SECONDS)
            BillingBackend.uid() == uid && BillingContract.authorizedPdf(result, hash) && BillingContract.documentId(file) == hash
        }.getOrDefault(false)
    }

    fun authorizeBackground(context: Context, file: File): Boolean = authorizeBackgroundBlocking(context, file)
    private fun active(activity: Activity) = !activity.isDestroyed && !activity.isFinishing
    private fun message(activity: Activity, message: String) { if (active(activity)) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
