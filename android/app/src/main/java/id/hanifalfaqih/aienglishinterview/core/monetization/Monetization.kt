package id.hanifalfaqih.aienglishinterview.core.monetization

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Entitlement identifying premium interview practice. This identifier is
 * chosen by the app; the RevenueCat dashboard must define an entitlement
 * with exactly this id and attach at least one product to it. No Play
 * product ids are hardcoded — offers are read from the dashboard at
 * runtime, so Play configuration stays dashboard-side.
 */
const val PREMIUM_ENTITLEMENT_ID = "premium"

/** Premium interview practice: detailed per-answer feedback. */
const val PREMIUM_BLURB = "Premium unlocks detailed per-answer feedback and " +
    "professional communication coaching. Interviews and overall feedback stay free."

sealed interface PremiumState {
    data object Loading : PremiumState
    data class Determined(val isPremium: Boolean) : PremiumState

    /** SDK not configured (no key) — paywall cannot function. */
    data class Unavailable(val reason: String) : PremiumState
}

data class PaywallOffer(
    val id: String,
    val title: String,
    val price: String,
)

sealed interface MonetizationResult<out T> {
    data class Success<T>(val value: T) : MonetizationResult<T>
    data class Failure(val message: String) : MonetizationResult<Nothing>

    /** SDK not configured; caller should show the unavailable state. */
    data class Unavailable(val reason: String) : MonetizationResult<Nothing>
}

sealed interface PurchaseOutcome {
    data object Success : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data class Failure(val message: String) : PurchaseOutcome
    data class Unavailable(val reason: String) : PurchaseOutcome
}

/**
 * Monetization boundary. UI and features ask about entitlement, offers,
 * purchase, and restore here — never touching the RevenueCat SDK directly.
 * Entitlement truth always comes from RevenueCat, never from a local flag.
 */
interface MonetizationRepository {
    val premiumState: StateFlow<PremiumState>

    /** Refresh entitlement state from RevenueCat. */
    suspend fun refresh()

    /** Current offering's purchasable packages, or why none exist. */
    suspend fun loadOffers(): MonetizationResult<List<PaywallOffer>>

    /** Purchase an offer previously returned by [loadOffers]. */
    suspend fun purchase(activity: Activity, offerId: String): PurchaseOutcome

    /** Restore previous purchases; value is the resulting premium state. */
    suspend fun restore(): MonetizationResult<Boolean>
}
