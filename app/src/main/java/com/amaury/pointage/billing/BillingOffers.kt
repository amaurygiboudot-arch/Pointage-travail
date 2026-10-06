package com.amaury.pointage.billing

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.amaury.pointage.PdfPreviewActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context

object BillingOffers {
    fun show(activity: Activity, documentId: String? = null, onVerified: () -> Unit = {}) {
        HoraTrackBilling.initialize(activity)
        val uid = BillingBackend.uid()
        BillingBackend.call("billingGetEntitlements").addOnCompleteListener { task ->
            if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid) return@addOnCompleteListener
            val entitlements = task.resultOrNull() as? Map<*, *>
            if (uid == null || entitlements == null || entitlements["obfuscatedAccountId"] != BillingContract.accountId(uid)) {
                Toast.makeText(activity, "Connecte ton compte ; la vérification serveur des achats doit être disponible avant de payer.", Toast.LENGTH_LONG).show()
                return@addOnCompleteListener
            }
            val included = entitlements["owner"] == true || entitlements["premium"] == true || entitlements["plus"] == true
            HoraTrackBilling.queryOffers(activity) { offers ->
                if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid) return@queryOffers
                val available = offers.filter { (!included && it.details.productId == BillingContract.PREMIUM) ||
                    (documentId != null && !included && it.details.productId == BillingContract.PDF) }
                val labels = available.map { it.label }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle(if (documentId == null) "HoraTrack Premium" else "Débloquer ce PDF")
                    .setMessage(
                        (if (included) "Ton compte dispose des PDF inclus.\n\n" else "Premium inclut les PDF. Un PDF acheté séparément reste téléchargeable sans repayer pour ce même document.\n\n") +
                            "Aucun aperçu avant paiement vérifié. Plus et analyses : en préparation, achat indisponible.\n\n" +
                            if (available.isEmpty()) "Aucune offre Google Play disponible actuellement. Les produits doivent être activés dans Play Console." else "Choisis une offre ci-dessous. Les tarifs sont ceux de Google Play."
                    )
                    .setPositiveButton("Voir les offres") { _, _ ->
                        if (available.isEmpty()) return@setPositiveButton
                        AlertDialog.Builder(activity).setTitle("Offres Google Play")
                            .setItems(labels) { _, index -> HoraTrackBilling.purchase(activity, available[index], documentId, onVerified) }
                            .setNegativeButton("Annuler", null).show()
                    }
                    .setNeutralButton("Restaurer les achats") { _, _ ->
                        HoraTrackBilling.restore(activity) { ok -> if (ok) onVerified() }
                    }
                    .setNegativeButton("Mes PDF") { _, _ -> showArchive(activity) }
                    .show()
            }
        }
    }

    private fun showArchive(activity: Activity) {
        val uid = BillingBackend.uid() ?: return
        val folder = File(activity.filesDir, "billing_pdf_archive/${BillingContract.accountId(uid)}")
        val files = folder.listFiles().orEmpty().filter { it.isFile && Regex("[a-f0-9]{64}\\.pdf").matches(it.name) }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            Toast.makeText(activity, "Aucun PDF conservé sur cet appareil pour ce compte.", Toast.LENGTH_LONG).show(); return
        }
        val names = activity.getSharedPreferences("billing_pdf_names", Context.MODE_PRIVATE)
        val dates = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        fun name(file: File): String {
            val stored = names.getString("${BillingContract.accountId(uid)}_${file.nameWithoutExtension}", null)
            return stored?.takeUnless { it.matches(Regex("[a-f0-9]{64}\\.pdf")) }
                ?: "PDF du ${dates.format(Date(file.lastModified()))}"
        }
        AlertDialog.Builder(activity).setTitle("Mes PDF conservés sur cet appareil")
            .setItems(files.map { "${name(it)} — ${dates.format(Date(it.lastModified()))}" }.toTypedArray()) { _, index ->
                if (BillingBackend.uid() != uid) return@setItems
                activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                    putExtra("pdf_path", files[index].absolutePath)
                    putExtra("pdf_name", name(files[index]).let { if (it.endsWith(".pdf")) it else "HoraTrack.pdf" })
                })
            }.setNegativeButton("Fermer", null).show()
    }

    private fun com.google.android.gms.tasks.Task<Any?>.resultOrNull(): Any? = if (isSuccessful) result else null
}
