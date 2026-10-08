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
    fun show(activity: Activity, documentId: String? = null, onClosed: () -> Unit = {}, onVerified: () -> Unit = {}) {
        if (documentId != null) {
            showPurchasable(activity, documentId, onVerified, onClosed)
            return
        }
        AlertDialog.Builder(activity).setTitle("Premium et services")
            .setItems(arrayOf("Abonnements et offres Google Play", "Analyses et rapports", "Restaurer les achats", "Mes PDF", "Mes rapports préparés", "Gérer mes abonnements")) { _, index ->
                when (index) {
                    0 -> showPurchasable(activity, null, onVerified)
                    1 -> showServiceCatalog(activity)
                    2 -> {
                        HoraTrackBilling.initialize(activity)
                        HoraTrackBilling.restore(activity) { ok -> if (ok) onVerified() else onClosed() }
                    }
                    3 -> showArchive(activity)
                    4 -> BillingServiceFlow.showPending(activity)
                    5 -> runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/account/subscriptions?package=com.amaury.pointage")))
                    }.onFailure { Toast.makeText(activity, "Impossible d'ouvrir la gestion Google Play.", Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun showServiceCatalog(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("Analyses et rapports — PDF inclus")
            .setItems(BillingServiceCatalog.services.map { it.title }.toTypedArray()) { _, index ->
                val service = BillingServiceCatalog.services[index]
                if (service.availability != BillingServiceCatalog.Availability.READY) {
                    AlertDialog.Builder(activity).setTitle(service.title)
                        .setMessage("${service.description}\n\nEn préparation : aucun paiement disponible.")
                        .setPositiveButton("Compris", null).show()
                } else {
                    AlertDialog.Builder(activity).setTitle(service.title)
                        .setMessage("${service.description}\n\nLes données nécessaires seront vérifiées avant toute proposition d'achat. Seul le prix fourni par Google Play sera affiché au paiement.")
                        .setPositiveButton("Préparer le rapport") { _, _ -> BillingServiceFlow.start(activity, service.productId) }
                        .setNegativeButton("Annuler", null).show()
                }
            }.setNegativeButton("Fermer", null).show()
    }

    fun showServicePurchase(activity: Activity, report: BillingServiceFlow.Report, onUsePlusCredit: () -> Unit, onUseLegacyCredit: () -> Unit, onClosed: () -> Unit = {}, onVerified: () -> Unit) {
        val uid = BillingBackend.uid() ?: run { onClosed(); return }
        if (BillingServiceFlow.prepared(activity, uid, report.documentId)?.reportId != report.reportId) { onClosed(); return }
        BillingBackend.call("billingGetEntitlements").addOnCompleteListener { entitlementTask ->
            val entitlement = entitlementTask.resultOrNull() as? Map<*, *>
            if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid || entitlement == null ||
                entitlement["obfuscatedAccountId"] != BillingContract.accountId(uid)) { onClosed(); return@addOnCompleteListener }
            val included = entitlement["owner"] == true || entitlement["premium"] == true || entitlement["plus"] == true
            HoraTrackBilling.queryOffers(activity) { offers ->
            if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid) { onClosed(); return@queryOffers }
            val available = offers.filter { it.details.productId == report.productId ||
                (!included && report.productId == BillingContract.ANALYSIS && it.details.productId == BillingContract.PLUS) }
            val builder = AlertDialog.Builder(activity).setTitle("Débloquer ce rapport — PDF inclus")
                .setMessage("Le rapport est préparé. Aucun aperçu avant validation serveur.\n\n" +
                    if (available.isEmpty() && report.productId == BillingContract.ANALYSIS) "Aucune offre Google Play active pour ce bulletin. Aucun achat possible actuellement ; un crédit Plus ou un ancien crédit vérifié peut être utilisé si ton compte en dispose."
                    else if (available.isEmpty()) "Aucune offre Google Play active pour cette prestation. Aucun achat possible actuellement. Premium et Plus ne comprennent pas cette prestation."
                    else if (report.productId == BillingContract.ANALYSIS) "Achat unique pour ce rapport, ou Premium + analyses pour un bulletin par mois. Le contrôle porte sur les montants confirmés disponibles, pas toutes les lignes du bulletin. Les prix et périodes ci-dessous sont ceux de Google Play."
                    else "Achat unique pour ce rapport, PDF inclus. Les abonnements Premium et Plus ne comprennent pas cette prestation. Le prix affiché est celui de Google Play.")
                .setNeutralButton("Restaurer et vérifier") { _, _ -> HoraTrackBilling.restore(activity) { ok -> if (ok && BillingBackend.uid() == uid) onVerified() else onClosed() } }
                .setNegativeButton("Plus tard") { _, _ -> onClosed() }.setOnCancelListener { onClosed() }
            val plusCredit = report.productId == BillingContract.ANALYSIS && entitlement["plusAnalysisCreditAvailable"] == true
            val legacyCredit = report.productId == BillingContract.ANALYSIS && (entitlement["legacyAnalysisCredits"] as? Number)?.toInt()?.let { it > 0 } == true
            if (available.isNotEmpty() || plusCredit || legacyCredit) builder.setPositiveButton("Choisir") { _, _ ->
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (plusCredit) actions += "Utiliser mon analyse mensuelle Plus" to onUsePlusCredit
                if (legacyCredit) actions += "Utiliser un ancien crédit d'analyse" to onUseLegacyCredit
                available.forEach { offer -> actions += offer.label to { HoraTrackBilling.purchase(activity, offer, report.documentId, onVerified) { onClosed() } } }
                AlertDialog.Builder(activity).setTitle("Débloquer le rapport — PDF inclus")
                    .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                        if (BillingBackend.uid() == uid) actions[index].second()
                    }.setNegativeButton("Annuler") { _, _ -> onClosed() }.setOnCancelListener { onClosed() }.show()
            }
            builder.show()
            }
        }
    }

    private fun showPurchasable(activity: Activity, documentId: String?, onVerified: () -> Unit, onClosed: () -> Unit = {}) {
        HoraTrackBilling.initialize(activity)
        val uid = BillingBackend.uid()
        BillingBackend.call("billingGetEntitlements").addOnCompleteListener { task ->
            if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid) { onClosed(); return@addOnCompleteListener }
            val entitlements = task.resultOrNull() as? Map<*, *>
            if (uid == null || entitlements == null || entitlements["obfuscatedAccountId"] != BillingContract.accountId(uid)) {
                Toast.makeText(activity, "Connecte ton compte ; la vérification serveur des achats doit être disponible avant de payer.", Toast.LENGTH_LONG).show()
                onClosed(); return@addOnCompleteListener
            }
            val included = entitlements["owner"] == true || entitlements["premium"] == true || entitlements["plus"] == true
            HoraTrackBilling.queryOffers(activity) { offers ->
                if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid) { onClosed(); return@queryOffers }
                val analysisReady = BillingServiceCatalog.services.any { it.productId == BillingContract.ANALYSIS && it.availability == BillingServiceCatalog.Availability.READY }
                val available = offers.filter { (!included && (it.details.productId == BillingContract.PREMIUM || (analysisReady && it.details.productId == BillingContract.PLUS))) ||
                    (documentId != null && !included && it.details.productId == BillingContract.PDF) }
                val labels = available.map { it.label }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle(if (documentId == null) "AGKGMG Premium" else "Débloquer ce PDF")
                    .setMessage(
                        (if (included) "Ton compte dispose des PDF inclus.\n\n" else "Premium inclut les PDF. Un PDF acheté séparément reste téléchargeable sans repayer pour ce même document.\n\n") +
                            "Aucun aperçu avant paiement vérifié. Premium + analyses inclut un bulletin par mois ; les autres prestations sont des achats séparés.\n\n" +
                            if (available.isEmpty()) "Aucune offre Google Play disponible actuellement. Les produits doivent être activés dans Play Console." else "Choisis une offre ci-dessous. Les tarifs sont ceux de Google Play."
                    )
                    .setPositiveButton("Voir les offres") { _, _ ->
                        if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid) { onClosed(); return@setPositiveButton }
                        if (available.isEmpty()) { onClosed(); return@setPositiveButton }
                        AlertDialog.Builder(activity).setTitle("Offres Google Play")
                            .setItems(labels) { _, index ->
                                if (activity.isFinishing || activity.isDestroyed || BillingBackend.uid() != uid) onClosed()
                                else HoraTrackBilling.purchase(activity, available[index], documentId, onVerified) { onClosed() }
                            }
                            .setNegativeButton("Annuler") { _, _ -> onClosed() }.setOnCancelListener { onClosed() }.show()
                    }
                    .setNeutralButton("Restaurer les achats") { _, _ ->
                        HoraTrackBilling.restore(activity) { ok -> if (ok && !activity.isFinishing && !activity.isDestroyed && BillingBackend.uid() == uid) onVerified() else onClosed() }
                    }
                    .setNegativeButton(if (documentId == null) "Mes PDF" else "Annuler") { _, _ -> if (documentId == null) showArchive(activity) else onClosed() }
                    .setOnCancelListener { onClosed() }
                    .show()
            }
        }
    }

    private fun showArchive(activity: Activity) {
        val uid = BillingBackend.uid() ?: return
        val files = PdfPendingVault.availableDocuments(activity.filesDir, BillingContract.accountId(uid))
        if (files.isEmpty()) {
            Toast.makeText(activity, "Aucun PDF conservé sur cet appareil pour ce compte.", Toast.LENGTH_LONG).show(); return
        }
        val names = activity.getSharedPreferences("billing_pdf_names", Context.MODE_PRIVATE)
        val dates = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        fun name(file: File): String {
            val stored = names.getString("${BillingContract.accountId(uid)}_${file.nameWithoutExtension}", null)
            return stored?.takeUnless { it.matches(Regex("[a-f0-9]{64}\\.pdf")) }
                ?: PdfPendingVault.name(file)
                ?: "PDF du ${dates.format(Date(file.lastModified()))}"
        }
        AlertDialog.Builder(activity).setTitle("Mes PDF — droits à vérifier avant ouverture")
            .setItems(files.map { "${name(it)} — ${dates.format(Date(it.lastModified()))}" }.toTypedArray()) { _, index ->
                if (BillingBackend.uid() != uid) return@setItems
                val displayName = name(files[index]).let { if (it.endsWith(".pdf")) it else "AGKGMG.pdf" }
                BillingPdfGate.require(activity, files[index], displayName) { authorized ->
                    if (BillingBackend.uid() != uid) return@require
                    activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                        putExtra("pdf_path", authorized.absolutePath)
                        putExtra("pdf_name", displayName)
                    })
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun com.google.android.gms.tasks.Task<Any?>.resultOrNull(): Any? = if (isSuccessful) result else null
}
