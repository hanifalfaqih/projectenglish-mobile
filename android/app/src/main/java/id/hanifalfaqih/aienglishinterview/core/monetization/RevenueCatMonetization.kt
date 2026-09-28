package id.hanifalfaqih.aienglishinterview.core.monetization

import android.app.Activity
import android.content.Context
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import id.hanifalfaqih.aienglishinterview.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-time SDK setup. Call from the entry point before any monetization
 * use. Returns false (leaving the SDK unconfigured) when no public key is
 * supplied — the repository then reports [PremiumState.Unavailable] and the
 * app keeps working without monetization.
 *
 * Only the PUBLIC client key belongs here (Test Store key for debug, Google
 * Play key for release, via `revenueCatApiKey`). Never put a secret key in
 * the app.
 */
object RevenueCatConfig {
    fun init(appContext: Context): Boolean {
        val key = BuildConfig.REVENUECAT_API_KEY.trim()
        if (key.isEmpty()) return false
        if (Purchases.isConfigured) return true
        Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO
        Purchases.configure(PurchasesConfiguration.Builder(appContext, key).build())
        return true
    }
}

/** Real [MonetizationRepository] backed by the RevenueCat SDK. */
class RevenueCatMonetization : MonetizationRepository {

    private val _premiumState =
        MutableStateFlow<PremiumState>(PremiumState.Loading)
    override val premiumState: StateFlow<PremiumState> = _premiumState.asStateFlow()

    private fun CustomerInfo.isPremium(): Boolean =
        entitlements[PREMIUM_ENTITLEMENT_ID]?.isActive == true

    override suspend fun refresh() {
        if (!Purchases.isConfigured) {
            _premiumState.value = PremiumState.Unavailable(REVENUECAT_KEY_DOC)
            return
        }
        try {
            val info = Purchases.sharedInstance.awaitCustomerInfo()
            _premiumState.value = PremiumState.Determined(info.isPremium())
        } catch (e: PurchasesException) {
            // Keep the last known state; the UI surfaces errors per-action.
        }
    }

    override suspend fun loadOffers(): MonetizationResult<List<PaywallOffer>> {
        if (!Purchases.isConfigured) {
            return MonetizationResult.Unavailable(REVENUECAT_KEY_DOC)
        }
        val offerings = try {
            Purchases.sharedInstance.awaitOfferings()
        } catch (e: PurchasesException) {
            return MonetizationResult.Failure("Could not load products. Check your connection and retry.")
        }
        val packages = offerings.current?.availablePackages.orEmpty()
        if (packages.isEmpty()) {
            return MonetizationResult.Failure(
                "No products are configured yet. Add a product to the current offering in RevenueCat.",
            )
        }
        return MonetizationResult.Success(
            packages.map { pkg ->
                PaywallOffer(
                    id = pkg.identifier,
                    title = pkg.product.title,
                    price = pkg.product.price.formatted,
                )
            },
        )
    }

    override suspend fun purchase(activity: Activity, offerId: String): PurchaseOutcome {
        if (!Purchases.isConfigured) {
            return PurchaseOutcome.Unavailable(REVENUECAT_KEY_DOC)
        }
        val offerings = try {
            Purchases.sharedInstance.awaitOfferings()
        } catch (e: PurchasesException) {
            return PurchaseOutcome.Failure("Could not load products. Check your connection and retry.")
        }
        val pkg = offerings.current?.availablePackages?.firstOrNull { it.identifier == offerId }
            ?: return PurchaseOutcome.Failure("That product is no longer available.")
        return try {
            val result = Purchases.sharedInstance.awaitPurchase(
                PurchaseParams.Builder(activity, pkg).build(),
            )
            _premiumState.value = PremiumState.Determined(result.customerInfo.isPremium())
            if (result.customerInfo.isPremium()) {
                PurchaseOutcome.Success
            } else {
                PurchaseOutcome.Failure("Purchase completed, but premium is not active yet.")
            }
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) PurchaseOutcome.Cancelled
            else PurchaseOutcome.Failure(e.message ?: "Purchase failed. Please try again.")
        }
    }

    override suspend fun restore(): MonetizationResult<Boolean> {
        if (!Purchases.isConfigured) {
            return MonetizationResult.Unavailable(REVENUECAT_KEY_DOC)
        }
        return try {
            val info = Purchases.sharedInstance.awaitRestore()
            val premium = info.isPremium()
            _premiumState.value = PremiumState.Determined(premium)
            MonetizationResult.Success(premium)
        } catch (e: PurchasesTransactionException) {
            MonetizationResult.Failure("Restore failed. Check your connection and retry.")
        }
    }
}

internal const val REVENUECAT_KEY_DOC =
    "RevenueCat is not configured. Add a public SDK key as revenueCatApiKey " +
        "(Test Store key for debug, Google Play key for release)."
