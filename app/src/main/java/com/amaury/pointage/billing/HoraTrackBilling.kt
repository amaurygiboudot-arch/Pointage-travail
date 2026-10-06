package com.amaury.pointage.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.android.billingclient.api.*
import java.lang.ref.WeakReference
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.concurrent.Executors

/** Play supplies purchases; only authenticated backend verification supplies entitlements. */
object HoraTrackBilling : PurchasesUpdatedListener {
    data class Offer(val details: ProductDetails, val token: String, val label: String)
    private val main = Handler(Looper.getMainLooper())
    private val serviceIo = Executors.newSingleThreadExecutor()
    private var client: BillingClient? = null
    private var connecting = false
    private val waiters = mutableListOf<(Boolean) -> Unit>()
    private var completion: (() -> Unit)? = null
    private var purchaseActivity: WeakReference<Activity>? = null
    private var purchasing = false

    fun initialize(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { initialize(context) }; return }
        if (client != null) return
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { restore(activity, quiet = true) {} }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (purchaseActivity?.get() === activity) { completion = null; purchaseActivity = null }
            }
        })
    }

    private fun connected(context: Context, action: (Boolean) -> Unit) {
        initialize(context)
        val billing = client ?: return action(false)
        if (billing.isReady) { action(true); return }
        waiters += action
        if (connecting) return
        connecting = true
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                val callbacks = waiters.toList(); waiters.clear()
                callbacks.forEach { it(result.responseCode == BillingClient.BillingResponseCode.OK) }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    fun queryOffers(activity: Activity, callback: (List<Offer>) -> Unit) {
        connected(activity) { ready ->
            if (!ready) { callback(emptyList()); return@connected }
            val offers = mutableListOf<Offer>()
            var remaining = 2
            fun received() { remaining--; if (remaining == 0) callback(offers.toList()) }
            listOf(BillingClient.ProductType.SUBS to listOf(BillingContract.PREMIUM, BillingContract.PLUS),
                BillingClient.ProductType.INAPP to (listOf(BillingContract.PDF) + BillingContract.serviceProducts)).forEach { (type, ids) ->
                val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
                }).build()
                client!!.queryProductDetailsAsync(params) { result, response ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        response.productDetailsList.forEach { product ->
                            if (type == BillingClient.ProductType.SUBS) {
                                product.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }.forEach { offer ->
                                    val phase = offer.pricingPhases.pricingPhaseList.lastOrNull()
                                    if (phase != null && phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING &&
                                        phase.billingPeriod in setOf("P1M", "P1Y")) {
                                        val period = if (phase.billingPeriod == "P1Y") "an" else "mois"
                                        val name = if (product.productId == BillingContract.PLUS) "Premium + analyses (1 bulletin par mois)" else "Premium"
                                        offers += Offer(product, offer.offerToken, "$name — ${phase.formattedPrice}/$period, renouvellement automatique")
                                    }
                                }
                            } else {
                                product.oneTimePurchaseOfferDetailsList.orEmpty().forEach { offer ->
                                    val name = BillingServiceCatalog.services.find { it.productId == product.productId }?.title ?: "Ce PDF"
                                    val token = offer.offerToken
                                    if (token != null) offers += Offer(product, token, "$name — ${offer.formattedPrice}, achat unique")
                                }
                            }
                        }
                    }
                    received()
                }
            }
        }
    }

    fun purchase(activity: Activity, offer: Offer, documentId: String? = null, onVerified: () -> Unit = {}) {
        val uid = BillingBackend.uid()
        if (uid == null) { toast(activity, "Connecte ton compte avant d'acheter."); return }
        if (purchasing) { toast(activity, "Un achat est déjà en cours. Restaure les achats avant de réessayer."); return }
        if (offer.details.productId !in BillingContract.products) return
        val service = offer.details.productId in BillingContract.serviceProducts
        val analysisReady = BillingServiceCatalog.services.any { it.productId == BillingContract.ANALYSIS && it.availability == BillingServiceCatalog.Availability.READY }
        if (offer.details.productId == BillingContract.PLUS && !analysisReady) return
        if (service && BillingServiceCatalog.services.none { it.productId == offer.details.productId && it.availability == BillingServiceCatalog.Availability.READY }) return
        if (service && (documentId == null || BillingServiceFlow.prepared(activity, uid, documentId)?.productId != offer.details.productId)) {
            toast(activity, "Prépare d'abord ce rapport avant de payer."); return
        }
        if (offer.details.productId == BillingContract.PDF || service) {
            if (documentId == null || !Regex("[a-f0-9]{64}").matches(documentId)) return
        }
        purchasing = true
        completion = onVerified
        purchaseActivity = WeakReference(activity)
        connected(activity) { ready ->
            if (!ready || BillingBackend.uid() != uid || activity.isFinishing || activity.isDestroyed) {
                purchasing = false; completion = null; toast(activity, "Google Play indisponible : aucun droit débloqué."); return@connected
            }
            fun launchVerifiedOffer() {
                if (BillingBackend.uid() != uid || activity.isFinishing || activity.isDestroyed) {
                    purchasing = false; completion = null; return
                }
            val builder = BillingFlowParams.newBuilder()
                .setObfuscatedAccountId(BillingContract.accountId(uid))
                .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(offer.details).setOfferToken(offer.token).build()))
            // Immutable Play-side binding survives pending payment, process death and account/device changes.
            if (offer.details.productId == BillingContract.PDF || service) builder.setObfuscatedProfileId(documentId!!)
            val params = builder.build()
            val result = client!!.launchBillingFlow(activity, params)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchasing = false; completion = null
                if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restore(activity) { ok -> if (ok) onVerified() }
                else toast(activity, "Achat non lancé. Aucun droit débloqué.")
            }
            }
            if (offer.details.productId in setOf(BillingContract.PREMIUM, BillingContract.PLUS)) {
                client!!.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { query, subscriptions ->
                    val overlapping = subscriptions.any { it.purchaseState != Purchase.PurchaseState.UNSPECIFIED_STATE &&
                        it.products.any { product -> product in setOf(BillingContract.PREMIUM, BillingContract.PLUS) } }
                    if (query.responseCode != BillingClient.BillingResponseCode.OK || overlapping || BillingBackend.uid() != uid) {
                        purchasing = false; completion = null
                        toast(activity, "Vérifie ou gère ton abonnement existant. Aucun second abonnement lancé.")
                    } else BillingBackend.call("billingGetEntitlements").addOnCompleteListener { task ->
                        val rights = if (task.isSuccessful) task.result as? Map<*, *> else null
                        if (BillingBackend.uid() != uid || rights == null || rights["obfuscatedAccountId"] != BillingContract.accountId(uid) ||
                            rights["owner"] == true || rights["premium"] == true || rights["plus"] == true) {
                            purchasing = false; completion = null
                            toast(activity, "Aucun second abonnement lancé. Vérifie ou gère ton abonnement Google Play.")
                        } else launchVerifiedOffer()
                    }
                }
            } else if (service) {
                val report = BillingServiceFlow.prepared(activity, uid, documentId!!)
                serviceIo.execute {
                    val intact = report != null && runCatching { BillingContract.documentId(report.file) == documentId }.getOrDefault(false)
                    main.post {
                        if (!intact || report == null || BillingBackend.uid() != uid || activity.isFinishing || activity.isDestroyed) {
                            purchasing = false; completion = null
                            toast(activity, "Rapport préparé introuvable ou modifié. Aucun achat lancé.")
                            return@post
                        }
                        BillingBackend.call("billingAuthorizeReport", mapOf("reportId" to report.reportId,
                            "documentSha256" to report.documentId, "usePlusCredit" to false, "useAnalysisCredit" to false))
                            .addOnCompleteListener { task ->
                                if (BillingBackend.uid() != uid || activity.isFinishing || activity.isDestroyed) {
                                    purchasing = false; completion = null; return@addOnCompleteListener
                                }
                                val response = if (task.isSuccessful) task.result as? Map<*, *> else null
                                if (task.isSuccessful && BillingContract.authorizedPdf(response, report.documentId) &&
                                    response != null && response["reportId"] == report.reportId && response["productId"] == report.productId) {
                                    purchasing = false; completion = null
                                    onVerified()
                                } else if ((task.exception as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.PERMISSION_DENIED) {
                                    launchVerifiedOffer()
                                } else {
                                    purchasing = false; completion = null
                                    toast(activity, "Vérification du rapport indisponible. Aucun achat lancé.")
                                }
                            }
                    }
                }
            } else launchVerifiedOffer()
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val activity = purchaseActivity?.get()
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchasing = false
            completion = null
            if (activity != null && result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED)
                toast(activity, "Paiement non confirmé. Restaure les achats pour réessayer.")
            return
        }
        val done = completion
        completion = null
        verifyPurchases(purchases.orEmpty()) { successful ->
            purchasing = false
            if (successful) done?.invoke()
            else if (activity != null) toast(activity, "Paiement en attente ou vérification indisponible. Aucun aperçu débloqué ; utilise Restaurer les achats.")
        }
    }

    fun restore(activity: Activity, quiet: Boolean = false, onComplete: (Boolean) -> Unit) {
        val restoreUid = BillingBackend.uid()
        if (restoreUid == null) { if (!quiet) toast(activity, "Connecte ton compte pour restaurer les achats."); onComplete(false); return }
        connected(activity) { ready ->
            if (!ready || BillingBackend.uid() != restoreUid) { if (!quiet) toast(activity, "Google Play indisponible."); onComplete(false); return@connected }
            val purchases = mutableListOf<Purchase>()
            var remaining = 2
            var querySuccess = true
            listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).forEach { type ->
                client!!.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, found ->
                    querySuccess = querySuccess && result.responseCode == BillingClient.BillingResponseCode.OK && BillingBackend.uid() == restoreUid
                    purchases += found
                    remaining--
                    if (remaining == 0) {
                        if (BillingBackend.uid() != restoreUid) { onComplete(false); return@queryPurchasesAsync }
                        verifyPurchases(purchases) { verified ->
                            if (BillingBackend.uid() != restoreUid) { onComplete(false); return@verifyPurchases }
                            BillingBackend.call("billingGetEntitlements").addOnCompleteListener { task ->
                                val rights = if (task.isSuccessful) task.result as? Map<*, *> else null
                                val ok = querySuccess && verified && BillingBackend.uid() == restoreUid &&
                                    rights?.get("obfuscatedAccountId") == BillingContract.accountId(restoreUid)
                                // A destroyed purchase activity has no callback. A successful non-pending
                                // restore releases that stale lock without interrupting a live Play flow.
                                if (ok && completion == null && purchases.none { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                                    purchasing = false
                                    purchaseActivity = null
                                }
                                if (!quiet) toast(activity, if (ok) "Achats restaurés et vérifiés." else "Restauration incomplète. Aucun droit local débloqué.")
                                onComplete(ok)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun verifyPurchases(purchases: List<Purchase>, onComplete: (Boolean) -> Unit) {
        val uid = BillingBackend.uid() ?: return onComplete(false)
        val completed = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        var pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        fun next(index: Int) {
            if (index == completed.size) { onComplete(!pending && BillingBackend.uid() == uid); return }
            val purchase = completed[index]
            val product = purchase.products.singleOrNull()
            if (product !in BillingContract.products) { next(index + 1); return }
            if (BillingBackend.uid() != uid) { onComplete(false); return }
            val payload = mutableMapOf<String, Any>("productId" to product!!, "purchaseToken" to purchase.purchaseToken)
            if (product == BillingContract.PDF || product in BillingContract.serviceProducts) {
                val hash = purchase.accountIdentifiers?.obfuscatedProfileId
                if (!BillingContract.validPurchaseDocument(product, hash)) { pending = true; next(index + 1); return }
                if (hash != null) payload["documentSha256"] = hash
                if (hash != null && product in BillingContract.serviceProducts) {
                    purchaseActivity?.get()?.let { context ->
                        BillingServiceFlow.prepared(context, uid, hash)?.let { payload["reportId"] = it.reportId }
                    }
                }
            }
            BillingBackend.call("billingVerifyPurchase", payload).addOnCompleteListener { task ->
                if (!task.isSuccessful || BillingBackend.uid() != uid) pending = true
                next(index + 1)
            }
        }
        next(0)
    }

    private fun toast(activity: Activity, message: String) { if (!activity.isDestroyed && !activity.isFinishing) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
