package app.satisfyingsand.play

import android.app.Activity
import android.content.Context
import android.util.Log
import app.satisfyingsand.BuildConfig
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * The one in-app purchase: a one-time "Remove ads" product. Ownership is cached locally so ads
 * stay off offline, and is re-checked with Google Play on every launch ("restore purchases" is
 * automatic, including on a new phone signed into the same Google account).
 */
class Store(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("store", Context.MODE_PRIVATE)
    private var details: ProductDetails? = null
    /** Called on the main thread when ownership or the price changes. */
    var onChange: (() -> Unit)? = null

    val adsRemoved: Boolean get() = prefs.getBoolean(KEY_ADS_REMOVED, false)

    /** Localized price, e.g. "$2.99", once Play has returned the product. */
    val price: String? get() = details?.oneTimePurchaseOfferDetails?.formattedPrice

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases.orEmpty(), completeList = false)
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        try {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                    refresh()
                    loadProduct()
                }
                override fun onBillingServiceDisconnected() = Unit
            })
        } catch (e: Exception) {
            Log.w(TAG, "billing unavailable", e)
        }
    }

    /** Re-checks what the player owns (also used by the "Restore purchase" button). */
    fun refresh() {
        try {
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, completeList = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "purchase query failed", e)
        }
    }

    private fun loadProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BuildConfig.REMOVE_ADS_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, res ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                details = res.productDetailsList.firstOrNull()
                activity.runOnUiThread { onChange?.invoke() }
            }
        }
    }

    /** Opens Google Play's purchase sheet. Returns false if the product isn't available. */
    fun buyRemoveAds(): Boolean {
        val d = details ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * [completeList] is true for a full "what do I own" query (so a missing purchase means it was
     * refunded or revoked) and false for a purchase that just happened.
     */
    private fun handle(purchases: List<Purchase>, completeList: Boolean) {
        var owned = false
        for (p in purchases) {
            if (BuildConfig.REMOVE_ADS_PRODUCT_ID !in p.products) continue
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue // pending: wait
            owned = true
            if (!p.isAcknowledged) {
                // Unacknowledged purchases are refunded by Google after 3 days.
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
            }
        }
        if (owned || completeList) prefs.edit().putBoolean(KEY_ADS_REMOVED, owned).apply()
        activity.runOnUiThread { onChange?.invoke() }
    }

    fun end() = try { client.endConnection() } catch (e: Exception) { }

    private companion object {
        const val KEY_ADS_REMOVED = "ads_removed"
        const val TAG = "Store"
    }
}
