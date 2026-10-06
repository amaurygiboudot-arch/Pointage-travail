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

/** Play supplies purchases; only authenticated backend verification supplies entitlements. */
object HoraTrackBilling : PurchasesUpdatedListener {
    data class Offer(val details: ProductDetails, val token: String, val label: String)
    private val main = Handler(Looper.getMainLooper())
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
            listOf(BillingClient.ProductType.SUBS to listOf(BillingContract.PREMIUM),
                BillingClient.ProductType.INAPP to listOf(BillingContract.PDF)).forEach { (type, ids) ->
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
                                        offers += Offer(product, offer.offerToken, "Premium — ${phase.formattedPrice}/$period, renouvellement automatique")
                                    }
                                }
                            } else {
                                product.oneTimePurchaseOfferDetailsList.orEmpty().forEach { offer ->
                                    offers += Offer(product, offer.offerToken, "Ce PDF — ${offer.formattedPrice}, achat unique")
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
        if (offer.details.productId !in setOf(BillingContract.PREMIUM, BillingContract.PDF)) return
        if (offer.details.productId == BillingContract.PDF) {
            if (documentId == null || !Regex("[a-f0-9]{64}").matches(documentId)) return
        }
        purchasing = true
        completion = onVerified
        purchaseActivity = WeakReference(activity)
        connected(activity) { ready ->
            if (!ready || BillingBackend.uid() != uid || activity.isFinishing || activity.isDestroyed) {
                purchasing = false; completion = null; toast(activity, "Google Play indisponible : aucun droit débloqué."); return@connected
            }
            val builder = BillingFlowParams.newBuilder()
                .setObfuscatedAccountId(BillingContract.accountId(uid))
                .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(offer.details).setOfferToken(offer.token).build()))
            // Immutable Play-side binding survives pending payment, process death and account/device changes.
            if (offer.details.productId == BillingContract.PDF) builder.setObfuscatedProfileId(documentId!!)
            val params = builder.build()
            val result = client!!.launchBillingFlow(activity, params)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchasing = false; completion = null
                if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restore(activity) { ok -> if (ok) onVerified() }
                else toast(activity, "Achat non lancé. Aucun droit débloqué.")
            }
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
        if (BillingBackend.uid() == null) { if (!quiet) toast(activity, "Connecte ton compte pour restaurer les achats."); onComplete(false); return }
        connected(activity) { ready ->
            if (!ready) { if (!quiet) toast(activity, "Google Play indisponible."); onComplete(false); return@connected }
            val purchases = mutableListOf<Purchase>()
            var remaining = 2
            var querySuccess = true
            listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).forEach { type ->
                client!!.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, found ->
                    querySuccess = querySuccess && result.responseCode == BillingClient.BillingResponseCode.OK
                    purchases += found
                    remaining--
                    if (remaining == 0) verifyPurchases(purchases) { verified ->
                        BillingBackend.call("billingGetEntitlements").addOnCompleteListener { task ->
                            val ok = querySuccess && verified && task.isSuccessful
                            if (!quiet) toast(activity, if (ok) "Achats restaurés et vérifiés." else "Restauration incomplète. Aucun droit local débloqué.")
                            onComplete(ok)
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
            if (index == completed.size) { onComplete(!pending); return }
            val purchase = completed[index]
            val product = purchase.products.singleOrNull()
            if (product !in BillingContract.products) { next(index + 1); return }
            if (BillingBackend.uid() != uid) { onComplete(false); return }
            val payload = mutableMapOf<String, Any>("productId" to product!!, "purchaseToken" to purchase.purchaseToken)
            if (product == BillingContract.PDF) {
                val hash = purchase.accountIdentifiers?.obfuscatedProfileId
                if (hash == null || !Regex("[a-f0-9]{64}").matches(hash)) { pending = true; next(index + 1); return }
                payload["documentSha256"] = hash
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
